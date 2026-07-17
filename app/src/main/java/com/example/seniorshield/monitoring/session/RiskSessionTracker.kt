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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Session"

/**
 * 기본 TTL: 마지막 신호로부터 30분.
 * `internal` — 테스트가 미러 상수 없이 직접 참조한다(SNOOZE_TTL_MS와 동일 패턴).
 */
internal const val DEFAULT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
/** TRIGGER 발생 시 연장 TTL: 60분. `internal` — 테스트 직접 참조용. */
internal const val TRIGGER_IDLE_TIMEOUT_MS = 60 * 60 * 1000L
/** α 재발화 억제 TTL 초기값 (60s). 튜닝 대상은 상수만 변경한다. */
private const val ALPHA_TTL_MS = 60_000L

/**
 * α 억제 escape 기준. `DefaultRiskDetectionCoordinator.kt`의 동일 이름 상수와 **내용 일치**를
 * 유지해야 한다. 공용화는 별도 증분에서 검토.
 */
private val UPGRADE_TRIGGERS: Set<RiskSignal> = setOf(
    RiskSignal.REMOTE_CONTROL_APP_OPENED,
    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
    RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
)

/**
 * 위험 세션의 생명주기를 관리하고 신호를 누적한다.
 *
 * ## 세션 TTL 규칙
 * - 기본 TTL: 마지막 **새** 신호로부터 30분
 * - 이미 누적된 동일 신호 재수신 시 TTL 미갱신 — 창 내 반복은 세션을 연장하지 못한다
 *   (단, 경계 시점에 실재하는 신호는 아래 renewal로 이어질 수 있다 — 무한 연장이 아니라 창 단위 갱신)
 * - 진짜 새 신호 발생 시 TTL 리셋
 * - TRIGGER 발생 시 TTL 60분으로 연장
 * - 사용자 "안전 확인" → 즉시 종료
 * - TTL 만료 시 처리는 [transition]이 **단일 시각(clock 1회 읽기)으로 원자 결정**:
 *   - 이번 tick 신호와 기존 accumulated의 **교집합이 있으면 renewal** — 같은 세션 ID로 신호 기반을
 *     rebase(lastSignalAt=now, hasTrigger 재계산)하고 통보/소비 메타데이터(notified*)를 승계한다.
 *     → notification·이력·GUARDED 카드·쿨다운이 **자동 재발동되지 않는다** (2026-07-14 D1 개정:
 *     rollover는 활성 위험 표시의 연속성만 유지. 재알림 주기는 별도 제품 정책으로 분리).
 *   - 교집합이 없으면 만료 후 새 신호로 **fresh episode**(새 ID) 생성 — 통상 알림 경로가 발화한다.
 *   - 신호가 없으면 만료 → OBSERVE 복귀.
 *
 * ## 세션 생성 조건
 * - PASSIVE 신호 1개 이상 감지 시 생성
 * - HIGH_RISK_DEVICE_ENVIRONMENT 단독으로는 세션을 생성/유지하지 않음 (modifier)
 *
 * [sessionState]를 통해 외부(DebugViewModel 등)에서 현재 세션을 관찰할 수 있다.
 * transition/renewal은 [sessionState]에 **중간 null 없이 단일 대입**으로 반영된다.
 */
@Singleton
class RiskSessionTracker @Inject constructor() : ResetEpochProvider {

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
    // "통화 경고 닫기" 클릭 시 session reset과 함께 snooze를 건다.
    // 같은 통화 ID의 call-derived signal은 Coordinator의 pre-update 필터에서 제거되어
    // session respawn을 차단한다. IDLE 전이, 새 통화, TTL 만료, 상위 trigger 출현 시 자동 해제.
    @Volatile private var snoozedCallId: Long? = null
    @Volatile private var snoozedAt: Long = 0L

