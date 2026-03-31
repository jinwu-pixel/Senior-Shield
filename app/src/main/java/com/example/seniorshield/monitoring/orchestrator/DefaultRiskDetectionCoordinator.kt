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
 * ## 에스컬레이션 알림 (기존)
 * 세션 점수가 이전 알림 수준보다 올라갈 때만 이벤트를 발행한다.
 * - MEDIUM: 이력 기록만
 * - HIGH+:  이력 + 알림 + 위험 팝업 (뱅킹 앱 미포그라운드 시)
 *
 * ## 뱅킹 쿨다운 인터럽터 (신규)
 * HIGH+ 세션 중 뱅킹 앱이 포그라운드로 올라오면 [BankingCooldownManager]가 60초
 * 강제 대기 화면을 표시한다. 같은 세션에서 한 번만 발동한다.
 * 인터럽터가 발동 중일 때는 일반 위험 팝업을 띄우지 않는다.
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
                        return@collect
                    }

                    val score = evaluator.evaluate(session.accumulatedSignals.toList())
                    Log.d(TAG, "session score: total=${score.total}, level=${score.level}")

                    // ── 에스컬레이션: 이전보다 위험 수준이 높아졌을 때만 처리 ───────
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
                            // 능동적 위협 신호(원격제어·뱅킹 연계)가 있을 때만 전체화면 팝업 표시.
                            // UNKNOWN_CALLER + LONG_CALL_DURATION 조합(맥락 신호만)은
                            // 알림으로만 경고하고 팝업을 띄우지 않아 사용자 불편을 줄인다.
                            // HIGH_RISK_DEVICE_ENVIRONMENT는 환경 신호(정적)이므로
                            // 단독 팝업/SMS 발동에서 제외한다 (오탐 방지).
                            val hasActiveThreat = session.accumulatedSignals.any {
                                it == RiskSignal.REMOTE_CONTROL_APP_OPENED ||
                                        it == RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP ||
                                        it == RiskSignal.SUSPICIOUS_APP_INSTALLED ||
                                        it == RiskSignal.TELEBANKING_AFTER_SUSPICIOUS ||
                                        it == RiskSignal.REPEATED_CALL_THEN_LONG_TALK
                            }
                            if (hasActiveThreat && !bankingForeground && !cooldownManager.isShowing()) {
                                overlayManager.show(event)
                            }
                        }
                    }

                    // ── 뱅킹 쿨다운 인터럽터 ────────────────────────────────────────
                    // 조건: HIGH+ 세션 + 뱅킹 포그라운드 + 이 세션에서 아직 미발동
                    if (bankingForeground &&
                        score.level.ordinal >= RiskLevel.HIGH.ordinal &&
                        !session.bankingInterrupterShown
                    ) {
                        cooldownManager.triggerIfNotActive(score.level)
                        sessionTracker.markInterrupterShown()
                        Log.d(TAG, "뱅킹 쿨다운 인터럽터 발동: level=${score.level}")
                    }
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

private data class CombinedSignals(
    val callSignals: List<RiskSignal>,
    val appSignals: List<RiskSignal>,
    val bankingForeground: Boolean,
    val installSignals: List<RiskSignal>,
    val deviceEnvSignals: List<RiskSignal>,
)
