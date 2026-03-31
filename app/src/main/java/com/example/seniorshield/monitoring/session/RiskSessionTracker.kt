package com.example.seniorshield.monitoring.session

import android.util.Log
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Session"

/** 비활동 30분이 지나면 세션을 자동 만료한다. */
private const val SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * 위험 세션의 생명주기를 관리하고 신호를 누적한다.
 *
 * [sessionState]를 통해 외부(DebugViewModel 등)에서 현재 세션을 관찰할 수 있다.
 */
@Singleton
class RiskSessionTracker @Inject constructor() {

    private val _sessionState = MutableStateFlow<RiskSession?>(null)

    /** 현재 활성 세션을 실시간으로 관찰한다. null = 세션 없음. */
    val sessionState: StateFlow<RiskSession?> = _sessionState.asStateFlow()

    // session 프로퍼티 변경 시 StateFlow가 자동 갱신된다.
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
                RiskSession(startedAt = now, accumulatedSignals = newSignals, lastSignalAt = now)
                    .also { Log.d(TAG, "session opened [${it.id}] signals=$newSignals") }
            }

            newSignals.isEmpty() && now - current.lastSignalAt > SESSION_IDLE_TIMEOUT_MS -> {
                val duration = (now - current.startedAt) / 1000L
                Log.d(TAG, "session expired [${current.id}] after ${duration}s")
                null
            }

            newSignals.isNotEmpty() -> {
                val added = newSignals - current.accumulatedSignals
                current.copy(
                    accumulatedSignals = current.accumulatedSignals + newSignals,
                    lastSignalAt = now,
                ).also {
                    if (added.isNotEmpty())
                        Log.d(TAG, "session updated [${it.id}] +$added → total=${it.accumulatedSignals}")
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

    fun markActiveThreatsNotified(threats: Set<RiskSignal>) {
        session = session?.copy(notifiedActiveThreats = threats)
        Log.d(TAG, "notifiedActiveThreats updated → $threats")
    }

    /** 세션을 강제로 초기화한다. 테스트·디버그 전용. */
    fun reset() {
        val id = session?.id
        session = null
        Log.d(TAG, "session reset [id=$id]")
    }
}