    // ── α arm state (user-confirmed-safe 직후 non-call shared-root 재발화 억제) ──
    // `resetAfterUserConfirmedSafe()`로 직전 session의 accumulatedSignals 스냅샷과 시각을 기록한다.
    // `update()` 진입부에서 `callSignals.isEmpty()` + `appSignals ⊆ lastResetSignals`
    // + 신규 UPGRADE 없음 조건이면 새 session 생성을 억제한다. TTL 경과 시 자동 정리.
    @Volatile private var lastResetAt: Long? = null
    @Volatile private var lastResetSignals: Set<RiskSignal> = emptySet()

    // ── user-reset epoch (사용자 안전 확인 우선권) ──────────────────────
    // 사용자 주도 세션 종료([reset]/[resetAfterUserConfirmedSafe])의 **첫 문장**에서 증가한다.
    // Coordinator는 tick 시작 시 값을 캡처하고 transition 전·외부 효과(eventSink/notification/
    // overlay/cooldown) 직전마다 재확인해, 값이 바뀌었으면 세션 재생성과 잔여 효과를 중단한다.
    // 메서드 단위 @Synchronized 만으로는 "tick 중간에 끼어든 사용자 확인"을 이길 수 없기 때문.
    private val userResetEpochCounter = AtomicLong(0L)

    /**
     * 사용자 주도 리셋 세대. tick 시작 시 캡처 후 변경 감지에 사용.
     * [ResetEpochProvider] 구현 — Real monitor들이 생산 경계(조회·분류 전)에서 읽는다.
     */
    override val userResetEpoch: Long get() = userResetEpochCounter.get()

    /** [transition]의 결과. 단일 clock 읽기로 만료/renewal/유지가 원자 결정되었음을 호출자에 전달한다. */
    data class UpdateOutcome(
        val session: RiskSession?,
        /** 이번 호출에서 이전 세션이 종료됨 (진짜 idle 만료 또는 fresh episode 교체). */
        val expiredPrevious: Boolean,
        /** 이번 호출에서 이전 세션이 같은 ID로 연속 갱신됨 (D1 개정 renewal). */
        val renewed: Boolean,
        /** renewal을 정당화한 겹침(old∩live, HIGH_RISK 제외) — 검증 토큰의 대상. */
        val renewalBasis: Set<RiskSignal>? = null,
        /** [expectedEpoch] 불일치로 아무 것도 수행하지 않고 반환됨 (사용자 확인 우선). */
        val aborted: Boolean = false,
    )

    /** [transition]의 축약 — 세션만 필요한 호출자(DebugViewModel 등)용. */
    fun update(callSignals: List<RiskSignal>, appSignals: List<RiskSignal>): RiskSession? =
        transition(callSignals, appSignals).session

