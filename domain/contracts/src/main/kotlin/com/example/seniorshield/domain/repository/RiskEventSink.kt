package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.RiskEvent

interface RiskEventSink {
    suspend fun pushRiskEvent(event: RiskEvent)
    /**
     * 이력에만 기록하고 currentEvent로 승격하지 않는다.
     * GUARDED(비-INTERRUPT) 세션의 notification 이벤트용 — currentEvent 승격은
     * 홈 WARNING 승격·Warning 활성 헤더를 유발하므로 INTERRUPT+ 전용이다.
     */
    suspend fun recordRiskEvent(event: RiskEvent)
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
