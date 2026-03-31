package com.example.seniorshield.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.evaluator.RiskEvaluator
import com.example.seniorshield.monitoring.session.RiskSession
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val sessionTracker: RiskSessionTracker,
    private val eventSink: RiskEventSink,
    private val evaluator: RiskEvaluator,
    private val overlayManager: RiskOverlayManager,
    private val cooldownManager: BankingCooldownManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 현재 활성 세션. null = 탐지 신호 없음. */
    val session: StateFlow<RiskSession?> = sessionTracker.sessionState

    /** 테스트 모드 — ON: 장시간 통화 임계값 10초 / OFF: 180초(3분). */
    val testModeEnabled: StateFlow<Boolean> = settingsRepository.observeTestModeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 세션에서 계산된 현재 점수. 세션이 없으면 null. */
    val score: StateFlow<RiskScore?> = session
        .map { s -> s?.let { evaluator.evaluate(it.accumulatedSignals.toList()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 세션 + 이력을 모두 초기화한다. */
    fun resetAll() {
        sessionTracker.reset()
        viewModelScope.launch { eventSink.clearAll() }
    }

    /** HIGH 위험 팝업을 화면에 직접 띄운다. */
    fun showTestOverlay() {
        overlayManager.show(
            RiskEvent(
                id = "debug-${System.currentTimeMillis()}",
                title = "[테스트] HIGH 위험 감지",
                description = "테스트용 팝업입니다. 실제 위험 상황이 아닙니다.",
                occurredAtMillis = System.currentTimeMillis(),
                level = RiskLevel.HIGH,
                signals = listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.REMOTE_CONTROL_APP_OPENED),
            )
        )
    }

    /** 뱅킹 쿨다운 인터럽터를 5초 미리보기로 띄운다. */
    fun showTestCooldown() {
        cooldownManager.triggerPreview(countdownSec = 5)
    }

    fun setTestModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTestModeEnabled(enabled) }
    }
}
