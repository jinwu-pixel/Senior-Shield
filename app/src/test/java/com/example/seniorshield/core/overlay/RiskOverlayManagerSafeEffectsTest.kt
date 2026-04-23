package com.example.seniorshield.core.overlay

import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RiskOverlayManager.shouldApplyCallSafeEffects] pure predicate 검증.
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
class RiskOverlayManagerSafeEffectsTest {

    // ── A: inCall + call-only → allow ─────────────────────────────────

    @Test
    fun `allow_whenInCallAndAllCallDerivedSignals`() {
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )
        assertTrue("inCall + call-only signals → allow (B-3 정상 경로)", result)
    }

    @Test
    fun `allow_whenInCallAndRepeatedCallSignals`() {
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
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
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertFalse("inCall + app-derived only → deny (이번 실기 B 시나리오)", result)
    }

    @Test
    fun `deny_whenInCallAndBankingAppSignalPresent`() {
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = true,
            signals = setOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP),
        )
        assertFalse("inCall + 뱅킹 앱 signal → deny", result)
    }

    // ── C: not-inCall → deny ──────────────────────────────────────────

    @Test
    fun `deny_whenNotInCall_regardlessOfSignals`() {
        val callOnly = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = false,
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )
        val appOnly = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = false,
            signals = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertFalse("not-inCall + call-only도 deny (inCall이 필요조건)", callOnly)
        assertFalse("not-inCall + app-only도 deny", appOnly)
    }

    // ── D: mixed allowlist 보수성 ─────────────────────────────────────

    @Test
    fun `deny_whenInCallAndMixedCallAndAppSignals`() {
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
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
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
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
        val result = RiskOverlayManager.shouldApplyCallSafeEffects(
            inCall = true,
            signals = emptySet(),
        )
        assertFalse("empty signals → deny (방어 가드)", result)
    }

    // ── performSafeCtaSideEffects 행동 검증 ───────────────────────────
    //
    // 클릭 경로의 부수효과 조합을 순서까지 검증한다.
    // snooze 축과 anchor suppression 축이 독립적으로 적용됨을 확인.

    /** A. inCall + app-derived (callSafe=false) → snooze만 적용, anchor 억제 스킵. */
    @Test
    fun `sideEffects_inCallAppDerived_snoozeOnly`() {
        val calls = mutableListOf<String>()
        RiskOverlayManager.performSafeCtaSideEffects(
            liveCallId = 111L,
            callSafe = false,
            reset = { calls.add("reset") },
            clearEvent = { calls.add("clearEvent") },
            snooze = { calls.add("snooze:$it") },
            clearAnchor = { calls.add("clearAnchor") },
            markSafe = { calls.add("markSafe:$it") },
        )
        // 순서 + 구성: reset → clearEvent → snooze만. anchor/markSafe 미호출 (회귀 방지 핵심).
        assertEquals(
            listOf("reset", "clearEvent", "snooze:111"),
            calls,
        )
    }

    /** B. inCall + call-derived (callSafe=true) → 전체 부수효과 적용. */
    @Test
    fun `sideEffects_inCallCallDerived_allApplied`() {
        val calls = mutableListOf<String>()
        RiskOverlayManager.performSafeCtaSideEffects(
            liveCallId = 222L,
            callSafe = true,
            reset = { calls.add("reset") },
            clearEvent = { calls.add("clearEvent") },
            snooze = { calls.add("snooze:$it") },
            clearAnchor = { calls.add("clearAnchor") },
            markSafe = { calls.add("markSafe:$it") },
        )
        // reset → clearEvent → snooze(respawn 억제) → clearAnchor + markSafe (anchor 억제 묶음)
        assertEquals(
            listOf("reset", "clearEvent", "snooze:222", "clearAnchor", "markSafe:222"),
            calls,
        )
    }

    /** C. not-inCall (liveCallId=null) → reset + clearEvent만. snooze/anchor 전부 스킵. */
    @Test
    fun `sideEffects_notInCall_resetAndClearEventOnly`() {
        val calls = mutableListOf<String>()
        RiskOverlayManager.performSafeCtaSideEffects(
            liveCallId = null,
            callSafe = false,
            reset = { calls.add("reset") },
            clearEvent = { calls.add("clearEvent") },
            snooze = { calls.add("snooze:$it") },
            clearAnchor = { calls.add("clearAnchor") },
            markSafe = { calls.add("markSafe:$it") },
        )
        assertEquals(listOf("reset", "clearEvent"), calls)
    }
}
