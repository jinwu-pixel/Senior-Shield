package com.example.seniorshield.monitoring.orchestrator

import android.util.Log
import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
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
 * ## 에스컬레이션 알림
 * 세션 점수가 이전 알림 수준보다 올라갈 때만 이벤트를 발행한다.
 * - MEDIUM: 이력 기록만
 * - HIGH+:  이력 + 알림 + 위험 팝업 (능동적 위협 시, 뱅킹 앱 미포그라운드)
 *
 * ## 새 능동적 위협 재알림
 * 에스컬레이션 없이도 새로운 능동적 위협 신호(원격제어, 텔레뱅킹 등)가
 * HIGH+ 세션에 추가되면 팝업을 재표시한다.
 *
 * ## 뱅킹 쿨다운 인터럽터
 * HIGH+ 세션 중 뱅킹 앱이 포그라운드로 전환될 때마다 [BankingCooldownManager]가
 * 위험 수준에 따라 차등 카운트다운(HIGH=30초, CRITICAL=60초)을 표시한다.
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
                    Log.d(TAG, "session score: total=${score.total}, level=${score.level}")

                    val currentActiveThreats = session.accumulatedSignals.intersect(ACTIVE_THREAT_SIGNALS)

                    // ── 에스컬레이션: 이전보다 위험 수준이 높아졌을 때만 처리 ───────
                    var popupShownThisTick = false
                    val prevOrdinal = session.notifiedLevel?.ordinal ?: -1
                    if (score.level.ordinal > prevOrdinal &&
                        score.level.ordinal >= RiskLevel.MEDIUM.ordinal
                    ) {
                        val event = eventFactory.create(score)
                        eventSink.pushRiskEvent(event)
                        sessionTracker.markNotified(score.level)
                        Log.d(TAG, "escalation: ${session.notifiedLevel} → ${score.level}")

                        if (score.level.ordinal >= RiskLevel.HIGH.ordinal) {
                            notificationManager.notify(event)
                            if (currentActiveThreats.isNotEmpty() && !bankingForeground && !cooldownManager.isShowing()) {
                                overlayManager.show(event)
                                sessionTracker.markActiveThreatsNotified(currentActiveThreats)
                                popupShownThisTick = true
                            }
                        }
                    }

                    // ── 새 능동적 위협: 에스컬레이션 없이도 새 위협 감지 시 팝업 재표시 ──
                    if (!popupShownThisTick && score.level.ordinal >= RiskLevel.HIGH.ordinal) {
                        val newThreats = currentActiveThreats - session.notifiedActiveThreats
                        if (newThreats.isNotEmpty() && !bankingForeground && !cooldownManager.isShowing()) {
                            val event = eventFactory.create(score, triggerSignals = newThreats)
                            eventSink.pushRiskEvent(event)
                            notificationManager.notify(event)
                            overlayManager.show(event)
                            sessionTracker.markActiveThreatsNotified(currentActiveThreats)
                            Log.d(TAG, "새 능동적 위협 팝업: newThreats=$newThreats")
                        }
                    }

                    // ── 뱅킹 쿨다운 인터럽터: 포그라운드 전환(false→true)마다 발동 ───
                    val bankingJustOpened = bankingForeground && !previousBankingForeground
                    if (bankingJustOpened &&
                        score.level.ordinal >= RiskLevel.HIGH.ordinal
                    ) {
                        cooldownManager.triggerIfNotActive(score.level)
                        Log.d(TAG, "뱅킹 쿨다운 인터럽터 발동: level=${score.level}")
                    }

                    previousBankingForeground = bankingForeground
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** 능동적 위협으로 간주하는 신호 — 팝업 발동 조건에 사용. */
        private val ACTIVE_THREAT_SIGNALS = setOf(
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
        )
    }
}

private data class CombinedSignals(
    val callSignals: List<RiskSignal>,
    val appSignals: List<RiskSignal>,
    val bankingForeground: Boolean,
    val installSignals: List<RiskSignal>,
    val deviceEnvSignals: List<RiskSignal>,
)
