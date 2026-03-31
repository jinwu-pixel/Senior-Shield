package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallContext
import kotlinx.coroutines.flow.Flow

interface CallRiskMonitor {
    /** 현재 통화 문맥. 통화 없는 초기 상태는 null. */
    fun observeCallContext(): Flow<CallContext?>

    /** Coordinator가 소비하는 신호 목록. */
    fun observeCallSignals(): Flow<List<RiskSignal>>
}
