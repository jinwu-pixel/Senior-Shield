package com.example.seniorshield.domain.model

data class RiskEvent(
    val id: String,
    val title: String,
    val description: String,
    val occurredAtMillis: Long,
    val level: RiskLevel,
    val signals: List<RiskSignal>,
)