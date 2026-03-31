package com.example.seniorshield.feature.home

import com.example.seniorshield.domain.model.RiskLevel

data class HomeUiState(
    val currentRiskTitle: String = "현재 보호 상태",
    val currentRiskBody: String = "안전합니다. 감지된 위험이 없습니다.",
    val currentRiskLevel: RiskLevel = RiskLevel.LOW,
    val recentEventCount: Int = 0,
    val hasCriticalPermissions: Boolean = true,
    val weeklyEventCount: Int = 0,
    val weeklyTip: String = "",
)
