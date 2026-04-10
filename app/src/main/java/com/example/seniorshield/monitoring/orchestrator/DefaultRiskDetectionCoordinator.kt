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
                    Log.d(TAG, "combine fired — call=$callSignals, app=$appSignals, banking=$bankingForeground, install=$installSignals, deviceEnv=$deviceEnvSignals")

                    // ── end-call suppression: IDLE 감지 시 안정화 타이머 시작 ──
                    if (overlayManager.isEndCallSuppressed() && callSignals.isEmpty()) {
                        overlayManager.scheduleSuppressionRelease()
                        Log.d(TAG, "call became IDLE during suppression, stabilization scheduled")
                    }

                    val session = sessionTracker.update(callSignals, appSignals + installSignals + deviceEnvSignals) ?: run {
                        Log.d(TAG, "no active session — dismiss overlay/cooldown if showing")
                        overlayManager.dismiss()
                        cooldownManager.dismissIfShowing()
                        eventSink.clearCurrentRiskEvent()
                        previousBankingForeground = bankingForeground
                        return@collect
                    }

                    val score = evaluator.evaluate(session.accumulatedSignals.toList())
                    val alertState = alertStateResolver.resolve(session)
                    Log.d(TAG, "session score: total=${score.total}, level=${score.level}, alertState=$alertState")

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
                    // 현재 tick의 raw signal에 없는 notified trigger를 제거한다.
                    // RealAppUsageRiskMonitor의 30초 window가 debounce 역할을 하므로
                    // "tick signal에 없음" = "30초 이상 해당 앱 포그라운드 아님" = "trigger 떠남".
                    // 이후 재감지 시 new trigger로 팝업/쿨다운 재발동이 가능해진다.
                    val rawTickSignals: Set<RiskSignal> =
                        (callSignals + appSignals + installSignals + deviceEnvSignals).toSet()
                    var syncedSession = sessionTracker.syncActiveThreats(rawTickSignals) ?: session

                    // 세션 누적 trigger(score/reason 계산용)과 현재 tick의 활성 trigger(팝업 판단용)를 분리한다.
                    // accumulatedSignals는 session TTL 만료까지 누적되므로 팝업 판단에 쓰면 "신호가 사라져도
                    // 계속 new trigger로 인식"되어 뒤늦은 팝업이 반복 발생한다.
                    val triggers = syncedSession.accumulatedSignals.filter { it.category == SignalCategory.TRIGGER }.toSet()
                    val activeTriggers = rawTickSignals.filter { it.category == SignalCategory.TRIGGER }.toSet()

                    // 팝업이 표시될 수 없는 상태(뱅킹 포그라운드 또는 쿨다운 표시 중)에서는
                    // 쿨다운/뱅킹 UI가 경고를 담당하므로 현재 활성 trigger를 자동 통보 처리한다.
                    // 이를 통해 쿨다운 종료 후 "뱅킹 포그라운드 중 추가된 trigger가 뒤늦은 팝업으로 발현"되는
                    // 버그를 방지한다.
                    if ((bankingForeground || cooldownManager.isShowing()) && activeTriggers.isNotEmpty()) {
                        val merged = syncedSession.notifiedActiveThreats + activeTriggers
                        if (merged != syncedSession.notifiedActiveThreats) {
                            sessionTracker.markActiveThreatsNotified(merged)
                            syncedSession = syncedSession.copy(notifiedActiveThreats = merged)
                        }
                    }

                    // ── notification: AlertState 전이 또는 RiskLevel 상승 시 ────
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

                        // popup: INTERRUPT/CRITICAL 전이 시 — 뱅킹 미포그라운드 + 쿨다운 미표시
                        if (alertState.ordinal >= AlertState.INTERRUPT.ordinal &&
                            !bankingForeground && !cooldownManager.isShowing()
                        ) {
                            overlayManager.show(event, firstGuardian())
                            sessionTracker.markActiveThreatsNotified(triggers)
                            popupShownThisTick = true
                            Log.d(TAG, "popup shown on state transition → $alertState")
                        }
                    }

                    // ── 새 trigger 재알림: 에스컬레이션 없이도 새 trigger 감지 시 팝업 ──
                    // popupShownThisTick 가드: 같은 사이클에서 에스컬레이션 팝업과 중복 방지.
                    // activeTriggers 기준: 현재 tick에 실제로 raw signal에 존재하는 trigger만.
                    // accumulatedSignals(triggers) 기준으로 하면 신호가 사라져도 "new trigger"로
                    // 인식되어 뒤늦은 팝업이 반복 발생한다.
                    // notifiedActiveThreats는 syncedSession 기준(사라진 trigger는 사전 제거됨).
                    // 같은 trigger가 종료 후 재감지되면 newTriggers에 다시 포함된다.
                    if (!popupShownThisTick &&
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

                    // ── banking cooldown: call-based 세션에서만 발동 ──────────
                    val bankingJustOpened = bankingForeground && !previousBankingForeground
                    if (bankingJustOpened && alertState.ordinal >= AlertState.GUARDED.ordinal
                        && !isCooldownGhostTransition()
                    ) {
                        val isCallBased = session.accumulatedSignals.any { it in AlertStateResolver.CALL_SIGNALS }
                        if (isCallBased) {
                            // CRITICAL: 금융앱 재진입마다 쿨다운 재발동 (dedupe 우회)
                            // GUARDED/INTERRUPT: 새 신호 없이 banking 재진입만으로 반복하지 않음
                            val consumed = session.cooldownConsumedAt
                            val hasNewSignals = consumed == null || session.lastSignalAt > consumed
                            if (hasNewSignals || alertState == AlertState.CRITICAL) {
                                val reason = buildCooldownReason(session.accumulatedSignals)
                                val isCallActive = callSignals.isNotEmpty()
                                cooldownManager.triggerIfNotActive(score.level, reason, isCallActive)
                                sessionTracker.markCooldownConsumed()
                                // 쿨다운이 팝업을 대체하므로 trigger도 통보 완료 처리.
                                // 이렇게 하지 않으면 뱅킹 앱 종료 시 미통보 trigger로 팝업이 뒤늦게 발생.
                                sessionTracker.markActiveThreatsNotified(triggers)
                                Log.d(TAG, "뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, isCallActive=$isCallActive, reason=$reason")
                            } else {
                                Log.d(TAG, "뱅킹 쿨다운 생략: 동일 세션 내 재발동 (새 신호 없음)")
                            }
                        } else {
                            Log.d(TAG, "뱅킹 쿨다운 생략: call-based 세션 아님")
                        }
                    }

                    // ── telebanking cooldown: CRITICAL 세션에서 텔레뱅킹 재발신 시 쿨다운 ──
                    if (alertState == AlertState.CRITICAL &&
                        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in callSignals &&
                        !cooldownManager.isShowing()
                    ) {
                        val isCallActive = callSignals.isNotEmpty()
                        cooldownManager.triggerIfNotActive(
                            score.level,
                            "은행 ARS 전화가 감지되었습니다.\n잠시 멈추고 다시 생각해 보세요.",
                            isCallActive,
                        )
                        Log.d(TAG, "텔레뱅킹 쿨다운 발동: level=${score.level}")
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
