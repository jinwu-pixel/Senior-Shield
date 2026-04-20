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

    /**
     * 텔레뱅킹 윈도우 anchor(lastSuspiciousCallEndedAt)를 즉시 무효화한다.
     * 사용자가 위험 경고를 안전 종료할 때 호출되어, 종료 후 5분 내
     * 정상 은행 ARS 발신이 텔레뱅킹으로 오발화하는 것을 막는다.
     * 이미 anchor가 null이면 no-op.
     */
    fun clearTelebankingAnchor()

    /**
     * 현재 통화([callId])를 "사용자 안전 확인 완료" 상태로 마킹한다.
     * 다음 IDLE 전이 시 lastSuspiciousCallEndedAt을 설정하지 않는다 (anchor 스킵).
     * 통화 종료 후 자동으로 클리어된다 — 다른 통화에는 영향 없음.
     *
     * 호출 위치: B-3 (RiskOverlayManager 통화 중 "통화 경고 닫기").
     */
    fun markCurrentCallConfirmedSafe(callId: Long)
}
