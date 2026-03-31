package com.example.seniorshield.domain.model

data class RiskScore(
    val total: Int,
    val level: RiskLevel,
    val signals: List<RiskSignal>,
)