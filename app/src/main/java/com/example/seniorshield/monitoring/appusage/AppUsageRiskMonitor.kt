package com.example.seniorshield.monitoring.appusage

import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.Flow

interface AppUsageRiskMonitor {
    fun observeAppUsageSignals(): Flow<List<RiskSignal>>

    /**
     * 뱅킹 앱이 현재 포그라운드에 있으면 true, 아니면 false를 방출한다.
     * 값이 바뀔 때만 방출한다 (distinctUntilChanged).
     */
    fun observeBankingAppForeground(): Flow<Boolean>
}