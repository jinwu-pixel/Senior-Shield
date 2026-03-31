package com.example.seniorshield.monitoring.session

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import java.util.UUID

/**
 * 위험 감지 세션 — 첫 신호 발생부터 [SESSION_IDLE_TIMEOUT_MS] 비활동까지의 단위.
 *
 * 세션 내 신호는 누적만 되고 감소하지 않는다. TeamViewer를 잠깐 닫거나,
 * 통화가 종료된 직후에도 세션이 유지되므로 시퀀스 기반 탐지가 가능하다.
 *
 * [notifiedLevel]: 현재 세션에서 이미 알림을 보낸 최고 위험 수준.
 *   이 값보다 높은 수준으로 에스컬레이션되었을 때만 새 알림을 발생시킨다.
 *   null → 아직 알림 없음.
 */
data class RiskSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long,
    val accumulatedSignals: Set<RiskSignal> = emptySet(),
    val lastSignalAt: Long,
    val notifiedLevel: RiskLevel? = null,
    /** 이미 팝업을 표시한 능동적 위협 신호. 새 위협이 추가되면 재알림한다. */
    val notifiedActiveThreats: Set<RiskSignal> = emptySet(),
)
