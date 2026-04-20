package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.model.SignalCategory
import com.example.seniorshield.monitoring.session.RiskSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 세션의 누적 신호로부터 [AlertState]를 결정한다.
 *
 * 핵심 원칙: "수동 신호는 위험 세션만 만든다.
 * 팝업은 위험 세션 위에서 발생한 고신뢰 행동 트리거에만 반응한다."
 *
 * 결정 규칙:
 * ```
 * 세션 없음                          → OBSERVE
 * 세션 + PASSIVE/AMPLIFIER만          → GUARDED
 * 세션 + TRIGGER 1개                  → INTERRUPT
 * 세션 + 고신뢰 TRIGGER 조합          → CRITICAL
 * ```
 */
@Singleton
class AlertStateResolver @Inject constructor() {

    fun resolve(session: RiskSession?): AlertState {
        if (session == null) return AlertState.OBSERVE

        val signals = session.accumulatedSignals
        val triggers = signals.filter { it.category == SignalCategory.TRIGGER }

        if (triggers.isEmpty()) return AlertState.GUARDED

        if (isHighConfidenceCombo(signals, triggers)) return AlertState.CRITICAL

        return AlertState.INTERRUPT
    }

    /**
     * 고신뢰 CRITICAL 조합 판별.
     *
     * CRITICAL 조건:
     * - suspicious call session + 임의의 TRIGGER → CRITICAL
     *   (call 기반 세션에서 trigger가 발생하면 사기 흐름 확정)
     * - REMOTE_CONTROL_APP_OPENED + BANKING_APP_OPENED_AFTER_REMOTE_APP → CRITICAL
     *   (call 없이도 원격+금융은 확정적 위험)
     *
     * "suspicious call session" = PASSIVE call 신호가 1개 이상 존재.
     */
    private fun isHighConfidenceCombo(
        allSignals: Set<RiskSignal>,
        triggers: List<RiskSignal>,
    ): Boolean {
        val hasCallSession = allSignals.any { it in CALL_SIGNALS }

        // suspicious call + 임의의 trigger
        if (hasCallSession && triggers.isNotEmpty()) return true
        // 원격제어 + 금융 앱 (call 없이도)
        if (RiskSignal.REMOTE_CONTROL_APP_OPENED in triggers &&
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP in triggers
        ) return true

        return false
    }

    companion object {
        /** call-based session 판별에 사용하는 통화 관련 PASSIVE/AMPLIFIER 신호. */
        val CALL_SIGNALS: Set<RiskSignal> = setOf(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.UNVERIFIED_CALLER,
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
        )
    }
}
