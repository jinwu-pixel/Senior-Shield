package com.example.seniorshield.monitoring.session

import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * α (non-call shared-root 재발화 억제) 단위 테스트.
 *
 * 설계 문서: investigations/2026-04-21-state-sync/05_alpha_design.md + 06_alpha_patch_plan.md §5
 * 대상 동작: [RiskSessionTracker.resetAfterUserConfirmedSafe] arm + [RiskSessionTracker.update] 진입부 α block.
 *
 * 시나리오(7건):
 *  T1 — non-call subset + UPGRADE 미포함 → 억제
 *  T2 — call 경계 guard (callSignals 비어있지 않음) → α 미개입, 새 session 생성
 *  T3 — 신규 UPGRADE new-arrival → 통과 (CRITICAL 경로 보존)
 *  T4 — subset 위반 (lastResetSignals 밖 신호 포함) → 통과
 *  T5 — TTL 경과 → 통과 + 내부 arm state 자동 정리
 *  T6 — debug reset(`reset()` 호출)은 arm 안 함 → 통과
 *  T7 — session-null race에서 `resetAfterUserConfirmedSafe()` → arm 안 함 → 통과
 */
class RiskSessionTrackerAlphaTest {

    private lateinit var tracker: RiskSessionTracker
    private var fakeNow: Long = 1_000_000L

    @Before
    fun setUp() {
        tracker = RiskSessionTracker()
        fakeNow = 1_000_000L
        tracker.clock = { fakeNow }
    }

    private fun advanceBy(ms: Long) { fakeNow += ms }

    private fun openNonCallSession(vararg signals: RiskSignal) {
        val opened = tracker.update(callSignals = emptyList(), appSignals = signals.toList())
        assertNotNull("fixture: session must be opened", opened)
    }

    // ── T1 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T1 non-call subset and no new UPGRADE is suppressed`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNull("α must suppress non-call respawn", result)
        assertNull(tracker.sessionState.value)
    }

    // ── T2 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T2 call boundary guard bypasses alpha when callSignals not empty`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()

        val result = tracker.update(
            callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNotNull("call 경계 guard: α must not intervene when callSignals non-empty", result)
        assertTrue(result!!.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
        assertTrue(result.accumulatedSignals.contains(RiskSignal.REMOTE_CONTROL_APP_OPENED))
    }

    // ── T3 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T3 new arrival UPGRADE trigger escapes alpha`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            ),
        )

        assertNotNull("new-arrival UPGRADE must pass through α", result)
        assertTrue(result!!.accumulatedSignals.contains(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP))
    }

    // ── T4 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T4 subset violation passes through alpha`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.SUSPICIOUS_APP_INSTALLED,
            ),
        )

        assertNotNull("subset 위반 시 α 미적용", result)
        assertTrue(result!!.accumulatedSignals.contains(RiskSignal.SUSPICIOUS_APP_INSTALLED))
    }

    // ── T5 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T5 TTL expiration passes and clears armed state`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()
        assertNotNull("fixture: α armed", tracker.alphaArmedAt())

        advanceBy(60_000L + 1L) // ALPHA_TTL_MS + 1

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNotNull("TTL 경과 후 재생성 정상 통과", result)
        assertNull("armed state must be auto-cleared after TTL", tracker.alphaArmedAt())
        assertTrue(tracker.alphaArmedSignals().isEmpty())
    }

    // ── T6 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T6 debug reset does not arm alpha`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.reset() // debug/admin path — no arm

        assertNull("debug reset must not arm α", tracker.alphaArmedAt())

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNotNull("α not armed → normal session creation", result)
    }

    // ── T7 ─────────────────────────────────────────────────────────────────
    @Test
    fun `T7 session null race in user confirmed safe path does not arm alpha`() {
        // session을 열지 않은 상태에서 user-confirmed-safe 경로 호출 (race fallback).
        assertNull("fixture: session must be null", tracker.sessionState.value)

        tracker.resetAfterUserConfirmedSafe()

        assertNull("session-null race: α must not arm (lastResetAt)", tracker.alphaArmedAt())
        assertTrue(
            "session-null race: α must not arm (lastResetSignals empty)",
            tracker.alphaArmedSignals().isEmpty(),
        )

        val result = tracker.update(
            callSignals = emptyList(),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNotNull("α not armed → normal session creation", result)
    }

    // ── 추가 안전장치: 동일 subset 반복이 TTL 내에 한해 계속 억제됨 ─────────
    @Test
    fun `repeated subset within TTL remains suppressed`() {
        openNonCallSession(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        tracker.resetAfterUserConfirmedSafe()

        advanceBy(30_000L)
        val r1 = tracker.update(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        assertNull(r1)

        advanceBy(20_000L) // 총 50s < 60s
        val r2 = tracker.update(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        assertNull(r2)

        assertFalse("arm should still be active within TTL", tracker.alphaArmedAt() == null)
    }
}
