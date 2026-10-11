@file:Suppress("DEPRECATION")

package com.example.seniorshield.monitoring.call

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.model.CallState
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTelephonyManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Real framework constructor coverage, based on the SDK 26/28/30 probe3 harness.
 * Shadow-driven callbacks verify monitor state transitions, not real-device main-Looper delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyCallMonitorLooperTest {
    private lateinit var monitor: RealCallRiskMonitor
    private lateinit var telephony: ShadowTelephonyManager
    private lateinit var sharingJob: Job
    private var collectorJob: Job? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaught = AtomicReference<Throwable?>(null)

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.compareAndSet(null, error) }
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        telephony = shadowOf(app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
        val settings = mockk<SettingsRepository> {
            every { observeTestModeEnabled() } returns MutableStateFlow(false)
        }
        val contactChecker = mockk<CallerContactChecker> {
            every { checkCaller(any()) } returns CallerCheckResult.NOT_IN_CONTACTS
        }
        monitor = RealCallRiskMonitor(
            app,
            mockk<CallSignalMapper>(relaxed = true),
            contactChecker,
            settings,
            mockk<BankArsRegistry>(relaxed = true),
            RiskSessionTracker(),
        )
        // Test-only cleanup: the singleton scope otherwise outlives Robolectric's sandbox.
        val sharingScope = RealCallRiskMonitor::class.java.getDeclaredField("sharingScope").run {
            isAccessible = true
            get(monitor) as CoroutineScope
        }
        sharingJob = requireNotNull(sharingScope.coroutineContext[Job])
    }

    @After
    fun tearDown() = runBlocking {
        try {
            collectorJob?.cancel()
            if (::sharingJob.isInitialized) {
                sharingJob.cancel()
                awaitCondition { sharingJob.isCompleted && collectorJob?.isCompleted != false }
            }
            assertNull("No uncaught exception through monitor cleanup", uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun backgroundCollectionRegistersLegacyListenerAndEmitsNeutralSeed() = runBlocking {
        val emissions = CopyOnWriteArrayList<Produced<List<RiskSignal>>>()
        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions += it }
        }

        awaitCondition { emissions.isNotEmpty() || uncaught.get() != null }

        assertNull("Legacy listener creation must not crash the sharing worker", uncaught.get())
        assertTrue("Initial SEED is neutral", emissions.first().value.isEmpty())
        assertEquals(0L, emissions.first().producedAtEpoch)
        // K1b also emits neutral on construction failure; registration distinguishes success.
        assertNotNull("A real PhoneStateListener must have been registered", telephony.listener)
        assertEquals(PhoneStateListener.LISTEN_CALL_STATE, telephony.eventFlags)
        assertTrue(requireNotNull(collectorJob).isActive)
    }

    @Test
    fun ringingThenOffhookDrivenOnMainEmitsActiveContext() = runBlocking {
        val states = CopyOnWriteArrayList<CallMonitorState>()
        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallContext().collect { states += it }
        }
        awaitCondition { states.any { it is CallMonitorState.Idle } || uncaught.get() != null }
        assertNull(uncaught.get())
        assertNotNull(telephony.listener)
        assertSame(Looper.getMainLooper(), Looper.myLooper())

        // ShadowTelephonyManager 4.16.1 invokes callbacks synchronously on the calling thread,
        // so this verifies monitor state transitions only.
        // Main-Looper delivery on real devices follows from constructing the listener on main
        // (verified by framework bytecode review for SDK 26/28/30, see IMPL_LOG); it is not proven here.
        // listen() adds its registration AFTER the initial callback, so poll until RINGING arrives.
        awaitCondition {
            telephony.setCallState(TelephonyManager.CALL_STATE_RINGING, "01011112222")
            states.any { it is CallMonitorState.Active && it.context.state == CallState.RINGING }
        }
        telephony.setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        awaitCondition {
            states.any { it is CallMonitorState.Active && it.context.state == CallState.OFFHOOK }
        }

        val active = states.filterIsInstance<CallMonitorState.Active>()
            .first { it.context.state == CallState.OFFHOOK }.context
        assertEquals("01011112222", active.phoneNumber)
        assertEquals(true, active.isUnknownCaller)
        assertNotNull(monitor.currentCallId())
        assertNull(uncaught.get())
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@withTimeout
                delay(10)
            }
        }
    }
}
