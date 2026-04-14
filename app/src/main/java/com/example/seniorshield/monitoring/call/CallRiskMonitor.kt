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

    /**
     * 현재 활성 통화의 고유 식별자. OFFHOOK 진입 시각(ms) 기준.
     * 통화 중이 아니면 null.
     * 팝업 snooze 바인딩에 사용된다 — 같은 통화 내에서만 snooze 유지.
     */
    fun currentCallId(): Long?
}
