package com.example.seniorshield.core.overlay

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.event.RiskEventFactory
import com.example.seniorshield.monitoring.orchestrator.DefaultRiskDetectionCoordinator
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOverlayBinding
import com.example.seniorshield.monitoring.orchestrator.shouldApplyOverlayCallSafeEffects
import com.example.seniorshield.testutil.CoordinatorTestHarness
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinator-owned positive allowlist와 safe-confirm 실행 순서 검증.
 *
 * 의미 계약:
 *   "현재 overlay를 닫는 행위가 해당 통화를 안전 확인하는 뜻으로 해석 가능한 경우에만" true.
 *
 * 정책: positive allowlist — `inCall == true` && 모든 signal이 `AlertStateResolver.CALL_SIGNALS`에 속할 때만 true.
 * app-derived (REMOTE_CONTROL 등) / TELEBANKING 포함 / mixed / empty / not-inCall은 모두 deny.
 *
 * 실제 click handler의 view/WindowManager 경로는 실기 Scenario A~D (02_patch_plan.md §6-2)로 커버.
 * 여기서는 predicate 로직만 분리해 단위 검증.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RiskOverlayManagerSafeEffectsTest {

    // ── A: inCall + call-only → allow ─────────────────────────────────

    @Test
    fun `allow_whenInCallAndAllCallDerivedSignals`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )
        assertTrue("inCall + call-only signals → allow (B-3 정상 경로)", result)
    }

    @Test
    fun `allow_whenInCallAndRepeatedCallSignals`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.REPEATED_UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
            ),
        )
        assertTrue("복수 call-derived signal만 있는 경우도 allow", result)
    }

    // ── B: inCall + app-derived → deny (이번 실기 시나리오) ───────────

    @Test
    fun `deny_whenInCallAndAppDerivedSignalPresent`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertFalse("inCall + app-derived only → deny (이번 실기 B 시나리오)", result)
    }

    @Test
    fun `deny_whenInCallAndBankingAppSignalPresent`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP),
        )
        assertFalse("inCall + 뱅킹 앱 signal → deny", result)
    }

    // ── C: not-inCall → deny ──────────────────────────────────────────

    @Test
    fun `deny_whenNotInCall_regardlessOfSignals`() {
        val callOnly = shouldApplyOverlayCallSafeEffects(
            inCall = false,
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )
        val appOnly = shouldApplyOverlayCallSafeEffects(
            inCall = false,
            signals = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertFalse("not-inCall + call-only도 deny (inCall이 필요조건)", callOnly)
        assertFalse("not-inCall + app-only도 deny", appOnly)
    }

    // ── D: mixed allowlist 보수성 ─────────────────────────────────────

    @Test
    fun `deny_whenInCallAndMixedCallAndAppSignals`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(
                RiskSignal.UNKNOWN_CALLER,            // call-derived
                RiskSignal.LONG_CALL_DURATION,        // call-derived
                RiskSignal.REMOTE_CONTROL_APP_OPENED, // app-derived — allowlist 위반
            ),
        )
        assertFalse("mixed signals → deny (allowlist 보수성)", result)
    }

    // ── TELEBANKING_AFTER_SUSPICIOUS: CALL_SIGNALS 밖 → deny ──────────

    @Test
    fun `deny_whenTelebankingSignalPresent`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = setOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
            ),
        )
        assertFalse("TELEBANKING 포함 → deny (텔레뱅킹이 해당 통화 — 안전 확인 의미 모순)", result)
    }

    // ── 방어 가드: empty signals ──────────────────────────────────────

    @Test
    fun `deny_whenEmptySignals`() {
        val result = shouldApplyOverlayCallSafeEffects(
            inCall = true,
            signals = emptySet(),
        )
        assertFalse("empty signals → deny (방어 가드)", result)
    }

    // ── Coordinator-owned safe-confirm 행동 검증 ─────────────────────
    //
    // 클릭 경로의 부수효과 조합을 순서까지 검증한다.
    // snooze 축과 anchor suppression 축이 독립적으로 적용됨을 확인.

    /** A. inCall + app-derived (callSafe=false) → snooze만 적용, anchor 억제 스킵. */
    @Test
    fun `sideEffects_inCallAppDerived_snoozeOnly`() = runTest {
        val fixture = startFixture(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), 111L)
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(111L)
            assertTrue(fixture.binding.confirm(111L, fixture.event.signals.toSet()))

            // Before: reset → event → snooze. After: reset → snooze → mirror → event → cleanup.
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(111L))
            assertEquals(
                listOf("mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
            assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
        } finally {
            fixture.coordinator.stop()
        }
    }

    /** B. inCall + call-derived (callSafe=true) → 전체 부수효과 적용. */
    @Test
    fun `sideEffects_inCallCallDerived_allApplied`() = runTest {
        val fixture = startFixture(
            setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
            222L,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(222L, 222L)
            assertTrue(fixture.binding.confirm(222L, fixture.event.signals.toSet()))

            // Before: reset → event → snooze → clear → mark.
            // After: reset → snooze → recheck → mark → clear → mirror → event → cleanup.
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(222L))
            assertEquals(
                listOf("mark:222", "source", "mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
        } finally {
            fixture.coordinator.stop()
        }
    }

    /** C. not-inCall (liveCallId=null) → reset + clearEvent만. snooze/anchor 전부 스킵. */
    @Test
    fun `sideEffects_notInCall_resetAndClearEventOnly`() = runTest {
        val fixture = startFixture(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), null)
        try {
            assertTrue(fixture.binding.confirm(null, fixture.event.signals.toSet()))

            // Before: reset → event. After: reset → mirror → event → cleanup.
            assertEquals(
                listOf("mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
            assertFalse(fixture.harness.sessionTracker.isSnoozeActive())
        } finally {
            fixture.coordinator.stop()
        }
    }

    private fun TestScope.startFixture(signals: Set<RiskSignal>, callId: Long?): Fixture {
        val harness = CoordinatorTestHarness()
        val event = RiskEvent(
            id = "overlay-contract-${signals.hashCode()}-${callId ?: "idle"}",
            title = "test",
            description = "test",
            occurredAtMillis = 1L,
            level = RiskLevel.CRITICAL,
            signals = signals.toList(),
        )
        val eventFactory = mockk<RiskEventFactory>().also { factory ->
            every { factory.create(any(), any()) } returns event
        }
        every { harness.overlayManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "overlay"
        }
        every { harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "cooldown"
        }
        harness.callMonitor.anchorHot = true
        harness.callMonitor.callId = callId
        harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val coordinator = with(harness) { start(eventFactoryOverride = eventFactory) }
        val captured = mutableListOf<SafeConfirmationOverlayBinding>()
        verify(atLeast = 1) { harness.overlayManager.show(any(), any(), capture(captured)) }
        harness.safeConfirmationOperations.clear()
        harness.callMonitor.logAnchorReads = true
        return Fixture(harness, coordinator, event, captured.last())
    }

    private data class Fixture(
        val harness: CoordinatorTestHarness,
        val coordinator: DefaultRiskDetectionCoordinator,
        val event: RiskEvent,
        val binding: SafeConfirmationOverlayBinding,
    )
}
