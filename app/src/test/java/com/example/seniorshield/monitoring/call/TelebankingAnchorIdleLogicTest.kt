package com.example.seniorshield.monitoring.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RealCallRiskMonitor의 IDLE 전이 시 텔레뱅킹 anchor 분기 로직을 검증한다.
 *
 * RealCallRiskMonitor는 Android 의존성(Context, TelephonyManager)이 있어
 * 직접 인스턴스화 불가하므로, IDLE 전이 시 anchor 처리 핵심 로직만
 * 동일한 분기 구조로 재현하여 검증한다.
 *
 * 검증 대상 로직 (RealCallRiskMonitor.observeCallSignals IDLE 분기):
 * ```
 * val callIdAtIdle = ctx.startedAtMillis
 * val confirmedSafe = (safeConfirmedCallId != null && callIdAtIdle != null
 *                      && safeConfirmedCallId == callIdAtIdle)
 * if (isSuspicious && !confirmedSafe) lastSuspiciousCallEndedAt = ctx.endedAtMillis
 * if (callIdAtIdle != null && safeConfirmedCallId == callIdAtIdle) safeConfirmedCallId = null
 * ```
 */
class TelebankingAnchorIdleLogicTest {

    /** 테스트용 reproduction 컨테이너 — 프로덕션 @Volatile 상태와 IDLE 분기를 동일하게 모사. */
    private class IdleAnchorReproducer {
        var lastSuspiciousCallEndedAt: Long? = null
        var safeConfirmedCallId: Long? = null

        fun markCurrentCallConfirmedSafe(callId: Long) {
            safeConfirmedCallId = callId
        }

        fun clearTelebankingAnchor() {
            lastSuspiciousCallEndedAt = null
        }

        /** IDLE 전이 시 RealCallRiskMonitor의 분기와 동일하게 처리. */
        fun handleIdle(callIdAtIdle: Long?, endedAtMillis: Long, isSuspicious: Boolean) {
            val confirmedSafe =
                safeConfirmedCallId != null && callIdAtIdle != null && safeConfirmedCallId == callIdAtIdle
            if (isSuspicious && !confirmedSafe) {
                lastSuspiciousCallEndedAt = endedAtMillis
            }
            if (callIdAtIdle != null && safeConfirmedCallId == callIdAtIdle) {
                safeConfirmedCallId = null
            }
        }
    }

    // ── T-A1: markCurrentCallConfirmedSafe 후 동일 callId IDLE — anchor 미설정 ──

    @Test
    fun `T-A1 markCurrentCallConfirmedSafe 후 동일 callId IDLE — anchor 미설정`() {
        val r = IdleAnchorReproducer()
        val callId = 1_000L

        // 사용자 안전 확인
        r.markCurrentCallConfirmedSafe(callId)

        // 동일 callId의 의심 통화 IDLE 전이
        r.handleIdle(callIdAtIdle = callId, endedAtMillis = 5_000L, isSuspicious = true)

        // anchor가 설정되지 않아야 함
        assertNull(
            "사용자 안전 확인된 통화의 IDLE에서는 anchor가 설정되지 않아야 함",
            r.lastSuspiciousCallEndedAt,
        )
    }

    // ── T-A2: markCurrentCallConfirmedSafe 후 다른 callId IDLE — anchor 정상 설정 ──

    @Test
    fun `T-A2 markCurrentCallConfirmedSafe 후 다른 callId IDLE — anchor 정상 설정`() {
        val r = IdleAnchorReproducer()
        val callIdA = 1_000L
        val callIdB = 2_000L

        // callId A에 mark
        r.markCurrentCallConfirmedSafe(callIdA)

        // 다른 callId B의 의심 통화 IDLE
        r.handleIdle(callIdAtIdle = callIdB, endedAtMillis = 5_000L, isSuspicious = true)

        // 다른 callId이므로 anchor가 정상 설정되어야 함
        assertEquals(
            "다른 callId의 IDLE은 safeConfirmedCallId 영향 없음 — anchor 정상 설정",
            5_000L,
            r.lastSuspiciousCallEndedAt,
        )
    }

