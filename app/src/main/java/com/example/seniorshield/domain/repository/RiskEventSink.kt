package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.RiskEvent

interface RiskEventSink {
    suspend fun pushRiskEvent(event: RiskEvent)
    suspend fun updateCurrentRiskEvent(event: RiskEvent)
    suspend fun clearCurrentRiskEvent()
    /** 이력 전체와 현재 이벤트를 초기화한다. 테스트·디버그 전용. */
    suspend fun clearAll()
}
