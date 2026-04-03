package com.example.seniorshield.monitoring.session

import android.util.Log
import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.model.SignalCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Session"

/** 기본 TTL: 마지막 신호로부터 30분. */
private const val DEFAULT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
/** TRIGGER 발생 시 연장 TTL: 60분. */
private const val TRIGGER_IDLE_TIMEOUT_MS = 60 * 60 * 1000L

/**
 * 위험 세션의 생명주기를 관리하고 신호를 누적한다.
 *
 * ## 세션 TTL 규칙
 * - 기본 TTL: 마지막 신호로부터 30분
 * - 새 PASSIVE/AMPLIFIER 신호 발생 시 TTL 30분 리셋
 * - TRIGGER 발생 시 TTL 60분으로 연장
 * - 사용자 "안전 확인" → 즉시 종료
 * - TTL 만료 → OBSERVE 복귀
 *
 * ## 세션 생성 조건
 * - PASSIVE 신호 1개 이상 감지 시 생성
 * - HIGH_RISK_DEVICE_ENVIRONMENT 단독으로는 세션을 생성하지 않음 (modifier)
 *
 * [sessionState]를 통해 외부(DebugViewModel 등)에서 현재 세션을 관찰할 수 있다.
 */
@Singleton
class RiskSessionTracker @Inject constructor() {

    private val _sessionState = MutableStateFlow<RiskSession?>(null)

    /** 현재 활성 세션을 실시간으로 관찰한다. null = 세션 없음. */
    val sessionState: StateFlow<RiskSession?> = _sessionState.asStateFlow()

    private var session: RiskSession?
        get() = _sessionState.value
        set(value) { _sessionState.value = value }

    fun update(callSignals: List<RiskSignal>, appSignals: List<RiskSignal>): RiskSession? {
        val newSignals: Set<RiskSignal> = (callSignals + appSignals).toSet()
        val now = System.currentTimeMillis()
        val current = session

        session = when {
            current == null && newSignals.isEmpty() -> null

            current == null -> {
                // HIGH_RISK_DEVICE_ENVIRONMENT 단독으로는 세션 생성 불가
                val sessionCreators = newSignals.filter { it != RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT }
                if (sessionCreators.isEmpty()) {
                    null
                } else {
                    val hasTrigger = newSignals.any { it.category == SignalCategory.TRIGGER }
                    RiskSession(
                        startedAt = now,
                        accumulatedSignals = newSignals,
                        lastSignalAt = now,
                        hasTrigger = hasTrigger,
                    ).also { Log.d(TAG, "session opened [${it.id}] signals=$newSignals") }
                }
            }

            newSignals.isEmpty() && now - current.lastSignalAt > currentTimeout(current) -> {
                val duration = (now - current.startedAt) / 1000L
                Log.d(TAG, "session expired [${current.id}] after ${duration}s (ttl=${if (current.hasTrigger) "60" else "30"}min)")
                null
            }

            newSignals.isNotEmpty() -> {
                val added = newSignals - current.accumulatedSignals
                val hasTrigger = current.hasTrigger || newSignals.any { it.category == SignalCategory.TRIGGER }
                current.copy(
                    accumulatedSignals = current.accumulatedSignals + newSignals,
                    lastSignalAt = now,
                    hasTrigger = hasTrigger,
                ).also {
                    if (added.isNotEmpty())
                        Log.d(TAG, "session updated [${it.id}] +$added → total=${it.accumulatedSignals} ttl=${if (hasTrigger) "60" else "30"}min")
                }
            }

            else -> current
        }

        return session
    }

    fun markNotified(level: RiskLevel) {
        session = session?.copy(notifiedLevel = level)
        Log.d(TAG, "notifiedLevel updated → $level")
    }

    fun markAlertStateNotified(state: AlertState) {
        session = session?.copy(notifiedAlertState = state)
        Log.d(TAG, "notifiedAlertState updated → $state")
    }

    fun markActiveThreatsNotified(threats: Set<RiskSignal>) {
        session = session?.copy(notifiedActiveThreats = threats)
        Log.d(TAG, "notifiedActiveThreats updated → $threats")
    }

    fun markCooldownConsumed() {
        session = session?.copy(cooldownConsumedAt = System.currentTimeMillis())
        Log.d(TAG, "cooldownConsumedAt updated")
    }

    /** 사용자 "안전 확인" — 세션 즉시 종료. */
    fun reset() {
        val id = session?.id
        session = null
        Log.d(TAG, "session reset [id=$id]")
    }

    private fun currentTimeout(session: RiskSession): Long =
        if (session.hasTrigger) TRIGGER_IDLE_TIMEOUT_MS else DEFAULT_IDLE_TIMEOUT_MS
}
