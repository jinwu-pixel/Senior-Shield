package com.example.seniorshield.monitoring.appinstall

import com.example.seniorshield.domain.model.RiskSignal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeAppInstallRiskMonitor @Inject constructor() : AppInstallRiskMonitor {
    override fun observeInstallSignals(): Flow<List<RiskSignal>> = flowOf(emptyList())
}
