package com.example.seniorshield.monitoring.model

/**
 * 통화 모니터의 상태를 구분하는 sealed interface.
 *
 * [CallContext]가 null이었던 기존 설계에서는 "권한 없음"과 "통화 없음"이
 * 구분되지 않았다. 이 타입으로 의미를 분리하여 디버깅·테스트·상태 전이를 명확히 한다.
 */
sealed interface CallMonitorState {
    /** 전화 권한(READ_PHONE_STATE) 미부여 — 모니터링 불가 */
    data object NoPermission : CallMonitorState

    /** 권한 있음, 통화 없는 대기 상태 */
    data object Idle : CallMonitorState

    /** 권한 있음, 통화 문맥 활성 */
    data class Active(val context: CallContext) : CallMonitorState
}
