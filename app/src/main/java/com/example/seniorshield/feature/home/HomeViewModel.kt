package com.example.seniorshield.feature.home

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
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
    private val sessionTracker: RiskSessionTracker,
    private val alertStateResolver: AlertStateResolver,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _hasCriticalPermissions = MutableStateFlow(checkCriticalPermissions())

    private data class WeeklySnapshot(
        val eventCount: Int = 0,
        val tip: String = "",
    )

    private val _weeklySnapshot = MutableStateFlow(WeeklySnapshot())

    /** GUARDED 카드를 이미 표시한 세션 ID. 세션당 1회만 노출. */
    private var guardedCardShownSessionId: String? = null

    val uiState: StateFlow<HomeUiState> = combine(
        riskRepository.getCurrentRiskEvent(),
        riskRepository.getRecentRiskEvents(),
        _hasCriticalPermissions,
        _weeklySnapshot,
        sessionTracker.sessionState,
    ) { current, recent, hasPermissions, weekly, session ->
        val baseBody = current?.title ?: "안전합니다. 감지된 위험이 없습니다."
        val summary = "최근 24시간: ${recent.size}건 · 이번 주: ${weekly.eventCount}건"
        val body = "$baseBody\n$summary"

        // GUARDED 카드: 세션당 1회, INTERRUPT/CRITICAL 진입 시 정리
        val alertState = alertStateResolver.resolve(session)
        val guardedCard = when {
            alertState == AlertState.GUARDED && session != null
                    && guardedCardShownSessionId != session.id -> {
                guardedCardShownSessionId = session.id
                buildGuardedCard(session.accumulatedSignals)
            }
            alertState.ordinal >= AlertState.INTERRUPT.ordinal -> {
                // INTERRUPT/CRITICAL 진입 시 카드 정리
                null
            }
            alertState == AlertState.GUARDED && session != null
                    && guardedCardShownSessionId == session.id -> {
                // 이미 표시한 세션 — 유지
                buildGuardedCard(session.accumulatedSignals)
            }
            else -> null
        }

        HomeUiState(
            currentRiskTitle = if (current != null) "위험 감지" else "현재 보호 상태",
            currentRiskBody = body,
            currentRiskLevel = current?.level ?: RiskLevel.LOW,
            recentEventCount = recent.size,
            hasCriticalPermissions = hasPermissions,
            weeklyEventCount = weekly.eventCount,
            weeklyTip = weekly.tip,
            guardedCard = guardedCard,
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

    init {
        viewModelScope.launch { loadWeeklySnapshot() }
        viewModelScope.launch {
            riskRepository.getCurrentRiskEvent()
                .filterNotNull()
                .filter { it.level.ordinal >= RiskLevel.HIGH.ordinal }
                .distinctUntilChangedBy { it.id }
                .collect { _navigateToWarning.tryEmit(Unit) }
        }
    }

    fun refreshPermissions() {
        _hasCriticalPermissions.value = checkCriticalPermissions()
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
