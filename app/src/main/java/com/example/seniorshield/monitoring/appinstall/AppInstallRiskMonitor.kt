package com.example.seniorshield.monitoring.appinstall

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import kotlinx.coroutines.flow.Flow

interface AppInstallRiskMonitor {
    /**
     * 설치 신호(사건 flow). [Produced.producedAtEpoch]는 BroadcastReceiver.onReceive
     * 진입부 — 설치 출처 조회·분류 **전** — 에 캡처된다. distinctUntilChanged는
     * (value, epoch) 기본 동등성 — reset 후 hold 창 내 실제 신규 설치는 새 epoch로 통과한다.
     */
    fun observeInstallSignals(): Flow<Produced<List<RiskSignal>>>
}
