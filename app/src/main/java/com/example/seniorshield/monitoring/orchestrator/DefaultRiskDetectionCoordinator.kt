package com.example.seniorshield.monitoring.orchestrator

import android.util.Log
import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.model.SignalCategory
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
    private val ioDispatcher: CoroutineDispatcher,
) : RiskDetectionCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null
    @Volatile private var previousBankingForeground = false

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

                    val session = sessionTracker.update(callSignals, appSignals + installSignals + deviceEnvSignals) ?: run {
                        Log.d(TAG, "no active session")
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

                    val triggers = session.accumulatedSignals.filter { it.category == SignalCategory.TRIGGER }.toSet()

                    // ── notification: AlertState 전이 시 1회만 ────────────────
                    var popupShownThisTick = false
                    val prevAlertOrdinal = session.notifiedAlertState?.ordinal ?: -1
                    if (alertState.ordinal > prevAlertOrdinal) {
                        val event = eventFactory.create(score)
                        eventSink.pushRiskEvent(event)
                        sessionTracker.markAlertStateNotified(alertState)
                        sessionTracker.markNotified(score.level)
                        Log.d(TAG, "notification escalation: ${session.notifiedAlertState} → $alertState")

                        notificationManager.notify(event)

                        // popup: INTERRUPT/CRITICAL 전이 시 — 뱅킹 미포그라운드 + 쿨다운 미표시
                        if (alertState.ordinal >= AlertState.INTERRUPT.ordinal &&
                            !bankingForeground && !cooldownManager.isShowing()
                        ) {
                            overlayManager.show(event)
                            sessionTracker.markActiveThreatsNotified(triggers)
                            popupShownThisTick = true
                            Log.d(TAG, "popup shown on state transition → $alertState")
                        }
                    }

                    // ── 새 trigger 재알림: 에스컬레이션 없이도 새 trigger 감지 시 팝업 ──
                    // popupShownThisTick 가드: 같은 사이클에서 에스컬레이션 팝업과 중복 방지
                    if (!popupShownThisTick &&
                        alertState.ordinal >= AlertState.INTERRUPT.ordinal && triggers.isNotEmpty()
                    ) {
                        val newTriggers = triggers - session.notifiedActiveThreats
                        if (newTriggers.isNotEmpty() && !bankingForeground && !cooldownManager.isShowing()) {
                            val event = eventFactory.create(score, triggerSignals = newTriggers)
                            eventSink.pushRiskEvent(event)
                            notificationManager.notify(event)
                            overlayManager.show(event)
                            sessionTracker.markActiveThreatsNotified(triggers)
                            Log.d(TAG, "새 trigger 팝업: newTriggers=$newTriggers")
                        }
                    }

                    // ── banking cooldown: call-based 세션에서만 발동 ──────────
                    val bankingJustOpened = bankingForeground && !previousBankingForeground
                    if (bankingJustOpened && alertState.ordinal >= AlertState.GUARDED.ordinal) {
                        val isCallBased = session.accumulatedSignals.any { it in AlertStateResolver.CALL_SIGNALS }
                        if (isCallBased) {
                            val reason = buildCooldownReason(session.accumulatedSignals)
                            cooldownManager.triggerIfNotActive(score.level, reason)
                            Log.d(TAG, "뱅킹 쿨다운 발동: level=${score.level}, alertState=$alertState, reason=$reason")
                        } else {
                            Log.d(TAG, "뱅킹 쿨다운 생략: call-based 세션 아님")
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
