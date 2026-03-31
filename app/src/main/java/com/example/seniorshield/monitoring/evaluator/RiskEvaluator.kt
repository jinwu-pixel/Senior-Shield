package com.example.seniorshield.monitoring.evaluator

import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal

interface RiskEvaluator {
    fun evaluate(signals: List<RiskSignal>): RiskScore
}