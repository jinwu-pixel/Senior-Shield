package com.example.seniorshield.monitoring.deviceenv

import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.Flow

interface DeviceEnvironmentRiskMonitor {
    fun observeDeviceEnvironmentSignals(): Flow<List<RiskSignal>>
}