    /**
     * [expectedEpoch]가 주어지면 **락 안에서** [userResetEpoch]와 원자 비교한다 — 사용자
     * 안전 확인(reset)은 같은 락에서 epoch를 먼저 올리므로, 확인-후-전이 사이에 reset이
     * 끼어드는 창이 구조적으로 사라진다. 불일치 시 상태를 건드리지 않고 aborted를 반환한다.
     */
    @Synchronized
    fun transition(
        callSignals: List<RiskSignal>,
        appSignals: List<RiskSignal>,
        expectedEpoch: Long? = null,
    ): UpdateOutcome {
        if (expectedEpoch != null && expectedEpoch != userResetEpochCounter.get()) {
            Log.d(TAG, "transition aborted: user reset epoch advanced (expected=$expectedEpoch, now=${userResetEpochCounter.get()})")
            return UpdateOutcome(session, expiredPrevious = false, renewed = false, aborted = true)
        }
        val newSignals: Set<RiskSignal> = (callSignals + appSignals).toSet()
        val now = clock()
        var current = session
        var expiredPrevious = false
        var renewed = false
        var renewalBasis: Set<RiskSignal>? = null

        // ── idle TTL 원자 집행 (단일 now 기준 — 경계 straddle 없음) ──────────
        if (current != null && isTimedOut(current, now)) {
            // HIGH_RISK_DEVICE_ENVIRONMENT는 세션을 생성/유지하지 못하는 modifier이므로
            // renewal 판정의 겹침 근거에서도 제외한다 (루팅 단말에서 영구 latched되기 때문).
            val creators = newSignals - RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT
            val overlap = creators intersect current.accumulatedSignals
            if (creators.isNotEmpty() && overlap.isNotEmpty()) {
                // D1 개정 renewal: 표시 연속성만 유지 — 같은 ID + notified* 승계라
                // notification·이력·카드·쿨다운이 자동 재발동되지 않는다.
                // basis는 old∩live(겹침)만 — 이번 tick의 진짜 새 신호는 아래 added 분기가
                // 통상 경로(신규 신호 누적)로 처리한다.
                val hasTrigger = overlap.any { it.category == SignalCategory.TRIGGER }
                current = current.copy(
                    accumulatedSignals = overlap,
                    lastSignalAt = now,
                    hasTrigger = hasTrigger,
                )
                renewed = true
                renewalBasis = overlap
                Log.d(TAG, "session renewed [${current.id}] basis=$overlap ttl=${if (hasTrigger) "60" else "30"}min (표시 연속, 재발동 없음)")
            } else {
                logExpiry(current, now)
                clearSnooze("session expired")
                current = null
                expiredPrevious = true
            }
        }

        // α: non-call shared-root 재발화 억제.
        // current == null + callSignals 비어있음 + TTL 내 + appSignals ⊆ armed + 신규 UPGRADE 없음 → 새 session 생성 스킵.
        if (current == null && callSignals.isEmpty()) {
            val armedAt = lastResetAt
            if (armedAt != null) {
                if (now - armedAt > ALPHA_TTL_MS) {
                    lastResetAt = null
                    lastResetSignals = emptySet()
                } else {
                    val appSet = appSignals.toSet()
                    val armedSignals = lastResetSignals
                    val upgradeNew = appSet.filter { it in UPGRADE_TRIGGERS } - armedSignals
                    if (appSet.isNotEmpty() && appSet.all { it in armedSignals } && upgradeNew.isEmpty()) {
                        Log.d(TAG, "non-call session respawn suppressed by α (appSet=$appSet ⊆ armed=$armedSignals)")
                        session = null
                        return UpdateOutcome(null, expiredPrevious, renewed = false)
                    }
                }
            }
        }

        val next: RiskSession? = when {
            // 세션 없음 + 신호 없음 → 아무 것도 없음
            current == null && newSignals.isEmpty() -> null

            // 세션 없음 + 신호 있음 → 새 세션 생성 (만료 직후면 fresh episode)
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

            // 세션 존재(살아있거나 방금 renewal됨) → 새 신호 여부에 따라 갱신/유지
            else -> {
                val added = newSignals - current.accumulatedSignals

                when {
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

        // 단일 대입 — renewal은 old→renewed, fresh episode는 old→new로 전이하며
        // 관찰자([sessionState])에게 불필요한 중간 null을 노출하지 않는다.
        session = next
        return UpdateOutcome(next, expiredPrevious, renewed, renewalBasis)
    }

    /**
     * renewal 검증 토큰이 "이미 사라진 신호로 갱신했다"고 판정한 [lostSignals]를 현재 세션에서
     * 원자적으로 걷어낸다 (부분 소멸 rebase). 남은 신호가 세션을 유지할 수 없으면(HIGH_RISK 단독
     * 포함) 세션을 즉시 종료하고 null을 반환한다. lastSignalAt은 갱신하지 않는다 — rebase는
     * 근거 교정이지 새 신호가 아니다. 사용자 액션이 아니므로 [userResetEpoch]·α 불간섭.
     */
    @Synchronized
    fun rebaseRenewedSession(sessionId: String, lostSignals: Set<RiskSignal>): RiskSession? {
        val current = session ?: return null
        if (current.id != sessionId) return current
        val survivors = current.accumulatedSignals - lostSignals
        val creators = survivors - RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT
        if (creators.isEmpty()) {
            Log.d(TAG, "session renewal invalidated [${current.id}] — lost=$lostSignals, survivors=$survivors")
            session = null
            clearSnooze("renewal invalidated")
            return null
        }
        val rebased = current.copy(
            accumulatedSignals = survivors,
            hasTrigger = survivors.any { it.category == SignalCategory.TRIGGER },
        )
        session = rebased
        Log.d(TAG, "session rebased [${rebased.id}] — lost=$lostSignals → survivors=$survivors")
        return rebased
    }

    /**
     * renewal 근거가 **전부** 소멸했을 때 호출된다: 기존 세션의 정체성(ID·startedAt·notified*)은
     * 죽은 근거 위에 서 있으므로 종료하고, **정제된 현재 실측 신호**([liveSignals])만으로
     * fresh episode(새 ID, 빈 통보 메타데이터)를 분리한다 — 과거 누적값(이미 사라진 신호)을
     * 부활시키지 않고, 새 위협이 낡은 세션의 알림 억제를 승계하지 않는다.
     * live 신호가 세션을 만들 수 없으면 종료만 한다. 어느 쪽이든 old→fresh 또는 old→null의
     * **단일 대입**으로 전이한다 (중간 null 노출 없음).
     */
    @Synchronized
    fun splitAfterRenewalBasisDied(sessionId: String, liveSignals: Set<RiskSignal>): RiskSession? {
        val current = session ?: return null
        if (current.id != sessionId) return current
        val creators = liveSignals - RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT
        clearSnooze("renewal basis died")
        if (creators.isEmpty()) {
            Log.d(TAG, "session renewal invalidated [${current.id}] — basis died, live=$liveSignals")
            session = null
            return null
        }
        val now = clock()
        val fresh = RiskSession(
            startedAt = now,
            accumulatedSignals = liveSignals,
            lastSignalAt = now,
            hasTrigger = liveSignals.any { it.category == SignalCategory.TRIGGER },
        )
        session = fresh // old→fresh 단일 대입
        Log.d(TAG, "session split [${current.id}] → fresh episode [${fresh.id}] live=$liveSignals (renewal basis died)")
        return fresh
    }

    /**
     * monitor Flow 재방출과 무관하게 현재 세션의 idle TTL을 점검한다.
     *
     * 정확한 경계에서는 유지하고(elapsed == TTL), 1ms라도 초과했을 때만 만료한다.
     * 이미 만료되었거나 세션이 없으면 false를 반환하므로 Coordinator cleanup은 한 번만 실행된다.
     *
     * `@Synchronized` — IO lane(coordinator maintenance pulse)과 main thread(사용자 CTA reset,
     * DebugViewModel update)가 동시에 세션을 쓸 수 있어, stale 스냅샷 기반 expire가 방금 생성된
     * 세션/스누즈를 지우는 lost-update를 클래스 단일 락으로 차단한다.
     */
    @Synchronized
    fun expireIfTimedOut(): Boolean {
        val current = session ?: return false
        val now = clock()
        if (!isTimedOut(current, now)) return false

        expire(current, now)
        return true
    }

    /**
     * 현재 세션이 idle TTL을 초과했는지 **읽기 전용**으로 판정한다 (상태 변경 0).
     * Coordinator maintenance pulse의 저비용 게이트 — 실제 만료/renewal 결정은 신호와 함께
     * [transition]이 단일 시각으로 내린다.
     */
    @Synchronized
    fun isCurrentSessionIdleTimedOut(): Boolean {
        val current = session ?: return false
        return isTimedOut(current, clock())
    }

    /**
     * [sessionId]가 현재 세션이면 즉시 종료한다. 스냅샷 기반 renewal이 직후의 실측 신호와
     * 모순될 때(위협이 이미 끝났는데 stale 스냅샷으로 갱신된 경우) Coordinator가 호출한다.
     * 사용자 액션이 아니므로 [userResetEpoch]는 증가시키지 않고 α도 arm하지 않는다.
     */
    @Synchronized
    fun expireNowIfCurrent(sessionId: String, reason: String): Boolean {
        val current = session ?: return false
        if (current.id != sessionId) return false
        Log.d(TAG, "session force-expired [${current.id}]: $reason")
        session = null
        clearSnooze("session force-expired: $reason")
        return true
    }

    @Synchronized
    fun markNotified(level: RiskLevel) {
        session = session?.copy(notifiedLevel = level)
        Log.d(TAG, "notifiedLevel updated → $level")
    }

    @Synchronized
    fun markAlertStateNotified(state: AlertState) {
        session = session?.copy(notifiedAlertState = state)
        Log.d(TAG, "notifiedAlertState updated → $state")
    }

    @Synchronized
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
    @Synchronized
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
     * "통화 경고 닫기" 탭 시 호출. 현재 [callId]에 바인딩된 snooze를 설정한다.
     * Coordinator는 이후 tick에서 같은 callId로 들어오는 call-derived signal을
     * `update()` 전에 필터링하여 세션 respawn을 차단한다.
     */
    @Synchronized
    fun snoozeForCall(callId: Long) {
        snoozedCallId = callId
        snoozedAt = clock()
        Log.d(TAG, "snooze set callId=$callId")
    }

    /** snooze 해제. IDLE 전이 / 통화 전환 / TTL 만료 / 상위 trigger / 세션 종료에서 호출. */
    @Synchronized
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

    /**
     * 일반/debug reset — 세션 즉시 종료. α arm은 **하지 않는다.**
     * 사용자 "안전 확인" 경로는 [resetAfterUserConfirmedSafe]를 사용한다.
     */
    @Synchronized
    fun reset() {
        userResetEpochCounter.incrementAndGet() // 가장 먼저 — 진행 중 tick의 잔여 효과 무효화
        val id = session?.id
        session = null
        clearSnooze("session reset")
        Log.d(TAG, "session reset [id=$id]")
    }

    /**
     * 사용자 "안전 확인" 경로 전용 reset. session이 있던 경우 직전 accumulatedSignals 스냅샷과
     * 현재 시각을 저장해 α(non-call shared-root 재발화 억제)를 arm한다.
     *
     * arm 조건: `session != null` && `accumulatedSignals.isNotEmpty()`.
     * session-null race 또는 빈 snapshot은 arm하지 않는다 — 빈 set이 저장되면 모든 non-call 재발화가
     * 구조적으로 억제되는 의도치 않은 동작을 초래한다.
     *
     * 호출부: B-3(RiskOverlayManager), B-5(HomeViewModel), B-6(WarningViewModel).
     * debug/admin reset(DebugViewModel)은 [reset]을 그대로 사용한다.
     */
    @Synchronized
    fun resetAfterUserConfirmedSafe() {
        userResetEpochCounter.incrementAndGet() // 가장 먼저 — 진행 중 tick의 잔여 효과 무효화
        val snapshot = session?.accumulatedSignals ?: emptySet()
        if (snapshot.isNotEmpty()) {
            lastResetAt = clock()
            lastResetSignals = snapshot
            Log.d(TAG, "α armed (lastResetSignals=$snapshot)")
        }
        val id = session?.id
        session = null
        clearSnooze("session reset (user-confirmed-safe)")
        Log.d(TAG, "session reset [id=$id, reason=user-confirmed-safe]")
    }

    @VisibleForTesting
    internal fun alphaArmedAt(): Long? = lastResetAt

    @VisibleForTesting
    internal fun alphaArmedSignals(): Set<RiskSignal> = lastResetSignals

    private fun currentTimeout(session: RiskSession): Long =
        if (session.hasTrigger) TRIGGER_IDLE_TIMEOUT_MS else DEFAULT_IDLE_TIMEOUT_MS

    private fun isTimedOut(session: RiskSession, now: Long): Boolean =
        now - session.lastSignalAt > currentTimeout(session)

    private fun logExpiry(sessionToExpire: RiskSession, now: Long) {
        val duration = (now - sessionToExpire.startedAt) / 1000L
        Log.d(
            TAG,
            "session expired [${sessionToExpire.id}] after ${duration}s " +
                "(ttl=${if (sessionToExpire.hasTrigger) "60" else "30"}min)",
        )
    }

    private fun expire(sessionToExpire: RiskSession, now: Long) {
        logExpiry(sessionToExpire, now)
        session = null
        clearSnooze("session expired")
    }
}
