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
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOrigin
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationSubject
import com.example.seniorshield.monitoring.orchestrator.WarningNavigationPayload
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val riskRepository: RiskRepository,
    private val sessionTracker: RiskSessionTracker,
    private val alertStateResolver: AlertStateResolver,
    private val guardianRepository: GuardianRepository,
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

    // PUBLISHED HIGH / CRITICAL 이벤트 감지 시 경고 화면으로 자동 이동하는 1회성 신호.
    private val _navigateToWarning = MutableSharedFlow<WarningNavigationPayload>(
        extraBufferCapacity = 1,
    )
    val navigateToWarning = _navigateToWarning.asSharedFlow()

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
            coordinator.warningNavigationEvents
                .distinctUntilChanged()
                .collect { payload -> _navigateToWarning.emit(payload) }
        }
    }

    fun refreshPermissions() {
        _hasCriticalPermissions.value = checkCriticalPermissions()
    }

    /**
     * 사용자가 홈 화면에서 "안전 확인"을 선택하면 현재 위험 세션을 완전히 종료한다.
     *
     * 상태 변경은 Coordinator의 typed command에 위임한다. 주간 스냅샷은 command가 성공한 뒤에만
     * 갱신하여 실패한 안전확인을 성공처럼 보이지 않게 한다.
     */
    fun confirmSafe(): Boolean {
        val request = coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME)
            ?: return false
        if (!coordinator.confirmSafe(request)) return false
        viewModelScope.launch { loadWeeklySnapshot() }
        return true
    }

    /** Revalidates Event provenance at the last boundary before automatic navigation. */
    fun isWarningNavigationPayloadCurrent(payload: WarningNavigationPayload): Boolean {
        val liveResetEpoch = sessionTracker.userResetEpoch
        if (payload.expectedResetEpoch != liveResetEpoch) return false
        val request = coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME)
            ?: return false
        val event = request.subject as? SafeConfirmationSubject.Event ?: return false
        return event.eventId == payload.eventId &&
            request.expectedEventId == payload.eventId &&
            request.expectedResetEpoch == payload.expectedResetEpoch &&
            sessionTracker.userResetEpoch == liveResetEpoch
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
