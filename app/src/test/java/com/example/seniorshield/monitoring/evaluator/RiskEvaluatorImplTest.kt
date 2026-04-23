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
    fun `TELEBANKING_AFTER_SUSPICIOUS 단독 - 25점 CRITICAL (강제 상향)`() {
        // 이 신호는 5분 anchor 윈도우 내에서만 방출되므로 "의심 맥락"이 선행 조건.
        // 점수는 25점(MEDIUM 임계)이지만 finalLevel은 CRITICAL로 강제 상향된다.
        val result = evaluator.evaluate(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
        assertEquals(25, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
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

    // ── 커버리지 보완 ─────────────────────────────────────────────────

    @Test
    fun `UNVERIFIED_CALLER 단독 - 20점 LOW`() {
        val result = evaluator.evaluate(listOf(RiskSignal.UNVERIFIED_CALLER))
        assertEquals(20, result.total)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `HIGH_RISK_DEVICE_ENVIRONMENT 단독 - 20점 LOW`() {
        val result = evaluator.evaluate(listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT))
        assertEquals(20, result.total)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `SUSPICIOUS_APP_INSTALLED 단독 - 40점 MEDIUM`() {
        val result = evaluator.evaluate(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
        assertEquals(40, result.total)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `점수 경계값 - BANKING + TELEBANKING 조합 - TELEBANKING 강제 CRITICAL`() {
        // BANKING_APP_OPENED_AFTER_REMOTE_APP(40) + TELEBANKING_AFTER_SUSPICIOUS(25) = 65점
        // call 신호 없음, REMOTE+BANKING 콤보 없음이지만
        // TELEBANKING_AFTER_SUSPICIOUS 자체가 anchor 기반 강제 CRITICAL 신호 → CRITICAL
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
            ),
        )
        assertEquals(65, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `forced CRITICAL 한정 — TELEBANKING_AFTER_SUSPICIOUS 이외의 TRIGGER 단독은 기존 level 유지`() {
        // 이번 변경으로 추가된 "signal 단독 forced CRITICAL"은 오직 TELEBANKING_AFTER_SUSPICIOUS에만 적용됨.
        // 다른 TRIGGER 신호들이 단독으로 발생하면 점수 기반 level을 그대로 유지해야 한다
        // (과도 확산 방지 — 일반 원격/뱅킹/앱 설치 신호까지 CRITICAL로 올려서는 안 됨).
        val remote = evaluator.evaluate(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        assertEquals(30, remote.total)
        assertEquals(RiskLevel.MEDIUM, remote.level)

        val banking = evaluator.evaluate(listOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP))
        assertEquals(40, banking.total)
        assertEquals(RiskLevel.MEDIUM, banking.level)

        val install = evaluator.evaluate(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
        assertEquals(40, install.total)
        assertEquals(RiskLevel.MEDIUM, install.level)

        // 대조군: TELEBANKING_AFTER_SUSPICIOUS만이 forced CRITICAL
        val telebanking = evaluator.evaluate(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
        assertEquals(25, telebanking.total)
        assertEquals(RiskLevel.CRITICAL, telebanking.level)
    }

    @Test
    fun `점수 경계값 - 80점은 CRITICAL (순수 점수, 강제 CRITICAL 조합 없음)`() {
        // SUSPICIOUS_APP_INSTALLED(40) + BANKING_APP_OPENED_AFTER_REMOTE_APP(40) = 80점
        // call 신호 없음, REMOTE_CONTROL_APP_OPENED 없으므로 REMOTE+BANKING 콤보 미적용
        // 순수 점수만으로 CRITICAL(≥80) 도달
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.SUSPICIOUS_APP_INSTALLED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            ),
        )
        assertEquals(80, result.total)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `PASSIVE 신호만 누적해도 CRITICAL 불가 - 강제 상향 규칙 미적용 명시적 검증`() {
        // REPEATED_UNKNOWN_CALLER(15) + UNKNOWN_CALLER(20) + LONG_CALL_DURATION(30) = 65점 → HIGH
        // call 신호 존재 + TRIGGER 없음 → 복합 패턴 강제 CRITICAL 규칙 미적용
        // PASSIVE/AMPLIFIER 누적만으로는 강제 CRITICAL 상향이 발동하지 않음을 검증한다.
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.REPEATED_UNKNOWN_CALLER,
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
            ),
        )
        assertEquals(65, result.total)
        // call 신호는 있지만 TRIGGER 없으므로 강제 CRITICAL 상향 미발동 → score 기반 HIGH
        assertEquals(RiskLevel.HIGH, result.level)
        // TRIGGER 신호가 없음을 검증
        val triggerSignals = result.signals.filter {
            it == RiskSignal.REMOTE_CONTROL_APP_OPENED ||
                it == RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP ||
                it == RiskSignal.TELEBANKING_AFTER_SUSPICIOUS ||
                it == RiskSignal.SUSPICIOUS_APP_INSTALLED
        }
        assertTrue(triggerSignals.isEmpty())
    }

    @Test
    fun `중복 신호 입력 시 result signals 개수 검증`() {
        // UNKNOWN_CALLER 를 callSignals 에 중복으로 입력해도 distinct 처리되어 1개만 존재
        val result = evaluator.evaluate(
            listOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.LONG_CALL_DURATION,
            ),
        )
        // distinct 후 신호 2개만 존재
        assertEquals(2, result.signals.size)
        // 점수도 중복 계산 없이 50점
        assertEquals(50, result.total)
    }
}
