package com.example.seniorshield.feature.home

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val riskRepository: RiskRepository,
    private val eventSink: RiskEventSink,
    private val sessionTracker: RiskSessionTracker,
    private val alertStateResolver: AlertStateResolver,
    private val guardianRepository: GuardianRepository,
    private val callRiskMonitor: CallRiskMonitor,
    private val coordinator: RiskDetectionCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _hasCriticalPermissions = MutableStateFlow(checkCriticalPermissions())

    private data class WeeklySnapshot(
        val eventCount: Int = 0,
        val tip: String = "",
    )

    private val _weeklySnapshot = MutableStateFlow(WeeklySnapshot())

    /** GUARDED 카드를 이미 표시한 세션 ID. 세션당 1회만 노출. */
    private val _guardedCardShownSessionId = MutableStateFlow<String?>(null)

    private data class SessionCombined(
        val session: com.example.seniorshield.monitoring.session.RiskSession?,
        val shownId: String?,
        val guardians: List<Guardian>,
        val anchorHot: Boolean,
    )

    val uiState: StateFlow<HomeUiState> = combine(
        riskRepository.getCurrentRiskEvent(),
        riskRepository.getRecentRiskEvents(),
        _hasCriticalPermissions,
        _weeklySnapshot,
        combine(
            sessionTracker.sessionState,
            _guardedCardShownSessionId,
            guardianRepository.observeGuardians(),
            coordinator.anchorHotState,
        ) { s, id, guardians, hot -> SessionCombined(s, id, guardians, hot) },
    ) { current, recent, hasPermissions, weekly, sc ->
        val summary = "최근 24시간: ${recent.size}건 · 이번 주: ${weekly.eventCount}건"
        val presentation = decideHomePresentation(
            currentEventTitle = current?.title,
            currentEventLevel = current?.level,
            anchorHot = sc.anchorHot,
        )
        val body = "${presentation.baseBody}\n$summary"

        val alertState = alertStateResolver.resolve(sc.session)
        val guardedCard = when {
            alertState == AlertState.GUARDED && sc.session != null
                    && sc.shownId == sc.session.id ->
                buildGuardedCard(sc.session.accumulatedSignals)
            else -> null
        }

        val guardian: Guardian? =
            if (alertState.ordinal >= AlertState.GUARDED.ordinal) sc.guardians.firstOrNull()
            else null

        HomeUiState(
            currentRiskTitle = presentation.title,
            currentRiskBody = body,
            currentRiskLevel = presentation.level,
            homeStatus = presentation.status,
            recentEventCount = recent.size,
            hasCriticalPermissions = hasPermissions,
            weeklyEventCount = weekly.eventCount,
            weeklyTip = weekly.tip,
            guardedCard = guardedCard,
            hasGuardian = guardian != null,
            guardianName = guardian?.name ?: "",
            guardianPhone = guardian?.phoneNumber ?: "",
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    private fun buildGuardedCard(signals: Set<RiskSignal>): GuardedCardInfo {
        val body = when {
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK in signals ||
                    RiskSignal.REPEATED_UNKNOWN_CALLER in signals ->
                "확인되지 않은 번호에서 반복 전화가 감지되었습니다. 금융 관련 요청에는 응하지 마세요."

            RiskSignal.LONG_CALL_DURATION in signals ->
                "낯선 번호와 장시간 통화가 감지되었습니다. 금융 관련 요청에는 주의하세요."

            else ->
                "의심스러운 활동이 감지되었습니다. 주의가 필요합니다."
        }
        return GuardedCardInfo(title = "주의 안내", body = body)
    }

    // HIGH / CRITICAL 이벤트 감지 시 경고 화면으로 자동 이동하는 1회성 신호.
    private val _navigateToWarning = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToWarning = _navigateToWarning.asSharedFlow()

    // ViewModel 생성 시각. MainActivity가 재생성되면(뱅킹 쿨다운 OPAQUE 오버레이로 인한 window 재부착 등)
    // HomeViewModel도 새로 만들어지고 StateFlow의 기존 currentRiskEvent가 replay된다.
    // 이 replay 값으로 Warning 자동 네비가 재발화되는 것을 막기 위해 VM 생성 이전에 발생한 이벤트는 무시한다.
    private val viewModelStartedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch { loadWeeklySnapshot() }
        viewModelScope.launch {
            riskRepository.getRecentRiskEvents().collect { loadWeeklySnapshot() }
        }
        viewModelScope.launch {
            sessionTracker.sessionState.collect { session ->
                val alertState = alertStateResolver.resolve(session)
                if (alertState == AlertState.GUARDED && session != null
                    && _guardedCardShownSessionId.value != session.id
                ) {
                    _guardedCardShownSessionId.value = session.id
                }
            }
        }
        viewModelScope.launch {
            riskRepository.getCurrentRiskEvent()
                .filterNotNull()
                .filter { it.level.ordinal >= RiskLevel.HIGH.ordinal }
                .filter { it.occurredAtMillis >= viewModelStartedAt }
                .distinctUntilChangedBy { it.id }
                .collect { _navigateToWarning.tryEmit(Unit) }
        }
    }

    fun refreshPermissions() {
        _hasCriticalPermissions.value = checkCriticalPermissions()
    }

    /**
     * 사용자가 홈 화면에서 "안전 확인"을 선택하면 현재 위험 세션을 완전히 종료한다.
     *
     * 호출 순서 엄수:
     *   1) sessionTracker.resetAfterUserConfirmedSafe()
     *   2) callRiskMonitor.clearTelebankingAnchor()
     *   3) coordinator.refreshAnchorHotNow()       ← mirror 즉시 false 동기화
     *   4) eventSink.clearCurrentRiskEvent()       ← 항상 마지막
     *
     * 3과 4의 순서가 바뀌면 `currentRiskEvent=null && anchorHot=true` 중간 상태가 드러나
     * Home이 GUARDED_ANCHOR를 짧게 다시 보여줘 UX가 어긋난다. 이 순서를 지키면 WARNING → SAFE로
     * 직행한다.
     */
    fun confirmSafe() {
        sessionTracker.resetAfterUserConfirmedSafe()
        callRiskMonitor.clearTelebankingAnchor()
        coordinator.refreshAnchorHotNow()
        eventSink.clearCurrentRiskEvent()
        viewModelScope.launch { loadWeeklySnapshot() }
    }

    private suspend fun loadWeeklySnapshot() {
        val cal = Calendar.getInstance(Locale.KOREA).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val weekStart = cal.timeInMillis
        val count = riskRepository.countEventsSince(weekStart)
        val weekIndex = Calendar.getInstance(Locale.KOREA).get(Calendar.WEEK_OF_YEAR)
        val tip = WEEKLY_TIPS[weekIndex % WEEKLY_TIPS.size]
        _weeklySnapshot.value = WeeklySnapshot(eventCount = count, tip = tip)
    }

    private fun checkCriticalPermissions(): Boolean {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(context)
        return hasPhoneState && hasOverlay
    }

    companion object {
        private val WEEKLY_TIPS = listOf(
            "모르는 번호로 온 전화에서 '검찰', '경찰'을 언급하면 100% 사기입니다. 바로 끊으세요.",
            "정부기관은 절대 전화로 개인정보나 계좌번호를 묻지 않습니다.",
            "누군가 전화로 앱 설치를 요구하면 즉시 전화를 끊으세요.",
            "가족이 돈을 급히 보내달라고 하면, 반드시 직접 전화해서 확인하세요.",
            "은행은 전화로 비밀번호나 OTP 번호를 절대 묻지 않습니다.",
            "통화 중에 원격제어 앱을 설치하라는 요청은 금융사기입니다.",
            "의심스러운 전화를 받으면 112(경찰) 또는 1332(금융감독원)에 신고하세요.",
            "대출 상담 전화가 왔을 때 수수료를 먼저 내라고 하면 사기입니다.",
        )
    }
}
