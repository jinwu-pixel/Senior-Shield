package com.example.seniorshield.monitoring.deviceenv

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import kotlinx.coroutines.flow.Flow

interface DeviceEnvironmentRiskMonitor {
    /**
     * 기기 환경 신호. [Produced.producedAtEpoch]는 최초 검사 **시작 전**(init) 1회 캡처되고,
     * StateFlow replay는 그 epoch를 재스탬프 없이 승계한다 — 첫 사용자 reset 이후에는 정직하게
     * stale이 되며, HIGH_RISK_DEVICE_ENVIRONMENT의 생존은 coordinator의 DEVICE_ENV 한정
     * 승계 예외가 담당한다.
     */
    fun observeDeviceEnvironmentSignals(): Flow<Produced<List<RiskSignal>>>
}
