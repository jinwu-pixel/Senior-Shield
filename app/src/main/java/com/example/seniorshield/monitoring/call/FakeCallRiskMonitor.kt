package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeCallRiskMonitor @Inject constructor() : CallRiskMonitor {

    override fun observeCallContext(): Flow<CallContext?> = flowOf(null)

    override fun observeCallSignals(): Flow<List<RiskSignal>> = flowOf(
        listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)
    )
}
