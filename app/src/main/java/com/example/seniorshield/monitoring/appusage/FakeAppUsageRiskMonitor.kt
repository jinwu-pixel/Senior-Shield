package com.example.seniorshield.monitoring.appusage

import com.example.seniorshield.domain.model.RiskSignal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeAppUsageRiskMonitor @Inject constructor() : AppUsageRiskMonitor {
    override fun observeAppUsageSignals(): Flow<List<RiskSignal>> = flowOf(
        listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED, RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP)
    )

    override fun observeBankingAppForeground(): Flow<Boolean> = flowOf(false)
}