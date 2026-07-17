package com.example.seniorshield.monitoring.orchestrator

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.model.SignalCategory
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.monitoring.appinstall.AppInstallRiskMonitor
import com.example.seniorshield.monitoring.appusage.AppUsageRiskMonitor
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.DeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.evaluator.RiskEvaluator
import com.example.seniorshield.monitoring.event.RiskEventFactory
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Coordinator"

/**
 * snooze 자동 만료: 설정 후 15분 경과 시 해제.
 * `internal` — 동일 패키지 테스트가 미러 없이 직접 참조한다([S2_REC_REFIRE_TTL_MS]와 동일 패턴).
 */
internal const val SNOOZE_TTL_MS = 15 * 60 * 1000L

/**
 * maintenance pulse 주기 — 신호 재방출과 무관하게 "시간 경과만으로" 일어나는 상태 전이를 반영한다.
 *
 * **왜 polling이 필요한가**
 * monitor flow는 전부 distinctUntilChanged/StateFlow라 값이 그대로면 emit이 없다. 그런데
 * (a) `CallRiskMonitor.isTelebankingAnchorHot()`의 5분 TTL 만료, (b) `RiskSession`의 30/60분
 * idle TTL 만료는 시간 경과로만 일어나므로, 그 순간 combine이 깨어나지 않아 Home이
 * GUARDED_ANCHOR/WARNING에 고정될 수 있다 ("통화 종료 후 가만히 있는" 시나리오에서 흔함).
 *
 * **한 pulse가 하는 일** (신호와 merge된 단일 직렬 lane의 MaintenanceTick 분기)
 * 1. anchor-hot mirror 갱신 (`refreshAnchorHotMirror`)
 * 2. 세션 idle TTL 게이트 (`RiskSessionTracker.isCurrentSessionIdleTimedOut`, 읽기 전용)
 * 3. idle 초과 시 마지막 신호 스냅샷으로 1회 재평가 — 실제 만료/renewal은 `transition()`이
 *    단일 시각으로 결정한다. 위협 latched → **같은 세션 ID renewal**(notified* 승계라
 *    notification·이력·GUARDED 카드·쿨다운 자동 재발동 없음, 표시 연속성만 유지 — 2026-07-14
 *    D1 개정; 재알림 주기는 별도 제품 정책으로 분리) / 진짜 idle → 만료+presentation 정리.
 *    스냅샷 renewal은 다음 실측 방출이 검증하며, 모순되면 즉시 무효화된다.
 *
 * **왜 15초인가**
 * - anchor TTL 5분(300초) 대비 20× 조밀하여 사용자가 느끼는 stale 창이 충분히 짧다.
 * - 세션 TTL 초과의 실제 반영 지연도 최대 0~15초 (수용 가능).
 * - 평시 pulse는 timestamp 비교 수준이라 CPU/배터리 부담 무시 가능 (재평가는 만료 tick 1회뿐).
 *
 * **변경 시 주의**
 * 이 값을 크게 늘리면 (a) "5분 후 SAFE 자연 복귀"(P2 실기 시나리오), (b) 만료 세션의 경고 잔존 창이
 * 모두 그만큼 늦어진다. 줄이면 배터리/CPU 대비 이득이 없다.
 * `internal` — 동일 패키지 테스트가 미러 상수 없이 직접 참조한다([SNOOZE_TTL_MS]와 동일 패턴).
 */
internal const val ANCHOR_MIRROR_INTERVAL_MS = 15 * 1000L

/**
 * TTL renewal(스냅샷·신호 경로 공통)을 직후의 실측 재방출로 검증하는 신선도 창.
 *
 * merge는 upstream 간 인과 순서를 보장하지 않고 usage monitor는 ~30초 polling이라, renewal에
 * 쓴 신호 값이 "이미 끝난 위협"일 수 있다. renewal마다 source별 근거 몫과 sequence를 토큰으로
 * 기록하고, 이 창 안에서 **해당 source가 실제로 재방출**(sequence 전진)했을 때만 그 몫을 판정한다
 * — 무관한 source 방출(예: banking flip)은 토큰을 소비하지 않는다. 소멸한 몫은 세션에서 원자
 * rebase로 걷어내고, 남는 근거가 없으면 renewal을 무효화한다. 창 밖의 신호 소멸은 stale 문제가
 * 아니라 정상 감쇠이므로 통상 TTL 규칙이 처리한다. `internal` — 테스트 직접 참조용.
 */
internal const val RENEWAL_VALIDATION_WINDOW_MS = 45_000L

/**
 * same-call snooze가 활성인 동안 `sessionTracker.update()` 전에 제거되는 call-derived signal.
 * TELEBANKING_AFTER_SUSPICIOUS는 여기 포함되지 않는다 — 상위 trigger로 분류되어 snooze를 해제한다.
 */
private val CALL_DERIVED_SIGNALS: Set<RiskSignal> = setOf(
    RiskSignal.UNKNOWN_CALLER,
    RiskSignal.LONG_CALL_DURATION,
    RiskSignal.UNVERIFIED_CALLER,
    RiskSignal.REPEATED_UNKNOWN_CALLER,
    RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
)

/**
 * 같은 tick에 하나라도 관측되면 snooze를 즉시 해제하고 정상 평가로 복귀한다.
 * 여기 포함된 신호는 pre-update 필터 대상이 아니다.
 */
private val UPGRADE_TRIGGERS: Set<RiskSignal> = setOf(
    RiskSignal.REMOTE_CONTROL_APP_OPENED,
    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
    RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
)

