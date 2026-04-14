package com.example.seniorshield.monitoring.session

import android.util.Log
import androidx.annotation.VisibleForTesting
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
 * - 기본 TTL: 마지막 **새** 신호로부터 30분
 * - 이미 누적된 동일 신호 재수신 시 TTL 미갱신 (영구 유지 방지)
 * - 진짜 새 신호 발생 시 TTL 리셋
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

    /** 테스트용 시계 주입점. 프로덕션은 System.currentTimeMillis(). */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val _sessionState = MutableStateFlow<RiskSession?>(null)

    /** 현재 활성 세션을 실시간으로 관찰한다. null = 세션 없음. */
    val sessionState: StateFlow<RiskSession?> = _sessionState.asStateFlow()

    private var session: RiskSession?
        get() = _sessionState.value
        set(value) { _sessionState.value = value }

    // ── snooze state (팝업 call-scoped 억제) ────────────────────────
    // "이 통화는 안전해요" 클릭 시 session reset과 함께 snooze를 건다.
    // 같은 통화 ID의 call-derived signal은 Coordinator의 pre-update 필터에서 제거되어
    // session respawn을 차단한다. IDLE 전이, 새 통화, TTL 만료, 상위 trigger 출현 시 자동 해제.
    @Volatile private var snoozedCallId: Long? = null
    @Volatile private var snoozedAt: Long = 0L

    fun update(callSignals: List<RiskSignal>, appSignals: List<RiskSignal>): RiskSession? {
        val newSignals: Set<RiskSignal> = (callSignals + appSignals).toSet()
        val now = clock()
        val current = session

        session = when {
            // 세션 없음 + 신호 없음 → 아무 것도 없음
            current == null && newSignals.isEmpty() -> null

            // 세션 없음 + 신호 있음 → 새 세션 생성
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

            // 세션 존재 → 새 신호 여부에 따라 갱신 또는 만료 체크
            else -> {
                val added = newSignals - current.accumulatedSignals

                when {
                    // 진짜 새 신호 없이 TTL 초과 → 세션 만료
                    added.isEmpty() && now - current.lastSignalAt > currentTimeout(current) -> {
                        val duration = (now - current.startedAt) / 1000L
                        Log.d(TAG, "session expired [${current.id}] after ${duration}s (ttl=${if (current.hasTrigger) "60" else "30"}min)")
                        clearSnooze("session expired")
                        null
                    }

                    // 진짜 새 신호 있음 → 누적 + TTL 리셋
                    added.isNotEmpty() -> {
                        val hasTrigger = current.hasTrigger || newSignals.any { it.category == SignalCategory.TRIGGER }
                        current.copy(
                            accumulatedSignals = current.accumulatedSignals + newSignals,
                            lastSignalAt = now,
                            hasTrigger = hasTrigger,
                        ).also {
                            Log.d(TAG, "session updated [${it.id}] +$added → total=${it.accumulatedSignals} ttl=${if (hasTrigger) "60" else "30"}min")
                        }
                    }

                    // 새 신호 없음, TTL 미초과 → 현재 세션 유지
                    else -> current
                }
            }
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

    /**
     * 현재 tick의 raw signal에 없는 trigger를 [RiskSession.notifiedActiveThreats]에서 제거한다.
     *
     * ## 목적
     * accumulatedSignals는 TTL 만료까지 누적만 되므로, 통보 완료된 trigger가
     * 앱 종료로 signal에서 사라져도 notifiedActiveThreats에 영구히 남아
     * 재진입 시 새 trigger로 인식되지 못한다. 이 메소드가 "사라진 trigger"를
     * 통보 목록에서 제거하여 재진입 감지가 가능하도록 한다.
     *
     * ## debounce 안정성
     * [RealAppUsageRiskMonitor]는 30초 window로 polling하므로, tick signal에 없다는 것은
     * "최근 30초 이상 해당 앱이 포그라운드가 아니었다"는 의미다. 단일 tick 누락에
     * 반응하지 않으므로 추가 debounce가 불필요하다.
     *
     * ## 호출 위치
     * Coordinator의 tick 처리 초입(세션 업데이트 직후, 팝업/쿨다운 판단 전)에서 호출한다.
     */
    fun syncActiveThreats(currentTickSignals: Set<RiskSignal>): RiskSession? {
        val current = session ?: return null
        if (current.notifiedActiveThreats.isEmpty()) return current
        val stillPresent = current.notifiedActiveThreats intersect currentTickSignals
        if (stillPresent == current.notifiedActiveThreats) return current
        val removed = current.notifiedActiveThreats - stillPresent
        val updated = current.copy(notifiedActiveThreats = stillPresent)
        session = updated
        Log.d(TAG, "notifiedActiveThreats synced: removed=$removed (trigger 사라짐 → 재진입 대기)")
        return updated
    }

    // ── snooze API ─────────────────────────────────────────────────────

    /**
     * "이 통화는 안전해요" 탭 시 호출. 현재 [callId]에 바인딩된 snooze를 설정한다.
     * Coordinator는 이후 tick에서 같은 callId로 들어오는 call-derived signal을
     * `update()` 전에 필터링하여 세션 respawn을 차단한다.
     */
    fun snoozeForCall(callId: Long) {
        snoozedCallId = callId
        snoozedAt = clock()
        Log.d(TAG, "snooze set callId=$callId")
    }

    /** snooze 해제. IDLE 전이 / 통화 전환 / TTL 만료 / 상위 trigger / 세션 종료에서 호출. */
    fun clearSnooze(reason: String) {
        if (snoozedCallId == null) return
        Log.d(TAG, "snooze cleared (wasCallId=$snoozedCallId): $reason")
        snoozedCallId = null
        snoozedAt = 0L
    }

    /** snooze 활성 여부 — 단순 bool 체크. */
    fun isSnoozeActive(): Boolean = snoozedCallId != null

    /** 현재 snooze 대상 통화 ID. null = snooze 비활성. */
    fun snoozedCallIdOrNull(): Long? = snoozedCallId

    /** 특정 [callId]에 snooze가 걸려있는지. */
    fun isSnoozedForCall(callId: Long): Boolean = snoozedCallId == callId

    /** snooze가 설정된 시각(epoch ms). null = snooze 비활성. TTL 판정용. */
    fun snoozedAtOrNull(): Long? = if (snoozedCallId != null) snoozedAt else null

    /** 사용자 "안전 확인" — 세션 즉시 종료. */
    fun reset() {
        val id = session?.id
        session = null
        clearSnooze("session reset")
        Log.d(TAG, "session reset [id=$id]")
    }

    private fun currentTimeout(session: RiskSession): Long =
        if (session.hasTrigger) TRIGGER_IDLE_TIMEOUT_MS else DEFAULT_IDLE_TIMEOUT_MS
}
