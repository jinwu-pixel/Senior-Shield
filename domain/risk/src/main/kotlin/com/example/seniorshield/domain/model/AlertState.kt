package com.example.seniorshield.domain.model

/**
 * 경고 행동 상태 머신.
 *
 * [RiskLevel]이 점수 기반 위험 수준이라면,
 * [AlertState]는 "사용자에게 어떤 경고를 보여줄 것인가"를 결정하는 행동 상태이다.
 *
 * ```
 * OBSERVE  → 활성 세션 없음. 노출 없음.
 * GUARDED  → 세션 존재 + PASSIVE/AMPLIFIER만. 팝업 없음, notification·하단 카드 가능.
 * INTERRUPT→ 세션 위에서 TRIGGER 1개 발생. notification + popup.
 * CRITICAL → 세션 + 고신뢰 trigger 조합. notification + popup.
 * ```
 */
enum class AlertState {
    OBSERVE,
    GUARDED,
    INTERRUPT,
    CRITICAL,
}
