package com.example.seniorshield.core.overlay

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.telecom.TelecomManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import com.example.seniorshield.core.navigation.NavigationEventBus
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOverlayBinding
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOverlayBindingKind
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationSubject
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
abstract class OverlayPermissionSafetyContract {
    protected abstract val riskSurface: Boolean
    private lateinit var app: Application
    private lateinit var telecom: TelecomManager
    private lateinit var risk: RiskOverlayManager
    private lateinit var cooldown: BankingCooldownManager
    private lateinit var monitor: CallRiskMonitor
    private var telecomService: TelecomManager? = null
    private val attached = mutableListOf<View>()
    private val epochs = object : ResetEpochProvider {
        override var userResetEpoch: Long = 0L
    }
    private var confirmations = 0
    private var eventSequence = 0
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSettings.setCanDrawOverlays(true)
        telecom = mockk()
        telecomService = telecom
        every { telecom.isInCall } returns true
        every { telecom.showInCallScreen(false) } returns Unit
        val windows = mockk<WindowManager>(relaxed = true)
        every { windows.addView(any(), any()) } answers {
            attached += firstArg<View>()
        }
        every { windows.removeView(any()) } answers {
            assertTrue("Only an attached presentation may be removed", attached.remove(firstArg<View>()))
        }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = when (name) {
                Context.TELECOM_SERVICE -> telecomService
                Context.WINDOW_SERVICE -> windows
                else -> super.getSystemService(name)
            }
        }
        val helper = CallEndHelper(context)
        monitor = mockk(relaxed = true)
        risk = RiskOverlayManager(context, helper, monitor, NavigationEventBus(), epochs)
        cooldown = BankingCooldownManager(context, helper, epochs)
    }

    @After
    fun tearDown() {
        risk.dismiss()
        cooldown.dismissIfShowing()
        mainLooper.idle()
        assertEquals("Primary CTA must never consume safe-confirm", 0, confirmations)
        verify(exactly = 0) { monitor.currentCallId() }
        verify(exactly = 0) { monitor.clearTelebankingAnchor() }
        verify(exactly = 0) { monitor.markCurrentCallConfirmedSafe(any()) }
    }

    private fun show(): Button {
        if (riskSurface) {
            val event = RiskEvent("event-${++eventSequence}", "위험", "테스트", 0L, RiskLevel.HIGH, emptyList())
            risk.show(
                event,
                guardian = null,
                safeConfirmation = SafeConfirmationOverlayBinding(
                    SafeConfirmationSubject.Event(event.id),
                    event.id,
                    epochs.userResetEpoch,
                    event.signals.toSet(),
                    SafeConfirmationOverlayBindingKind.EVENT,
                ) {
                    confirmations++
                    true
                },
            )
        } else {
            cooldown.triggerIfNotActive(RiskLevel.HIGH, isCallActive = true, expectedResetEpoch = epochs.userResetEpoch)
        }
        mainLooper.idle()
        assertEquals("Exactly one real presentation must be built", 1, attached.size)
        return buttons(attached.single()).first()
    }

    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertDismissedImmediately(button: Button) {
        assertTrue(button.performClick())
        mainLooper.idle()
        assertTrue("Dismiss must complete without advancing the 500 ms clock", attached.isEmpty())
    }

    private fun replacePresentation(resetEpoch: Boolean): View {
        if (resetEpoch) {
            epochs.userResetEpoch++
            risk.invalidateBeforeEpoch(epochs.userResetEpoch)
            cooldown.invalidateBeforeEpoch(epochs.userResetEpoch)
            risk.dismissBeforeEpoch(epochs.userResetEpoch)
            cooldown.dismissBeforeEpoch(epochs.userResetEpoch)
        } else if (!riskSurface) {
            // Cooldown intentionally rejects a second active presentation.
            cooldown.dismissIfShowing()
        }
        mainLooper.idle()
        show()
        return attached.single()
    }

    @Test
    fun acceptedInCallInvocationKeepsOverlayUntilExactly500Milliseconds() {
        val primary = show()
        assertEquals("전화 앱으로 이동", primary.text.toString())
        assertTrue(primary.performClick())
        mainLooper.idle()
        assertEquals(1, attached.size)
        mainLooper.idleFor(Duration.ofMillis(499))
        assertEquals(1, attached.size)
        mainLooper.idleFor(Duration.ofMillis(1))
        assertTrue(attached.isEmpty())
        verify(exactly = 1) { telecom.showInCallScreen(false) }
    }

    @Test
    fun idleAtClickDismissesImmediately() {
        val primary = show()
        every { telecom.isInCall } returns false
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun deniedAtRenderAndClickDoesNotReadCallStateOrOpenPhone() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val primary = show()
        if (riskSurface) assertEquals("일단 닫기", primary.text.toString())
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.isInCall }
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun revocationDuringRenderIsSafeAndPrimaryStillDismisses() {
        every { telecom.isInCall } answers {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            throw SecurityException("read permission revoked while rendering or clicking")
        }
        val primary = show()
        if (riskSurface) assertEquals("일단 닫기", primary.text.toString())
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun permissionRevokedAfterRenderDismissesImmediately() {
        val primary = show()
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun permissionRevokedDuringClickCallStateCheckDismissesImmediately() {
        val primary = show()
        every { telecom.isInCall } throws SecurityException("revoked at click-time isInCall")
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun permissionRevokedBetweenCallCheckAndOpeningSkipsInvocation() {
        val primary = show()
        every { telecom.isInCall } answers {
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            true
        }
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun permissionRevokedAtShowInCallScreenDismissesImmediately() {
        val primary = show()
        every { telecom.showInCallScreen(false) } throws SecurityException("revoked at showInCallScreen")
        assertDismissedImmediately(primary)
        verify(exactly = 1) { telecom.showInCallScreen(false) }
    }

    @Test
    fun absentServiceAtClickDismissesImmediately() {
        val primary = show()
        telecomService = null
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun serviceDisappearingBetweenCheckAndOpeningDismissesImmediately() {
        val primary = show()
        every { telecom.isInCall } answers {
            telecomService = null
            true
        }
        assertDismissedImmediately(primary)
        verify(exactly = 0) { telecom.showInCallScreen(any()) }
    }

    @Test
    fun olderDelayedCallbackCannotDismissNewPresentationInSameEpoch() {
        val oldButton = show()
        oldButton.performClick()
        val replacement = replacePresentation(resetEpoch = false)
        mainLooper.idleFor(Duration.ofMillis(500))
        assertSame(replacement, attached.single())
    }

    @Test
    fun olderDelayedCallbackCannotDismissPresentationAfterReset() {
        val oldButton = show()
        oldButton.performClick()
        val replacement = replacePresentation(resetEpoch = true)
        mainLooper.idleFor(Duration.ofMillis(500))
        assertSame(replacement, attached.single())
    }

    @Test
    fun olderImmediateCallbackCannotDismissNewPresentationInSameEpoch() {
        val oldButton = show()
        val replacement = replacePresentation(resetEpoch = false)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        oldButton.performClick()
        mainLooper.idle()
        assertSame(replacement, attached.single())
        assertFalse(attached.isEmpty())
    }

    @Test
    fun olderImmediateCallbackCannotDismissPresentationAfterReset() {
        val oldButton = show()
        val replacement = replacePresentation(resetEpoch = true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        oldButton.performClick()
        mainLooper.idle()
        assertSame(replacement, attached.single())
    }
}

class RiskOverlayPermissionSafetyTest : OverlayPermissionSafetyContract() {
    override val riskSurface = true
}

class BankingCooldownPermissionSafetyTest : OverlayPermissionSafetyContract() {
    override val riskSurface = false
}
