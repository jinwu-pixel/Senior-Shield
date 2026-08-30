package com.example.seniorshield.monitoring.call

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [RealCallRiskMonitor]의 IDLE anchor 분기를 legacy callback seam으로 실구동한다. */
class RealCallRiskMonitorAnchorTest {

    private val wallClock = FakeClock(now = 1_000_000L)
    private val elapsedClock = FakeClock(now = 2_000_000L)
    private val tracker = RiskSessionTracker().also { it.clock = wallClock.provider }
    private val listenerSlot = slot<PhoneStateListener>()
    private val telephonyManager = mockk<TelephonyManager>(relaxed = true) {
        every { listen(capture(listenerSlot), any()) } returns Unit
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.TELEPHONY_SERVICE) } returns telephonyManager
    }
    private val contactChecker = mockk<CallerContactChecker> {
        every { checkCaller(any()) } returns CallerCheckResult.NOT_IN_CONTACTS
    }
    private val settingsRepository = mockk<SettingsRepository> {
        every { observeTestModeEnabled() } returns MutableStateFlow(false)
    }
    private val monitor = RealCallRiskMonitor(
        context = context,
        mapper = mockk(relaxed = true),
        contactChecker = contactChecker,
        settingsRepository = settingsRepository,
        bankArsRegistry = mockk(relaxed = true),
        sessionTracker = tracker,
    ).apply {
        clock = wallClock.provider
        monotonicClock = elapsedClock.provider
        sdkIntProvider = { Build.VERSION_CODES.R }
    }

    private val emissions = Channel<Produced<List<RiskSignal>>>(Channel.UNLIMITED)
    private var collectorJob: Job? = null

    @After
    fun tearDown() {
        collectorJob?.cancel()
        OutgoingCallReceiver.clear()
    }

    @Test
    fun `T-A1 safe-confirmed same call idle skips anchor`() = runBlocking {
        seedIdle()
        val callId = startUnknownCall(wallAt = 10_000L, elapsedAt = 100_000L)
        monitor.markCurrentCallConfirmedSafe(callId)

        endActiveCall(wallAt = 20_000L, elapsedAt = 105_000L)

        assertNull("사용자 안전 확인된 동일 callId는 anchor를 장전하지 않음", monitor.lastSuspiciousCallEndedElapsedMs)
    }

    @Test
    fun `T-A2 different call idle sets monotonic anchor`() = runBlocking {
        seedIdle()
        startUnknownCall(wallAt = 2_000L, elapsedAt = 20_000L)
        monitor.markCurrentCallConfirmedSafe(callId = 1_000L)

        endActiveCall(wallAt = 5_000L, elapsedAt = 25_000L)

        assertEquals(
            "다른 callId의 종료 wall 시각이 아니라 monotonic 종료 시각을 anchor로 저장",
            25_000L,
            monitor.lastSuspiciousCallEndedElapsedMs,
        )
    }

    @Test
    fun `T-A3 matching idle clears safe marker for a later reused call id`() = runBlocking {
        seedIdle()
        val callId = startUnknownCall(wallAt = 10_000L, elapsedAt = 100_000L)
        monitor.markCurrentCallConfirmedSafe(callId)
        endActiveCall(wallAt = 20_000L, elapsedAt = 105_000L)
        assertNull(monitor.lastSuspiciousCallEndedElapsedMs)

        val reusedCallId = startUnknownCall(wallAt = callId, elapsedAt = 110_000L)
        assertEquals(callId, reusedCallId)
        endActiveCall(wallAt = 30_000L, elapsedAt = 115_000L)

        assertEquals(
            "첫 IDLE이 marker를 지웠으므로 같은 값의 다음 callId는 anchor를 정상 장전",
            115_000L,
            monitor.lastSuspiciousCallEndedElapsedMs,
        )
    }

    @Test
    fun `T-A3 different idle preserves marker for its matching call`() = runBlocking {
        seedIdle()
        startUnknownCall(wallAt = 2_000L, elapsedAt = 20_000L)
        monitor.markCurrentCallConfirmedSafe(callId = 1_000L)
        endActiveCall(wallAt = 5_000L, elapsedAt = 25_000L)
        assertEquals(25_000L, monitor.lastSuspiciousCallEndedElapsedMs)
        monitor.clearTelebankingAnchor()

        val markedCallId = startUnknownCall(wallAt = 1_000L, elapsedAt = 30_000L)
        assertEquals(1_000L, markedCallId)
        endActiveCall(wallAt = 6_000L, elapsedAt = 35_000L)

        assertNull(
            "다른 callId의 IDLE이 marker를 보존해 원래 callId 종료는 anchor를 스킵",
            monitor.lastSuspiciousCallEndedElapsedMs,
        )
    }

    @Test
    fun `T-A4 clear removes an armed anchor`() = runBlocking {
        seedIdle()
        startUnknownCall(wallAt = 10_000L, elapsedAt = 100_000L)
        endActiveCall(wallAt = 20_000L, elapsedAt = 105_000L)
        assertEquals(105_000L, monitor.lastSuspiciousCallEndedElapsedMs)

        monitor.clearTelebankingAnchor()

        assertNull(monitor.lastSuspiciousCallEndedElapsedMs)
    }

    @Test
    fun `T-A4 clear is a no-op when anchor is null`() {
        assertNull(monitor.lastSuspiciousCallEndedElapsedMs)

        monitor.clearTelebankingAnchor()

        assertNull(monitor.lastSuspiciousCallEndedElapsedMs)
    }

    @Test
    fun `ringing to idle sets anchor and preserves unmatched safe marker`() = runBlocking {
        seedIdle()
        monitor.markCurrentCallConfirmedSafe(callId = 1_000L)
        wallClock.now = 5_000L
        elapsedClock.now = 50_000L
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
        withTimeout(5_000) {
            while (monitor.lastSuspiciousCallEndedElapsedMs != 50_000L) delay(10)
        }
        assertEquals(
            "startedAtMillis가 null인 RINGING→IDLE도 monotonic anchor를 장전",
            50_000L,
            monitor.lastSuspiciousCallEndedElapsedMs,
        )
        monitor.clearTelebankingAnchor()

        val markedCallId = startUnknownCall(wallAt = 1_000L, elapsedAt = 60_000L)
        assertEquals(1_000L, markedCallId)
        endActiveCall(wallAt = 7_000L, elapsedAt = 65_000L)

        assertNull(
            "callId가 null인 IDLE은 기존 marker를 지우지 않음",
            monitor.lastSuspiciousCallEndedElapsedMs,
        )
    }

    private suspend fun seedIdle() {
        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!listenerSlot.isCaptured) delay(10)
        }
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmissionWhere { it.value.isEmpty() }
    }

    private suspend fun startUnknownCall(wallAt: Long, elapsedAt: Long): Long {
        wallClock.now = wallAt
        elapsedClock.now = elapsedAt
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmissionWhere { RiskSignal.UNKNOWN_CALLER in it.value }
        return requireNotNull(monitor.currentCallId())
    }

    private suspend fun endActiveCall(wallAt: Long, elapsedAt: Long) {
        wallClock.now = wallAt
        elapsedClock.now = elapsedAt
        drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
        awaitEmissionWhere { it.value.isEmpty() }
    }

    private suspend fun awaitEmissionWhere(
        predicate: (Produced<List<RiskSignal>>) -> Boolean,
    ): Produced<List<RiskSignal>> = withTimeout(5_000) {
        var emission = emissions.receive()
        while (!predicate(emission)) emission = emissions.receive()
        emission
    }

    private fun drive(state: Int, number: String? = null) {
        listenerSlot.captured.onCallStateChanged(state, number)
    }
}
