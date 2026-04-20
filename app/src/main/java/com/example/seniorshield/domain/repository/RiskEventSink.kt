package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.RiskEvent

interface RiskEventSink {
    suspend fun pushRiskEvent(event: RiskEvent)
    suspend fun updateCurrentRiskEvent(event: RiskEvent)
    /**
     * currentEvent를 즉시 null로 초기화한다.
     * 단순 nullable 대입이므로 비-suspend.
     * RiskOverlayManager 등 동기 컨텍스트에서 호출 가능 (scope 신설 회피).
     */
    fun clearCurrentRiskEvent()
    /** 이력 전체와 현재 이벤트를 초기화한다. 테스트·디버그 전용. */
    suspend fun clearAll()
}
