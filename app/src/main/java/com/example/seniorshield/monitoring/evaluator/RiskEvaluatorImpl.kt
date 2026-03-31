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
 *
 * 임계값: CRITICAL ≥ 80 / HIGH ≥ 50 / MEDIUM ≥ 25 / LOW < 25
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
            }
        }.toInt()
        val level = when {
            total >= 80 -> RiskLevel.CRITICAL
            total >= 50 -> RiskLevel.HIGH
            total >= 25 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return RiskScore(total = total, level = level, signals = allSignals)
    }
}
