package com.example.seniorshield.monitoring.evaluator

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEvaluatorImplTest {

    private val evaluator = RiskEvaluatorImpl()

    // 신호별 점수: UNKNOWN_CALLER=20, LONG_CALL_DURATION=30, UNVERIFIED_CALLER=20,
    //             REMOTE_CONTROL_APP_OPENED=30, BANKING_APP_OPENED_AFTER_REMOTE_APP=40,
    //             HIGH_RISK_DEVICE_ENVIRONMENT=20, SUSPICIOUS_APP_INSTALLED=40
    // 임계값: CRITICAL≥80 / HIGH≥50 / MEDIUM≥25 / LOW<25

    @Test
    fun `신호 없음 - 점수 0 LOW`() {
        val result = evaluator.evaluate(emptyList())
        assertEquals(0, result.total)
        assertEquals(RiskLevel.LOW, result.level)
        assertTrue(result.signals.isEmpty())
    }

    @Test
    fun `UNKNOWN_CALLER 단독 - 20점 LOW`() {
        val result = evaluator.evaluate(listOf(RiskSignal.UNKNOWN_CALLER))
        assertEquals(20, result.total)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `LONG_CALL_DURATION 단독 - 30점 MEDIUM`() {
        val result = evaluator.evaluate(listOf(RiskSignal.LONG_CALL_DURATION))
        assertEquals(30, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `UNKNOWN_CALLER + LONG_CALL_DURATION - 50점 HIGH`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )
        assertEquals(50, result.total)
        assertEquals(RiskLevel.HIGH, result.level)
    }

    @Test
    fun `REMOTE_CONTROL_APP_OPENED + UNKNOWN_CALLER + LONG_CALL_DURATION - 80점 CRITICAL`() {
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
            ),
        )
        assertEquals(80, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `BANKING_APP_OPENED_AFTER_REMOTE_APP 단독 - 40점 MEDIUM`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP),
        )
        assertEquals(40, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `중복 신호 - 한 번만 계산`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.UNKNOWN_CALLER),
        )
        assertEquals(20, result.total)
    }

    @Test
    fun `전체 신호 - 점수 합산 정확`() {
        // 20 + 30 + 20 + 30 + 40 + 20 + 40 = 200
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.UNVERIFIED_CALLER,
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT,
                RiskSignal.SUSPICIOUS_APP_INSTALLED,
            ),
        )
        assertEquals(200, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
        assertEquals(7, result.signals.size)
    }

    @Test
    fun `CRITICAL 경계값 정확 - 70점은 HIGH`() {
        // UNKNOWN_CALLER(20) + LONG_CALL_DURATION(30) + UNVERIFIED_CALLER(20) = 70 → HIGH
        val result = evaluator.evaluate(
            listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION, RiskSignal.UNVERIFIED_CALLER),
        )
        assertEquals(70, result.total)
        assertEquals(RiskLevel.HIGH, result.level)
    }

    @Test
    fun `MEDIUM 경계값 - 30점`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.LONG_CALL_DURATION),
        )
        assertEquals(30, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    // ── P0.5 신호 점수 검증 ──────────────────────────────────────────

    @Test
    fun `REPEATED_UNKNOWN_CALLER 단독 - 15점 LOW`() {
        val result = evaluator.evaluate(listOf(RiskSignal.REPEATED_UNKNOWN_CALLER))
        assertEquals(15, result.total)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `REPEATED_CALL_THEN_LONG_TALK 단독 - 20점 LOW`() {
        val result = evaluator.evaluate(listOf(RiskSignal.REPEATED_CALL_THEN_LONG_TALK))
        assertEquals(20, result.total)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `TELEBANKING_AFTER_SUSPICIOUS 단독 - 25점 MEDIUM`() {
        val result = evaluator.evaluate(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
        assertEquals(25, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    // ── 복합 패턴 CRITICAL 강제 상향 ─────────────────────────────────

    @Test
    fun `반복호출 + 원격제어 - 점수 미달이어도 CRITICAL`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.REPEATED_UNKNOWN_CALLER, RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertEquals(45, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `반복호출 + 텔레뱅킹 - CRITICAL`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.REPEATED_CALL_THEN_LONG_TALK, RiskSignal.TELEBANKING_AFTER_SUSPICIOUS),
        )
        assertEquals(45, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `반복호출 + 뱅킹앱 - CRITICAL`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.REPEATED_UNKNOWN_CALLER, RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP),
        )
        assertEquals(55, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `반복호출만 단독 - CRITICAL 아님`() {
        val result = evaluator.evaluate(
            listOf(RiskSignal.REPEATED_UNKNOWN_CALLER, RiskSignal.REPEATED_CALL_THEN_LONG_TALK),
        )
        assertEquals(35, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `텔레뱅킹 유도 사기 전체 시나리오 - CRITICAL`() {
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.REPEATED_UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
                RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
            ),
        )
        assertEquals(110, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }
}
