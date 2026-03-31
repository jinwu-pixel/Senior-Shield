package com.example.seniorshield.monitoring.appinstall

import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.Flow

interface AppInstallRiskMonitor {
    fun observeInstallSignals(): Flow<List<RiskSignal>>
}