/**
 * 통화·앱 사용 신호를 세션 단위로 누적 평가하고 알림·인터럽터를 조율한다.
 *
 * ## 핵심 원칙
 * "수동 신호는 위험 세션만 만든다.
 *  팝업은 위험 세션 위에서 발생한 고신뢰 행동 트리거에만 반응한다."
 *
 * ## AlertState 행동 정책
 * - OBSERVE:   노출 없음
 * - GUARDED:   notification (상태 전이 1회). 팝업 없음. call-based 세션일 때만 banking cooldown.
 * - INTERRUPT: notification + popup. banking cooldown.
 * - CRITICAL:  notification + popup. banking cooldown.
 *
 * ## 노출 dedupe
 * - notification: AlertState 전이 시 1회만
 * - popup: 동일 trigger 반복 시 debounce (notifiedActiveThreats)
 * - banking cooldown: 포그라운드 전환(false→true)마다 발동
 */
@Singleton
class DefaultRiskDetectionCoordinator @Inject constructor(
    private val callMonitor: CallRiskMonitor,
    private val appUsageMonitor: AppUsageRiskMonitor,
    private val appInstallMonitor: AppInstallRiskMonitor,
    private val deviceEnvMonitor: DeviceEnvironmentRiskMonitor,
    private val evaluator: RiskEvaluator,
    private val eventFactory: RiskEventFactory,
    private val eventSink: RiskEventSink,
    private val notificationManager: RiskNotificationManager,
    private val overlayManager: RiskOverlayManager,
    private val cooldownManager: BankingCooldownManager,
    private val sessionTracker: RiskSessionTracker,
    private val alertStateResolver: AlertStateResolver,
    private val guardianRepository: GuardianRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : RiskDetectionCoordinator {

    /** 테스트용 시계 주입점. 프로덕션은 System.currentTimeMillis(). */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null
    @Volatile private var previousBankingForeground = false

    /**
     * 이번 세션에서 쿨다운이 이미 소비된 세션 ID. 같은 session.id에서는 재발동 금지.
     * 세션 변경(id 다름) 또는 세션 소멸(null 반환) 시 tick 초입에서 자동 clear.
     */
    @Volatile private var cooldownConsumedSessionId: String? = null

    /**
     * S2 REC-REFIRE debounce 게이트 상태.
     *
     * Coordinator collect 블록 안에서만 read/write되며 외부에 노출되지 않는다.
     * α 변수와 5축 disjoint 공존 — 같은 `UPGRADE_TRIGGERS` set만 의미상 공유한다.
     * 자세한 설계는 `investigations/2026-04-24-cta-semantics/03_step2_design.md`,
     * `04_step3_impl_plan.md` 참조.
     */
    @Volatile private var s2RecRefireState = S2RecRefireDebounceState()

    private val _anchorHotState = MutableStateFlow(false)
    override val anchorHotState: StateFlow<Boolean> = _anchorHotState.asStateFlow()

    private suspend fun firstGuardian(): Guardian? =
        guardianRepository.observeGuardians().first().firstOrNull()

    /**
     * 생산 경계에서 스탬프된 [Produced]를 coordinator-local sequence와 함께 [SourceEmission]으로
     * 승계한다 (A안). epoch는 monitor가 조회·분류 **전**에 캡처한 값 그대로 — 과거 stamped()처럼
     * 수신 시점에 읽지 않으므로, flowOn/shareIn 버퍼에 잠긴 reset-이전 생산 데이터가 fresh로
     * 위장될 수 없다. sequence는 "같은 값의 재방출 식별"용 coordinator 내부 관심사로 유지한다.
     */
    private fun <T> Flow<Produced<T>>.sourced(sourceId: SourceId): Flow<SourceEmission<T>> = flow {
        var sequence = 0L
        collect { produced ->
            emit(SourceEmission(sourceId, ++sequence, produced.producedAtEpoch, produced.value))
        }
    }

    /**
     * source가 사용자 reset **이전**에 생산한 값이면 빈 값으로 취급한다.
     * 배제는 그 source가 reset 이후 재방출할 때까지 지속된다 — 통화 신호는 상태 전이마다
     * 재방출되므로 자연 회복되고, latched 앱 신호는 실제 변화가 있을 때 회복된다.
     * (α의 60초 escape보다 강한 억제 — 상호작용은 재알림 주기 정책 트랙에서 함께 결정.)
     *
     * 예외: HIGH_RISK_DEVICE_ENVIRONMENT는 승계한다 — 세션 생성/유지/renewal 근거가 될 수 없는
     * 지속 환경 modifier라 reset 우회 위험이 없고, deviceEnv monitor는 초기화 시 1회만 방출하므로
     * 배제하면 앱 재시작 전까지 영구 소실되기 때문이다.
     */
    private fun signalsIfFresh(
        emission: SourceEmission<List<RiskSignal>>,
        epochNow: Long,
    ): List<RiskSignal> =
        if (emission.producedAtEpoch >= epochNow) {
            emission.value
        } else if (emission.sourceId == SourceId.DEVICE_ENV) {
            // 승계 예외는 DEVICE_ENV source에만 허용 — 계약을 source 단위로 명확화한다.
            emission.value.filter { it == RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT }
        } else {
            emptyList()
        }

    override fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "coordinator started")
        job = scope.launch {
            val signalUpdates: Flow<CoordinatorEvent> = combine(
                callMonitor.observeCallSignals().sourced(SourceId.CALL),
                appUsageMonitor.observeAppUsageSignals().sourced(SourceId.APP_USAGE),
                appUsageMonitor.observeBankingAppForeground().sourced(SourceId.BANKING),
                appInstallMonitor.observeInstallSignals().sourced(SourceId.APP_INSTALL),
                deviceEnvMonitor.observeDeviceEnvironmentSignals().sourced(SourceId.DEVICE_ENV),
            ) { call, app, banking, install, deviceEnv ->
                CombinedSignals(call, app, banking, install, deviceEnv)
            }
            val maintenanceTicks = flow<CoordinatorEvent> {
                while (true) {
                    emit(CoordinatorEvent.MaintenanceTick)
                    delay(ANCHOR_MIRROR_INTERVAL_MS)
                }
            }
            // maintenance 만료 직후 재평가용 마지막 신호 스냅샷. collect lane에서만 접근(직렬).
            var latestSignals: CombinedSignals? = null
            // 직전 TTL renewal(스냅샷·신호 경로 공통)의 검증 토큰.
            var renewalToken: RenewalToken? = null
            // version vector: 직전에 처리한 tick의 source별 sequence — "이번 tick에서 실제로
            // 전진한(재방출된) source"를 식별한다.
            val lastSeenSeq = mutableMapOf<SourceId, Long>()

            merge(signalUpdates, maintenanceTicks)
                .collect { tick ->
                    // ── 공통 prologue ────────────────────────────────────────
                    // TTL 만료/renewal/유지는 신호와 함께 transition()이 단일 시각으로 원자 결정한다.
                    // 여기서는 mirror 갱신 + maintenance 저비용 게이트만.
                    refreshAnchorHotMirror()

                    val signals = when (tick) {
                        is CombinedSignals -> tick.also { latestSignals = it }
                        CoordinatorEvent.MaintenanceTick -> {
                            // 위협 신호가 latched(monitor flow 전부 distinctUntilChanged → 재방출 0)인 채
                            // TTL이 만료될 수 있으므로, idle 초과가 감지된 tick에서만 마지막 스냅샷으로
                            // 1회 재평가한다. 위협 지속 → 같은 세션 renewal(재발동 없음) / 진짜 idle → 종료.
                            if (!sessionTracker.isCurrentSessionIdleTimedOut()) return@collect
                            latestSignals ?: run {
                                // 첫 combine 방출 전(스냅샷 없음) 만료 — 재평가 없이 만료+정리만.
                                if (sessionTracker.expireIfTimedOut()) {
                                    clearInactiveSessionPresentation("session TTL expired (no snapshot)")
                                }
                                return@collect
                            }
                        }
                    }

                    // ── per-source reset 위생검사 (다른 모든 처리보다 먼저) ─────
                    // source별 생산 시점 epoch가 현재 reset 세대보다 오래됐으면 그 source의 값은
                    // 빈 값으로 취급한다 — 신선한 source 하나(예: banking flip)가 stale 구성원
                    // 전체를 승인해 세션·쿨다운을 되살리는 혼합 epoch 우회를 차단한다.
                    // 캐시(maintenance 재사용 tick)도 동일 규칙으로 자동 flush된다.
                    // 배제는 해당 source가 reset 이후 재방출할 때까지 지속된다.
                    val epochAtTickStart = sessionTracker.userResetEpoch
                    val freshBySource = mapOf(
                        SourceId.CALL to signalsIfFresh(signals.call, epochAtTickStart),
                        SourceId.APP_USAGE to signalsIfFresh(signals.app, epochAtTickStart),
                        SourceId.APP_INSTALL to signalsIfFresh(signals.install, epochAtTickStart),
                        SourceId.DEVICE_ENV to signalsIfFresh(signals.deviceEnv, epochAtTickStart),
                    )
                    val callSignals = freshBySource.getValue(SourceId.CALL)
                    val appSignals = freshBySource.getValue(SourceId.APP_USAGE)
                    val installSignals = freshBySource.getValue(SourceId.APP_INSTALL)
                    val deviceEnvSignals = freshBySource.getValue(SourceId.DEVICE_ENV)
                    // BANKING도 동일 규칙: reset 이전 생산 값은 edge를 만들지도, previous를
                    // 오염시키지도 못한다 (effective=previous → tick 말 대입도 불변).
                    val bankingForeground =
                        if (signals.banking.producedAtEpoch < epochAtTickStart) previousBankingForeground
                        else signals.banking.value
                    // 이번 tick에서 실제로 전진한(재방출된) source — 즉시-confirmed 판정용.
                    val advancedSources = SourceId.values()
                        .filter { signals.sequenceOf(it) > (lastSeenSeq[it] ?: 0L) }
                        .toSet()
                    SourceId.values().forEach { lastSeenSeq[it] = signals.sequenceOf(it) }
                    Log.d(TAG, "signal tick — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals, advanced=$advancedSources")

                    // ── end-call suppression: IDLE 감지 시 안정화 타이머 시작 ──
                    // raw callSignals 기준 — snooze filter와 무관한 실제 통화 상태 반영.
                    if (overlayManager.isEndCallSuppressed() && callSignals.isEmpty()) {
                        overlayManager.scheduleSuppressionRelease()
                        Log.d(TAG, "call became IDLE during suppression, stabilization scheduled")
                    }

                    // ── 1단계: pre-update snooze stage ───────────────────────
                    // sessionTracker.update() 전에 snooze를 평가하고 call-derived signal을 필터링한다.
                    // 목적: same call respawn 차단. 같은 통화에서 반복 수신되는 PASSIVE 신호가
                    //       세션에 누적되어 score/alertState가 상승하거나 재알림이 발생하는 것을 막는다.
                    val liveCallId = callMonitor.currentCallId()
                    val nonCallSignals = appSignals + installSignals + deviceEnvSignals
                    val upgradeTriggerPresent =
                        callSignals.any { it in UPGRADE_TRIGGERS } ||
                            nonCallSignals.any { it in UPGRADE_TRIGGERS }

                    if (sessionTracker.isSnoozeActive()) {
                        val snoozedId = sessionTracker.snoozedCallIdOrNull()
                        val snoozedAt = sessionTracker.snoozedAtOrNull() ?: 0L
                        val now = clock()
                        when {
                            liveCallId == null ->
                                sessionTracker.clearSnooze("IDLE (wasCallId=$snoozedId)")
                            liveCallId != snoozedId ->
                                sessionTracker.clearSnooze("new call: live=$liveCallId snoozed=$snoozedId")
                            now - snoozedAt > SNOOZE_TTL_MS ->
                                sessionTracker.clearSnooze("TTL 15min elapsed (wasCallId=$snoozedId)")
                            upgradeTriggerPresent ->
                                sessionTracker.clearSnooze("upgrade trigger present in raw signals")
                            else -> { /* 유지 */ }
                        }
                    }

                    // snooze가 여전히 활성이고 liveCallId와 일치하면 call-derived signal 필터링.
                    val filteredCallSignals: List<RiskSignal> =
                        if (liveCallId != null && sessionTracker.isSnoozedForCall(liveCallId)) {
                            val filtered = callSignals.filterNot { it in CALL_DERIVED_SIGNALS }
                            Log.d(TAG, "snooze filter applied (callId=$liveCallId): rawCall=$callSignals → filteredCall=$filtered")
                            filtered
                        } else {
                            callSignals
                        }

                    // ── renewal 검증 토큰: 해당 source의 실측 재방출만 몫을 판정한다 ──
                    // 무관한 방출(banking flip, 캐시 재사용)은 몫을 소비하지 않는다.
                    // 부분 소멸 → 원자 rebase(같은 ID). 근거 전멸 → 세션 종료 + **정제된 현재
                    // live 신호**만으로 fresh episode 분리(old→fresh 단일 대입, 과거 누적값 부활
                    // 금지) — 낡은 정체성·통보 메타데이터를 승계하지 않는다.
                    var forceExpiredThisTick = false
                    var rebasedThisTick = false
                    var splitThisTick = false
                    renewalToken?.let { token ->
                        val windowExpired = clock() - token.renewedAtMs > RENEWAL_VALIDATION_WINDOW_MS
                        val sessionGone = sessionTracker.sessionState.value?.id != token.sessionId
                        if (windowExpired || sessionGone) {
                            renewalToken = null
                        } else {
                            var confirmed = token.confirmedBasis
                            val remaining = token.remaining.toMutableMap()
                            val lost = mutableSetOf<RiskSignal>()
                            for ((source, portion) in token.remaining) {
                                if (source !in advancedSources) continue
                                // 판정은 transition과 동일 입력 기준 — CALL은 snooze 필터 적용값.
                                val freshValue =
                                    if (source == SourceId.CALL) filteredCallSignals.toSet()
                                    else freshBySource.getValue(source).toSet()
                                confirmed = confirmed + (portion intersect freshValue)
                                lost += portion - freshValue
                                remaining.remove(source) // 이 source 몫은 실측으로 판정 완료
                            }
                            renewalToken = when {
                                lost.isEmpty() ->
                                    if (remaining.isEmpty()) null
                                    else token.copy(confirmedBasis = confirmed, remaining = remaining)

                                confirmed.isNotEmpty() || remaining.isNotEmpty() -> {
                                    // 근거 일부 생존 — 소멸 몫만 걷어내고 같은 ID 유지
                                    val rebased = sessionTracker.rebaseRenewedSession(token.sessionId, lost)
                                    if (rebased == null) {
                                        forceExpiredThisTick = true
                                        null
                                    } else {
                                        rebasedThisTick = true
                                        if (remaining.isEmpty()) null
                                        else token.copy(confirmedBasis = confirmed, remaining = remaining)
                                    }
                                }

                                else -> {
                                    // renewal 근거 전멸 — transition과 동일한 입력(snooze 필터 적용,
                                    // 정제 완료)의 live 신호만으로 새 episode를 원자 결정한다.
                                    val liveSignals = (filteredCallSignals + nonCallSignals).toSet()
                                    val split =
                                        sessionTracker.splitAfterRenewalBasisDied(token.sessionId, liveSignals)
                                    if (split == null) forceExpiredThisTick = true else splitThisTick = true
                                    null
                                }
                            }
                        }
                    }

                    // ── transition: 만료/renewal/갱신을 단일 시각으로 결정 ─────
                    // expectedEpoch를 tracker 락 안에서 원자 비교 — 사용자 안전 확인(reset)과
                    // 완전 직렬화되며, reset 이전에 생산된(queued) tick도 여기서 거부된다.
                    val outcome = sessionTracker.transition(
                        filteredCallSignals,
                        nonCallSignals,
                        expectedEpoch = epochAtTickStart,
                    )
                    if (outcome.aborted) {
                        Log.d(TAG, "tick predates user reset — session transition skipped")
                        previousBankingForeground = bankingForeground
                        return@collect
                    }
                    val session = outcome.session ?: run {
                        clearInactiveSessionPresentation(
                            when {
                                outcome.expiredPrevious -> "session TTL expired"
                                forceExpiredThisTick -> "renewal invalidated by fresh signals"
                                else -> "no active session"
                            },
                        )
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // fresh episode(만료 후 새 ID) 또는 renewal 무효화/분리 직후 새 세션:
                    // 구 세션 presentation을 새 세션 효과(push/notify)보다 먼저 정리한다.
                    if (outcome.expiredPrevious || forceExpiredThisTick || splitThisTick) {
                        clearInactiveSessionPresentation("previous session ended — fresh episode")
                    }
                    // 모든 TTL renewal(스냅샷·신호 경로 공통)에 검증 토큰을 발급한다.
                    // 이번 tick에서 sequence가 전진한 source의 몫은 즉시 confirmed(실측 확인) —
                    // 직접 재방출로 renewal을 정당화한 source를 뒤늦게 stale로 오판하지 않는다.
                    // 전진하지 않은(cached/latched) source의 몫만 pending으로 남긴다.
                    if (outcome.renewed) {
                        val basis = outcome.renewalBasis.orEmpty()
                        var confirmed = emptySet<RiskSignal>()
                        val remaining = buildMap<SourceId, Set<RiskSignal>> {
                            for (source in listOf(
                                SourceId.CALL, SourceId.APP_USAGE, SourceId.APP_INSTALL, SourceId.DEVICE_ENV,
                            )) {
                                val portion = basis intersect freshBySource.getValue(source).toSet()
                                if (portion.isEmpty()) continue
                                if (source in advancedSources) {
                                    confirmed = confirmed + portion
                                } else {
                                    put(source, portion)
                                }
                            }
                        }
                        renewalToken = if (remaining.isEmpty()) {
                            null // 근거 전부가 이번 tick 실측 — 검증할 pending 없음
                        } else {
                            RenewalToken(
                                sessionId = session.id,
                                confirmedBasis = confirmed,
                                remaining = remaining,
                                renewedAtMs = clock(),
                            )
                        }
                    }

                    // ── 2단계: 세션 변경 감지 → cooldownConsumedSessionId 자동 clear ──
                    if (cooldownConsumedSessionId != null && cooldownConsumedSessionId != session.id) {
                        Log.d(TAG, "cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})")
                        cooldownConsumedSessionId = null
                    }

                    val score = evaluator.evaluate(session.accumulatedSignals.toList())
                    val alertState = alertStateResolver.resolve(session)
                    Log.d(TAG, "session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}")

                    // ── renewal/rebase downgrade 정리 + 통보 상한 재무장 ────────
                    // renewal은 notified*를 승계하므로(재발동 금지), 이전에 INTERRUPT+ 표시가
                    // 있었는데 새 상태가 그 미만이면 팝업/currentEvent만 걷어낸다.
                    // 뱅킹 쿨다운은 세션 지속의 friction이므로 renewal에서 끊지 않는다.
                    // 검증 토큰의 부분 소멸 rebase로 내려간 경우도 동일하게 처리한다.
                    if (outcome.renewed || rebasedThisTick) {
                        val inheritedAlertOrdinal = session.notifiedAlertState?.ordinal ?: -1
                        val inheritedLevelOrdinal = session.notifiedLevel?.ordinal ?: -1
                        if (alertState.ordinal < AlertState.INTERRUPT.ordinal &&
                            inheritedAlertOrdinal >= AlertState.INTERRUPT.ordinal
                        ) {
                            Log.d(TAG, "renewal downgraded to $alertState — dismiss stale popup/current event")
                            overlayManager.dismiss()
                            eventSink.clearCurrentRiskEvent()
                        }
                        // 승계된 통보 상한이 현 상태보다 높으면 현 상태로 클램프 — 이번 tick에는
                        // 아무것도 발화하지 않지만(동치), 이후의 진짜 재상승이 다시 알림/이력을
                        // 낼 수 있게 escalation 감지를 재무장한다.
                        if (inheritedAlertOrdinal > alertState.ordinal) {
                            sessionTracker.markAlertStateNotified(alertState)
                        }
                        if (inheritedLevelOrdinal > score.level.ordinal) {
                            sessionTracker.markNotified(score.level)
                        }
                    }

                    if (alertState == AlertState.OBSERVE) {
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── end-call suppression 중 UI 액션 전체 스킵 ──────────
                    if (overlayManager.isEndCallSuppressed()) {
                        Log.d(TAG, "suppression active, skip popup/notification/cooldown")
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── trigger 라이프사이클 동기화 ────────────────────────────
                    // rawTickSignals는 필터링된 call signal을 사용한다 — snooze로 걸러진 PASSIVE 신호는
                    // 세션에도 반영되지 않았으므로 sync 입력에서도 빠져야 일관된다.
                    // (CALL_DERIVED_SIGNALS는 모두 PASSIVE라 notifiedActiveThreats에 들어갈 일이 없어
                    //  실무상 영향은 없지만 의미상 raw 대신 filtered를 사용한다.)
                    val rawTickSignals: Set<RiskSignal> =
                        (filteredCallSignals + nonCallSignals).toSet()
                    var syncedSession = sessionTracker.syncActiveThreats(rawTickSignals) ?: session

                    val triggers = syncedSession.accumulatedSignals.filter { it.category == SignalCategory.TRIGGER }.toSet()
                    val activeTriggers = rawTickSignals.filter { it.category == SignalCategory.TRIGGER }.toSet()

                    // snooze가 살아남았다면 (same call + no upgrade) 이번 tick에서 popup/notification/cooldown을 모두 스킵.
                    val snoozeActive = sessionTracker.isSnoozeActive()
                    if (snoozeActive) {
                        Log.d(TAG, "snooze still active — skip popup/notification/cooldown this tick (callId=$liveCallId)")
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // 쿨다운 표시 중일 때만 활성 trigger를 자동 통보 처리한다.
                    // (뱅킹 포그라운드 단독은 쿨다운이 닫힌 상태일 수 있어 여기서 미리 마킹하면
                    //  이후 CRITICAL 에스컬레이션 시 새 trigger 팝업이 누락된다 — 은행 ARS 발신 감지 누락 방지.)
                    if (cooldownManager.isShowing() && activeTriggers.isNotEmpty()) {
                        val merged = syncedSession.notifiedActiveThreats + activeTriggers
                        if (merged != syncedSession.notifiedActiveThreats) {
                            sessionTracker.markActiveThreatsNotified(merged)
                            syncedSession = syncedSession.copy(notifiedActiveThreats = merged)
                        }
                    }

                    // ── epoch 재검증 (b): 쿨다운 발동 직전 ─────────────────────
                    if (userResetIntervened(epochAtTickStart, "cooldown stage")) {
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── 4단계: 쿨다운 발동 여부를 팝업보다 먼저 판정 ──────────
                    // 같은 tick에서 쿨다운이 발동하면 팝업은 생략한다(modal surface 1개 원칙).
                    var cooldownFiredThisTick = false
                    val isCallActive = liveCallId != null

                    val bankingJustOpened = bankingForeground && !previousBankingForeground
                    if (bankingJustOpened && alertState.ordinal >= AlertState.GUARDED.ordinal
                        && !isCooldownGhostTransition()
                    ) {
                        // 동기 ghost 조회(latestBankingForegroundEventTimestamp)가 도는 동안
                        // 사용자 확인이 끼어들 수 있다 — 재검증 (b)는 조회보다 앞서 실행되므로
                        // 이 창을 못 본다. 외부 효과(쿨다운) 직전 재확인 (라운드 8 지적 7).
                        if (userResetIntervened(epochAtTickStart, "post ghost query")) {
                            previousBankingForeground = bankingForeground
                            return@collect
                        }
                        val isCallBased = session.accumulatedSignals.any { it in AlertStateResolver.CALL_SIGNALS }
                        if (!isCallBased) {
                            Log.d(TAG, "뱅킹 쿨다운 생략: call-based 세션 아님")
                        } else if (cooldownConsumedSessionId == session.id) {
                            Log.d(TAG, "뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)")
                        } else {
                            val reason = buildCooldownReason(session.accumulatedSignals)
                            cooldownManager.triggerIfNotActive(score.level, reason, isCallActive)
                            overlayManager.ensureCriticalOnTop()
                            cooldownConsumedSessionId = session.id
                            Log.d(TAG, "cooldownConsumedSessionId set=${session.id}: banking cooldown fired (level=${score.level}, isCallActive=$isCallActive)")
                            sessionTracker.markActiveThreatsNotified(triggers)
                            cooldownFiredThisTick = true
                            Log.d(TAG, "뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason")
                        }
                    }

                    // ── telebanking cooldown: CRITICAL 세션에서 텔레뱅킹 발신 시 쿨다운 ──
                    if (!cooldownFiredThisTick &&
                        alertState == AlertState.CRITICAL &&
                        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in filteredCallSignals &&
                        !cooldownManager.isShowing()
                    ) {
                        if (cooldownConsumedSessionId == session.id) {
                            Log.d(TAG, "텔레뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id})")
                        } else {
                            cooldownManager.triggerIfNotActive(
                                score.level,
                                "은행 ARS 전화가 감지되었습니다.\n잠시 멈추고 다시 생각해 보세요.",
                                isCallActive,
                            )
                            overlayManager.ensureCriticalOnTop()
                            cooldownConsumedSessionId = session.id
                            Log.d(TAG, "cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})")
                            sessionTracker.markActiveThreatsNotified(triggers)
                            cooldownFiredThisTick = true
                            Log.d(TAG, "텔레뱅킹 쿨다운 발동: level=${score.level}")
                        }
                    }

                    // ── epoch 재검증 (c): notification/event push/popup 직전 ────
                    if (userResetIntervened(epochAtTickStart, "escalation effects")) {
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── notification: AlertState 전이 또는 RiskLevel 상승 시 ────
                    // 팝업은 쿨다운이 같은 tick에 발동했으면 생략(4단계). notification은 유지.
                    var popupShownThisTick = false
                    val prevAlertOrdinal = session.notifiedAlertState?.ordinal ?: -1
                    val prevLevelOrdinal = session.notifiedLevel?.ordinal ?: -1
                    val alertEscalated = alertState.ordinal > prevAlertOrdinal
                    val levelEscalated = score.level.ordinal > prevLevelOrdinal
                    if (alertEscalated || levelEscalated) {
                        val event = eventFactory.create(score)
                        // currentEvent 승격은 INTERRUPT+ 전용 — GUARDED 세션은 이력·notification만.
                        // (승격하면 홈이 WARNING으로 표시되고 Warning 화면 중립 헤더가 죽는다.)
                        if (alertState.ordinal >= AlertState.INTERRUPT.ordinal) {
                            eventSink.pushRiskEvent(event)
                        } else {
                            eventSink.recordRiskEvent(event)
                        }
                        // suspend(Room 쓰기) 재개 후 재검증 — 사용자 clear "이후"에 push가
                        // currentEvent를 되살렸을 수 있으므로 회수하고 잔여 효과를 중단한다.
                        if (userResetIntervened(epochAtTickStart, "escalation post-push")) {
                            eventSink.clearCurrentRiskEvent()
                            previousBankingForeground = bankingForeground
                            return@collect
                        }
                        if (alertEscalated) sessionTracker.markAlertStateNotified(alertState)
                        sessionTracker.markNotified(score.level)
                        Log.d(TAG, "notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}")

                        notificationManager.notify(event)

                        if (alertState.ordinal >= AlertState.INTERRUPT.ordinal &&
                            !cooldownManager.isShowing() && !cooldownFiredThisTick
                        ) {
                            val nowMs = clock()
                            if (shouldSuppressS2RecRefire(s2RecRefireState, rawTickSignals, nowMs)) {
                                Log.d(TAG, "popup suppressed by S2 REC-REFIRE debounce (escalation path) — rawTick=$rawTickSignals, snapshot=${s2RecRefireState.snapshot}")
                            } else {
                                val guardian = firstGuardian()
                                // suspend(DataStore 첫 읽기) 재개 후 재검증 — 사용자 확인 이후
                                // 팝업이 다시 떠서는 안 된다.
                                if (userResetIntervened(epochAtTickStart, "escalation popup show")) {
                                    eventSink.clearCurrentRiskEvent()
                                    previousBankingForeground = bankingForeground
                                    return@collect
                                }
                                overlayManager.show(event, guardian)
                                s2RecRefireState = s2RecRefireStateAfterFiring(rawTickSignals, nowMs)
                                sessionTracker.markActiveThreatsNotified(triggers)
                                popupShownThisTick = true
                                Log.d(TAG, "popup shown on state transition → $alertState (s2Snapshot=${s2RecRefireState.snapshot})")
                            }
                        } else if (cooldownFiredThisTick && alertState.ordinal >= AlertState.INTERRUPT.ordinal) {
                            Log.d(TAG, "popup suppressed: cooldown fired this tick")
                        }
                    }

                    // ── epoch 재검증 (d): 새 trigger 재알림 효과 직전 ───────────
                    // (c)와의 사이에 suspend가 없으면 실질적으로 도달 불가한 창이지만,
                    // escalation 블록이 suspend를 포함하므로 defense-in-depth로 유지한다.
                    if (userResetIntervened(epochAtTickStart, "new-trigger effects")) {
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── 새 trigger 재알림: 에스컬레이션 없이도 새 trigger 감지 시 팝업 ──
                    if (!popupShownThisTick && !cooldownFiredThisTick &&
                        alertState.ordinal >= AlertState.INTERRUPT.ordinal && activeTriggers.isNotEmpty()
                    ) {
                        val newTriggers = activeTriggers - syncedSession.notifiedActiveThreats
                        if (newTriggers.isNotEmpty() && !cooldownManager.isShowing()) {
                            val nowMs = clock()
                            if (shouldSuppressS2RecRefire(s2RecRefireState, rawTickSignals, nowMs)) {
                                Log.d(TAG, "popup suppressed by S2 REC-REFIRE debounce (new-trigger path) — new=$newTriggers, snapshot=${s2RecRefireState.snapshot}")
                            } else {
                                val event = eventFactory.create(score, triggerSignals = newTriggers)
                                eventSink.pushRiskEvent(event)
                                // suspend(Room 쓰기) 재개 후 재검증 — push가 사용자 clear 이후
                                // currentEvent를 되살렸으면 회수하고 중단.
                                if (userResetIntervened(epochAtTickStart, "new-trigger post-push")) {
                                    eventSink.clearCurrentRiskEvent()
                                    previousBankingForeground = bankingForeground
                                    return@collect
                                }
                                notificationManager.notify(event)
                                val guardian = firstGuardian()
                                // suspend(DataStore) 재개 후 재검증 — 사용자 확인 이후 팝업 금지.
                                if (userResetIntervened(epochAtTickStart, "new-trigger popup show")) {
                                    eventSink.clearCurrentRiskEvent()
                                    previousBankingForeground = bankingForeground
                                    return@collect
                                }
                                overlayManager.show(event, guardian)
                                s2RecRefireState = s2RecRefireStateAfterFiring(rawTickSignals, nowMs)
                                sessionTracker.markActiveThreatsNotified(syncedSession.notifiedActiveThreats + newTriggers)
                                Log.d(TAG, "새 trigger 팝업: new=$newTriggers (s2Snapshot=${s2RecRefireState.snapshot})")
                            }
                        }
                    }

                    previousBankingForeground = bankingForeground
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _anchorHotState.value = false
        s2RecRefireState = S2RecRefireDebounceState()
    }

    /**
     * tick 시작 시 캡처한 [RiskSessionTracker.userResetEpoch]가 이후 바뀌었는지 확인한다.
     * 바뀌었다면 사용자가 tick 도중 "안전 확인"을 실행한 것 — 사용자 결정이 항상 우선하므로
     * 세션 재생성과 잔여 외부 효과(eventSink/notification/overlay/cooldown)를 중단해야 한다.
     */
    private fun userResetIntervened(epochAtTickStart: Long, stage: String): Boolean {
        val intervened = sessionTracker.userResetEpoch != epochAtTickStart
        if (intervened) {
            Log.d(TAG, "user reset during tick — abort $stage")
        }
        return intervened
    }

    private fun clearInactiveSessionPresentation(reason: String) {
        Log.d(TAG, "$reason — dismiss overlay/cooldown and clear current event")
        overlayManager.dismiss()
        cooldownManager.dismissIfShowing()
        eventSink.clearCurrentRiskEvent()
        if (cooldownConsumedSessionId != null) {
            Log.d(TAG, "cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)")
            cooldownConsumedSessionId = null
        }
    }

    private fun refreshAnchorHotMirror() {
        val hot = callMonitor.isTelebankingAnchorHot()
        if (_anchorHotState.value != hot) {
            Log.d(TAG, "anchorHotState mirror → $hot")
            _anchorHotState.value = hot
        }
    }

    /**
     * 명시적 사용자 액션(예: "안전 확인했어요") 후 mirror를 즉시 동기화.
     * 내부적으로는 ticker/combine emit 경로와 동일한 [refreshAnchorHotMirror]를 호출하지만,
     * 의미상 "평시 polling"이 아닌 "사용자 액션 직후 즉시 반영"임을 구분하기 위해 별도 메서드로 노출한다.
     */
    override fun refreshAnchorHotNow() {
        refreshAnchorHotMirror()
    }

    /**
     * 쿨다운 오버레이(OPAQUE)로 인한 거짓 banking foreground 전이인지 판별한다.
     *
     * OPAQUE 오버레이가 화면을 덮으면 UsageStats가 뱅킹 앱을 비포그라운드로 감지하고,
     * dismiss 후 다시 포그라운드로 감지하여 false→true 잔상 전이가 발생한다.
     *
     * 판별 방법:
     * 1. UsageEvents.Event의 MOVE_TO_FOREGROUND 타임스탬프가
     *    쿨다운 활성 구간 [showedAt, dismissedAt] 내에 있으면 → 거짓 전이
     * 2. dismissedAt 이후이면 → 진짜 재진입
     * 3. queryEvents 실패 시 fallback: dismissedAt + 쿨다운시간 이내의 첫 전이 1회만 무시
     */
    private fun isCooldownGhostTransition(): Boolean {
        val showedAt = cooldownManager.showedAtMillis
        val dismissedAt = cooldownManager.dismissedAtMillis
        if (showedAt == 0L || dismissedAt == 0L || dismissedAt <= showedAt) return false

        val eventTs = appUsageMonitor.latestBankingForegroundEventTimestamp()
        if (eventTs != null) {
            val isGhost = eventTs in showedAt..dismissedAt
            Log.d(TAG, "ghost check: eventTs=$eventTs, showedAt=$showedAt, dismissedAt=$dismissedAt → ghost=$isGhost")
            return isGhost
        }

        // fallback: queryEvents 실패 시 — dismissedAt + 쿨다운표시시간 이내 1회 무시
        val fallbackWindow = cooldownManager.lastCountdownSec * 1_000L
        val now = clock()
        val isFallbackGhost = now - dismissedAt < fallbackWindow
        Log.d(TAG, "ghost check fallback: now=$now, dismissedAt=$dismissedAt, window=${fallbackWindow}ms → ghost=$isFallbackGhost")
        return isFallbackGhost
    }

    /**
     * 현재 세션 신호에 기반한 쿨다운 이유 문구 생성.
     */
    private fun buildCooldownReason(signals: Set<RiskSignal>): String = when {
        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in signals ->
            "위험 신호가 감지된 뒤 은행 전화를 시도했습니다.\n지시를 받고 하는 것이라면 즉시 중단하세요."

        RiskSignal.REMOTE_CONTROL_APP_OPENED in signals ->
            "원격제어 앱이 실행된 상태입니다.\n지금 송금이나 인증을 진행하지 마세요."

        RiskSignal.REPEATED_CALL_THEN_LONG_TALK in signals ||
                RiskSignal.REPEATED_UNKNOWN_CALLER in signals ->
            "확인되지 않은 번호에서 반복 전화가 감지되었습니다.\n송금이나 인증 전에 가족에게 먼저 확인하세요."

        RiskSignal.LONG_CALL_DURATION in signals ->
            "방금 낯선 번호와 오래 통화했습니다.\n지금 바로 송금이나 인증을 진행하지 마세요."

        else ->
            "의심스러운 활동이 감지된 상태입니다.\n잠시 멈추고 확인해 주세요."
    }
}

private sealed interface CoordinatorEvent {
    data object MaintenanceTick : CoordinatorEvent
}

private enum class SourceId { CALL, APP_USAGE, BANKING, APP_INSTALL, DEVICE_ENV }

/**
 * upstream 방출의 coordinator 내부 표현 — 같은 값의 재방출도 식별하고([sequence]),
 * "사용자 reset 이전에 생산된 데이터"를 tick 단위로 거부할 수 있게 한다([producedAtEpoch]).
 * epoch는 monitor가 생산 경계(조회·분류 전)에서 스탬프한 [Produced]를 그대로 승계하며
 * (A안, 2026-07-16), sequence만 coordinator-local로 부여한다.
 */
private data class SourceEmission<T>(
    val sourceId: SourceId,
    val sequence: Long,
    val producedAtEpoch: Long,
    val value: T,
)

private data class CombinedSignals(
    val call: SourceEmission<List<RiskSignal>>,
    val app: SourceEmission<List<RiskSignal>>,
    val banking: SourceEmission<Boolean>,
    val install: SourceEmission<List<RiskSignal>>,
    val deviceEnv: SourceEmission<List<RiskSignal>>,
) : CoordinatorEvent {
    /** 신호를 나르는 source의 방출. BANKING은 신호원이 아니므로 null. */
    fun signalEmissionOf(source: SourceId): SourceEmission<List<RiskSignal>>? = when (source) {
        SourceId.CALL -> call
        SourceId.APP_USAGE -> app
        SourceId.APP_INSTALL -> install
        SourceId.DEVICE_ENV -> deviceEnv
        SourceId.BANKING -> null
    }

    fun sequenceOf(source: SourceId): Long = when (source) {
        SourceId.CALL -> call.sequence
        SourceId.APP_USAGE -> app.sequence
        SourceId.BANKING -> banking.sequence
        SourceId.APP_INSTALL -> install.sequence
        SourceId.DEVICE_ENV -> deviceEnv.sequence
    }
}

/**
 * TTL renewal 1건의 검증 토큰.
 * renewal tick에서 sequence가 전진한 source의 몫은 즉시 [confirmedBasis]로 들어가고(실측 확인),
 * 전진하지 않은(cached/latched) source의 몫만 [remaining]으로 남아 이후 재방출로 판정된다.
 * 판정 중 근거가 **전부** 소멸하면(confirmed·remaining 모두 空) renewal은 무효 — 세션을 종료하고
 * 잔존 신호는 fresh episode(새 ID)로 분리한다.
 */
private data class RenewalToken(
    val sessionId: String,
    /** 실측으로 생존이 확인된 renewal 근거. */
    val confirmedBasis: Set<RiskSignal>,
    /** 아직 해당 source의 실측 재방출로 판정되지 않은 renewal 근거 몫. */
    val remaining: Map<SourceId, Set<RiskSignal>>,
    val renewedAtMs: Long,
)
