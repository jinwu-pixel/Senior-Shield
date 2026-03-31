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
 * - HIGH_RISK_DEVICE_ENVIRONMENT: 20점 (단독으로 MEDIUM 미충족, 다른 신호와 조합 시 기여)
 * - SUSPICIOUS_APP_INSTALLED: 40점
 * - REPEATED_UNKNOWN_CALLER: 15점
 * - REPEATED_CALL_THEN_LONG_TALK: 20점
 * - TELEBANKING_AFTER_SUSPICIOUS: 25점
 *
 * 임계값: CRITICAL ≥ 80 / HIGH ≥ 50 / MEDIUM ≥ 25 / LOW < 25
 *
 * 복합 패턴 강제 상향:
 * - (REPEATED_UNKNOWN_CALLER 또는 REPEATED_CALL_THEN_LONG_TALK) + (REMOTE_CONTROL_APP_OPENED 또는
 *   BANKING_APP_OPENED_AFTER_REMOTE_APP 또는 TELEBANKING_AFTER_SUSPICIOUS) → CRITICAL 강제
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
        val level = when {
            total >= 80 -> RiskLevel.CRITICAL
            total >= 50 -> RiskLevel.HIGH
            total >= 25 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        // 복합 패턴: 반복 호출 + 능동적 위협 → 즉시 CRITICAL
        val hasRepeatedCall = RiskSignal.REPEATED_UNKNOWN_CALLER in allSignals ||
                RiskSignal.REPEATED_CALL_THEN_LONG_TALK in allSignals
        val hasActiveThreat = RiskSignal.REMOTE_CONTROL_APP_OPENED in allSignals ||
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP in allSignals ||
                RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in allSignals
        val finalLevel = if (hasRepeatedCall && hasActiveThreat) RiskLevel.CRITICAL else level
        return RiskScore(total = total, level = finalLevel, signals = allSignals)
    }
}
