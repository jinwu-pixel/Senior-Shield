package com.example.seniorshield.monitoring.evaluator

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 신호별 점수 가중치 기반 위험 평가기.
 *
 * 신호 점수:
 * - UNKNOWN_CALLER: 20점
 * - LONG_CALL_DURATION: 30점 (단독으로 MEDIUM 충족)
 * - UNVERIFIED_CALLER: 20점
 * - REMOTE_CONTROL_APP_OPENED: 30점
 * - BANKING_APP_OPENED_AFTER_REMOTE_APP: 40점
 * - HIGH_RISK_DEVICE_ENVIRONMENT: 20점 (modifier — 단독 세션 생성 불가)
 * - SUSPICIOUS_APP_INSTALLED: 40점
 * - REPEATED_UNKNOWN_CALLER: 15점
 * - REPEATED_CALL_THEN_LONG_TALK: 20점
 * - TELEBANKING_AFTER_SUSPICIOUS: 25점
 *
 * 임계값: CRITICAL ≥ 80 / HIGH ≥ 50 / MEDIUM ≥ 25 / LOW < 25
 *
 * 복합 패턴 강제 CRITICAL 상향 (TRIGGER 포함 필수):
 * - call 신호 + TRIGGER(원격제어/텔레뱅킹/금융 앱) → CRITICAL
 * - REMOTE_CONTROL_APP_OPENED + BANKING_APP_OPENED_AFTER_REMOTE_APP → CRITICAL
 *
 * PASSIVE/AMPLIFIER 누적만으로는 CRITICAL 불가.
 */
@Singleton
class RiskEvaluatorImpl @Inject constructor() : RiskEvaluator {

    override fun evaluate(signals: List<RiskSignal>): RiskScore {
        val allSignals = signals.distinct()
        val total = allSignals.sumOf { signal ->
            when (signal) {
                RiskSignal.UNKNOWN_CALLER -> 20L
                RiskSignal.LONG_CALL_DURATION -> 30L
                RiskSignal.UNVERIFIED_CALLER -> 20L
                RiskSignal.REMOTE_CONTROL_APP_OPENED -> 30L
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP -> 40L
                RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT -> 20L
                RiskSignal.SUSPICIOUS_APP_INSTALLED -> 40L
                RiskSignal.REPEATED_UNKNOWN_CALLER -> 15L
                RiskSignal.REPEATED_CALL_THEN_LONG_TALK -> 20L
                RiskSignal.TELEBANKING_AFTER_SUSPICIOUS -> 25L
            }
        }.toInt()
        val scoreLevel = when {
            total >= 80 -> RiskLevel.CRITICAL
            total >= 50 -> RiskLevel.HIGH
            total >= 25 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // 복합 패턴 강제 CRITICAL: call 신호 + TRIGGER 조합
        val hasCallSignal = allSignals.any { it in CALL_PASSIVE_SIGNALS }
        val hasTrigger = allSignals.any { it in TRIGGER_SIGNALS }
        val hasRemoteAndBanking = RiskSignal.REMOTE_CONTROL_APP_OPENED in allSignals &&
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP in allSignals

        val finalLevel = when {
            hasCallSignal && hasTrigger -> RiskLevel.CRITICAL
            hasRemoteAndBanking -> RiskLevel.CRITICAL
            else -> scoreLevel
        }
        return RiskScore(total = total, level = finalLevel, signals = allSignals)
    }

    companion object {
        private val CALL_PASSIVE_SIGNALS = setOf(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.UNVERIFIED_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
        )
        private val TRIGGER_SIGNALS = setOf(
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
        )
    }
}
