package com.example.seniorshield.domain.model

/**
 * 위험 신호의 행동 분류.
 *
 * 핵심 원칙: "수동 신호는 위험 세션만 만든다.
 * 팝업은 위험 세션 위에서 발생한 고신뢰 행동 트리거에만 반응한다."
 */
enum class SignalCategory {
    /** 세션 생성·점수 누적만. 단독 팝업 불가. */
    PASSIVE,
    /** 수동 신호 복합 조합. 점수 증폭, 단독 팝업 불가. */
    AMPLIFIER,
    /** 팝업 발동 가능한 고신뢰 행동 신호. */
    TRIGGER,
}
