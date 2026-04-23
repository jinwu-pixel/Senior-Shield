package com.example.seniorshield.feature.home

import com.example.seniorshield.domain.model.RiskLevel

/**
 * Home 카드가 표현하는 상태 축. 도메인의 [com.example.seniorshield.domain.model.AlertState]와
 * 별도로 유지한다 — 도메인 axis는 세션/트리거 기준이고, HomeStatus는 "사용자에게 어떻게 보일지" 기준이다.
 *
 * - [SAFE]            : currentRiskEvent == null && !anchorHot
 * - [GUARDED_ANCHOR]  : currentRiskEvent == null && anchorHot  (위험 통화 직후 5분 TTL 내)
 * - [WARNING]         : currentRiskEvent != null (레벨 세부 표현은 currentRiskLevel로)
 */
enum class HomeStatus { SAFE, GUARDED_ANCHOR, WARNING }

/**
 * HomeStatus와 Home 카드에 렌더할 기본 title/body/level을 묶어 반환한다.
 * 순수 함수 — ViewModel이 combine 결과를 이 함수에 넘겨 UI 문구를 결정하고
 * 단위테스트에서도 동일 결정 경로를 직접 검증할 수 있다.
 *
 * @param currentEventTitle currentRiskEvent?.title — null이면 WARNING 분기 없음
 * @param currentEventLevel currentRiskEvent?.level — WARNING에서 StatusCard 색상 결정
 * @param anchorHot CallRiskMonitor 기반 5분 TTL 플래그 (coordinator mirror)
 */
data class HomePresentation(
    val status: HomeStatus,
    val title: String,
    val baseBody: String,
    val level: RiskLevel,
)

fun decideHomePresentation(
    currentEventTitle: String?,
    currentEventLevel: RiskLevel?,
    anchorHot: Boolean,
): HomePresentation = when {
    currentEventTitle != null && currentEventLevel != null ->
        HomePresentation(HomeStatus.WARNING, "위험 감지", currentEventTitle, currentEventLevel)
    anchorHot ->
        HomePresentation(
            HomeStatus.GUARDED_ANCHOR,
            "주의 — 확인 필요",
            "최근 의심 통화 맥락이 남아 있습니다",
            // StatusCard 노란색 축. 팝업 CRITICAL 축과 혼동되지 않도록 MEDIUM으로 고정.
            RiskLevel.MEDIUM,
        )
    else ->
        HomePresentation(HomeStatus.SAFE, "현재 보호 상태", "안전합니다. 감지된 위험이 없습니다.", RiskLevel.LOW)
}

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
    val homeStatus: HomeStatus = HomeStatus.SAFE,
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
