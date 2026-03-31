package com.example.seniorshield.monitoring.deviceenv

import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeDeviceEnvironmentRiskMonitor @Inject constructor() : DeviceEnvironmentRiskMonitor {
    override fun observeDeviceEnvironmentSignals(): Flow<List<RiskSignal>> =
        flowOf(emptyList())
}
