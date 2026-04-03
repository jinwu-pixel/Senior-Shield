package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallMonitorState
import kotlinx.coroutines.flow.Flow

interface CallRiskMonitor {
    /**
     * 현재 통화 모니터 상태.
     * - [CallMonitorState.NoPermission]: 권한 미부여
     * - [CallMonitorState.Idle]: 통화 없음
     * - [CallMonitorState.Active]: 통화 활성
     */
    fun observeCallContext(): Flow<CallMonitorState>

    /** Coordinator가 소비하는 신호 목록. */
    fun observeCallSignals(): Flow<List<RiskSignal>>
}
