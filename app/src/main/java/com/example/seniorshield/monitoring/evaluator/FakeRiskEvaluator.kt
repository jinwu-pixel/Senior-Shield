package com.example.seniorshield.monitoring.evaluator

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeRiskEvaluator @Inject constructor() : RiskEvaluator {
    override fun evaluate(signals: List<RiskSignal>): RiskScore =
        RiskScore(total = 0, level = RiskLevel.LOW, signals = emptyList())
}
