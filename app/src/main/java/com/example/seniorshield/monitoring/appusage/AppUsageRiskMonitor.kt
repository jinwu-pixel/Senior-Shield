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

    /**
     * 최근 [windowMs] 이내에 발생한 뱅킹 앱 MOVE_TO_FOREGROUND 이벤트 중
     * 가장 최근 타임스탬프(epoch ms)를 반환한다.
     * 이벤트가 없거나 조회 실패 시 null.
     */
    fun latestBankingForegroundEventTimestamp(windowMs: Long = 30_000L): Long?
}