    // ── T-A3: IDLE 처리 후 safeConfirmedCallId 자동 클리어 ──

    @Test
    fun `T-A3 IDLE 처리 후 safeConfirmedCallId 자동 클리어`() {
        val r = IdleAnchorReproducer()
        val callId = 1_000L

        r.markCurrentCallConfirmedSafe(callId)
        assertEquals(callId, r.safeConfirmedCallId)

        r.handleIdle(callIdAtIdle = callId, endedAtMillis = 5_000L, isSuspicious = true)

        // 동일 callId 처리 직후 자동 클리어되어 다음 통화에 영향 없도록
        assertNull(
            "IDLE 처리 후 safeConfirmedCallId는 자동으로 null로 복귀해야 함",
            r.safeConfirmedCallId,
        )
    }

    // ── T-A3 보강: 다른 callId의 IDLE은 safeConfirmedCallId를 클리어하지 않음 ──

    @Test
    fun `T-A3 보강 다른 callId IDLE은 safeConfirmedCallId 보존`() {
        val r = IdleAnchorReproducer()
        val callIdA = 1_000L
        val callIdB = 2_000L

        r.markCurrentCallConfirmedSafe(callIdA)
        // 먼저 다른 callId B의 IDLE이 발생 (예: 동시 진행 통화의 마무리)
        r.handleIdle(callIdAtIdle = callIdB, endedAtMillis = 5_000L, isSuspicious = false)

        // safeConfirmedCallId는 callId A에 대해 여전히 유효해야 함
        assertEquals(
            "다른 callId의 IDLE은 safeConfirmedCallId를 건드리지 않음",
            callIdA,
            r.safeConfirmedCallId,
        )
    }

    // ── T-A4: clearTelebankingAnchor — 설정된 anchor를 null로 리셋 ──

    @Test
    fun `T-A4 clearTelebankingAnchor — 설정된 anchor를 null로 리셋`() {
        val r = IdleAnchorReproducer()
        // anchor 사전 설정
        r.handleIdle(callIdAtIdle = 1_000L, endedAtMillis = 5_000L, isSuspicious = true)
        assertEquals(5_000L, r.lastSuspiciousCallEndedAt)

        // 사용자 안전 종료 → anchor 무력화
        r.clearTelebankingAnchor()

        assertNull(
            "clearTelebankingAnchor 호출 후 anchor는 null이어야 함",
            r.lastSuspiciousCallEndedAt,
        )
    }

    // ── T-A4 보강: anchor가 이미 null인 상태에서 clearTelebankingAnchor 호출은 no-op ──

    @Test
    fun `T-A4 보강 anchor null 상태에서 clearTelebankingAnchor 호출은 no-op`() {
        val r = IdleAnchorReproducer()
        assertNull(r.lastSuspiciousCallEndedAt)

        r.clearTelebankingAnchor()

        assertNull(r.lastSuspiciousCallEndedAt)
    }

    // ── 추가 검증: callIdAtIdle == null (RINGING→IDLE) 케이스 ──

    @Test
    fun `RINGING IDLE callIdAtIdle null — safeConfirmedCallId 매칭 안 됨, anchor 정상 설정`() {
        val r = IdleAnchorReproducer()
        r.markCurrentCallConfirmedSafe(1_000L)

        // RINGING→IDLE: ctx.startedAtMillis == null
        r.handleIdle(callIdAtIdle = null, endedAtMillis = 5_000L, isSuspicious = true)

        // safeConfirmedCallId가 있어도 callIdAtIdle null이면 매칭 불가 → anchor 설정
        assertEquals(5_000L, r.lastSuspiciousCallEndedAt)
        // safeConfirmedCallId는 보존 (callIdAtIdle null이라 클리어 조건 미충족)
        assertEquals(1_000L, r.safeConfirmedCallId)
    }
}
