package com.example.seniorshield.feature.home

import com.example.seniorshield.domain.model.RiskLevel

/**
 * GUARDED 상태일 때 HomeScreen 하단에 1회 표시되는 안내 카드 정보.
 */
data class GuardedCardInfo(
    val title: String,
    val body: String,
)

data class HomeUiState(
    val currentRiskTitle: String = "현재 보호 상태",
    val currentRiskBody: String = "안전합니다. 감지된 위험이 없습니다.",
    val currentRiskLevel: RiskLevel = RiskLevel.LOW,
    val recentEventCount: Int = 0,
    val hasCriticalPermissions: Boolean = true,
    val weeklyEventCount: Int = 0,
    val weeklyTip: String = "",
    /** GUARDED 세션 중 1회 표시되는 하단 안내 카드. null이면 미표시. */
    val guardedCard: GuardedCardInfo? = null,
    /** GUARDED+ 상태에서 보호자가 등록되어 있는지 여부. */
    val hasGuardian: Boolean = false,
    /** 보호자 이름. */
    val guardianName: String = "",
    /** 보호자 전화번호 (전화/문자 intent 구성용). */
    val guardianPhone: String = "",
)
