package com.example.seniorshield.monitoring.orchestrator

import android.util.Log
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
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Coordinator"

/** snooze 자동 만료: 설정 후 15분 경과 시 해제. */
private const val SNOOZE_TTL_MS = 15 * 60 * 1000L

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

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null
    @Volatile private var previousBankingForeground = false

    /**
     * 이번 세션에서 쿨다운이 이미 소비된 세션 ID. 같은 session.id에서는 재발동 금지.
     * 세션 변경(id 다름) 또는 세션 소멸(null 반환) 시 tick 초입에서 자동 clear.
     */
    @Volatile private var cooldownConsumedSessionId: String? = null

    private suspend fun firstGuardian(): Guardian? =
        guardianRepository.observeGuardians().first().firstOrNull()

    override fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "coordinator started")
        job = scope.launch {
            combine(
                callMonitor.observeCallSignals(),
                appUsageMonitor.observeAppUsageSignals(),
                appUsageMonitor.observeBankingAppForeground(),
                appInstallMonitor.observeInstallSignals(),
                deviceEnvMonitor.observeDeviceEnvironmentSignals(),
            ) { callSignals, appSignals, bankingForeground, installSignals, deviceEnvSignals ->
                CombinedSignals(callSignals, appSignals, bankingForeground, installSignals, deviceEnvSignals)
            }
                .collect { (callSignals, appSignals, bankingForeground, installSignals, deviceEnvSignals) ->
                    Log.d(TAG, "combine fired — rawCall=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals")

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
                        val now = System.currentTimeMillis()
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

                    // ── update: filter 적용된 신호로 세션 평가 ───────────────
                    val session = sessionTracker.update(filteredCallSignals, nonCallSignals) ?: run {
                        Log.d(TAG, "no active session — dismiss overlay/cooldown if showing")
                        overlayManager.dismiss()
                        cooldownManager.dismissIfShowing()
                        eventSink.clearCurrentRiskEvent()
                        if (cooldownConsumedSessionId != null) {
                            Log.d(TAG, "cooldownConsumedSessionId cleared: session disappeared (was=$cooldownConsumedSessionId)")
                            cooldownConsumedSessionId = null
                        }
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    // ── 2단계: 세션 변경 감지 → cooldownConsumedSessionId 자동 clear ──
                    if (cooldownConsumedSessionId != null && cooldownConsumedSessionId != session.id) {
                        Log.d(TAG, "cooldownConsumedSessionId cleared: session changed (was=$cooldownConsumedSessionId, now=${session.id})")
                        cooldownConsumedSessionId = null
                    }

                    val score = evaluator.evaluate(session.accumulatedSignals.toList())
                    val alertState = alertStateResolver.resolve(session)
                    Log.d(TAG, "session score: total=${score.total}, level=${score.level}, alertState=$alertState, sessionId=${session.id}")

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

                    // 팝업이 표시될 수 없는 상태(뱅킹 포그라운드 또는 쿨다운 표시 중)에서는
                    // 쿨다운/뱅킹 UI가 경고를 담당하므로 현재 활성 trigger를 자동 통보 처리한다.
                    if ((bankingForeground || cooldownManager.isShowing()) && activeTriggers.isNotEmpty()) {
                        val merged = syncedSession.notifiedActiveThreats + activeTriggers
                        if (merged != syncedSession.notifiedActiveThreats) {
                            sessionTracker.markActiveThreatsNotified(merged)
                            syncedSession = syncedSession.copy(notifiedActiveThreats = merged)
                        }
                    }

                    // ── 4단계: 쿨다운 발동 여부를 팝업보다 먼저 판정 ──────────
                    // 같은 tick에서 쿨다운이 발동하면 팝업은 생략한다(modal surface 1개 원칙).
                    var cooldownFiredThisTick = false
                    val isCallActive = liveCallId != null

                    val bankingJustOpened = bankingForeground && !previousBankingForeground
                    if (bankingJustOpened && alertState.ordinal >= AlertState.GUARDED.ordinal
                        && !isCooldownGhostTransition()
                    ) {
                        val isCallBased = session.accumulatedSignals.any { it in AlertStateResolver.CALL_SIGNALS }
                        if (!isCallBased) {
                            Log.d(TAG, "뱅킹 쿨다운 생략: call-based 세션 아님")
                        } else if (cooldownConsumedSessionId == session.id) {
                            Log.d(TAG, "뱅킹 쿨다운 생략: 세션당 1회 정책 (sessionId=${session.id}, alertState=$alertState)")
                        } else {
                            val reason = buildCooldownReason(session.accumulatedSignals)
                            cooldownManager.triggerIfNotActive(score.level, reason, isCallActive)
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
                            cooldownConsumedSessionId = session.id
                            Log.d(TAG, "cooldownConsumedSessionId set=${session.id}: telebanking cooldown fired (level=${score.level})")
                            sessionTracker.markActiveThreatsNotified(triggers)
                            cooldownFiredThisTick = true
                            Log.d(TAG, "텔레뱅킹 쿨다운 발동: level=${score.level}")
                        }
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
                        eventSink.pushRiskEvent(event)
                        if (alertEscalated) sessionTracker.markAlertStateNotified(alertState)
                        sessionTracker.markNotified(score.level)
                        Log.d(TAG, "notification escalation: alertState=${session.notifiedAlertState}→$alertState, level=${session.notifiedLevel}→${score.level}")

                        notificationManager.notify(event)

                        if (alertState.ordinal >= AlertState.INTERRUPT.ordinal &&
                            !bankingForeground && !cooldownManager.isShowing() && !cooldownFiredThisTick
                        ) {
                            overlayManager.show(event, firstGuardian())
                            sessionTracker.markActiveThreatsNotified(triggers)
                            popupShownThisTick = true
                            Log.d(TAG, "popup shown on state transition → $alertState")
                        } else if (cooldownFiredThisTick && alertState.ordinal >= AlertState.INTERRUPT.ordinal) {
                            Log.d(TAG, "popup suppressed: cooldown fired this tick")
                        }
                    }

                    // ── 새 trigger 재알림: 에스컬레이션 없이도 새 trigger 감지 시 팝업 ──
                    if (!popupShownThisTick && !cooldownFiredThisTick &&
                        alertState.ordinal >= AlertState.INTERRUPT.ordinal && activeTriggers.isNotEmpty()
                    ) {
                        val newTriggers = activeTriggers - syncedSession.notifiedActiveThreats
                        if (newTriggers.isNotEmpty() && !bankingForeground && !cooldownManager.isShowing()) {
                            val event = eventFactory.create(score, triggerSignals = newTriggers)
                            eventSink.pushRiskEvent(event)
                            notificationManager.notify(event)
                            overlayManager.show(event, firstGuardian())
                            sessionTracker.markActiveThreatsNotified(syncedSession.notifiedActiveThreats + newTriggers)
                            Log.d(TAG, "새 trigger 팝업: new=$newTriggers")
                        }
                    }

                    previousBankingForeground = bankingForeground
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
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
        val now = System.currentTimeMillis()
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

private data class CombinedSignals(
    val callSignals: List<RiskSignal>,
    val appSignals: List<RiskSignal>,
    val bankingForeground: Boolean,
    val installSignals: List<RiskSignal>,
    val deviceEnvSignals: List<RiskSignal>,
)
