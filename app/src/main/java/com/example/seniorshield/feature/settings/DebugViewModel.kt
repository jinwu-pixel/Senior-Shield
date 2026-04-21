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

    /** 보호자 문자 보내기 메뉴 표시 여부. */
    val smsMenuEnabled: StateFlow<Boolean> = settingsRepository.observeSmsMenuEnabled()
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

    fun setSmsMenuEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSmsMenuEnabled(enabled) }
    }

    /** 텔레뱅킹 유도 사기 전체 파이프라인을 시뮬레이션한다. */
    fun simulateTelebankingDetection() {
        // Phase 1: 의심 통화 수신
        sessionTracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        // Phase 2: 반복 호출 + 장시간 통화
        sessionTracker.update(
            listOf(
                RiskSignal.REPEATED_UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
                RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
            ),
            emptyList(),
        )
        // Phase 3: 텔레뱅킹 시도
        val session = sessionTracker.update(
            listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS),
            emptyList(),
        )
        if (session != null) {
            val score = evaluator.evaluate(session.accumulatedSignals.toList())
            val event = RiskEvent(
                id = "debug-telebanking-${System.currentTimeMillis()}",
                title = "[테스트] 텔레뱅킹 유도 사기 감지",
                description = "의심 통화 → 반복 호출 → 은행 ARS 발신 시뮬레이션",
                occurredAtMillis = System.currentTimeMillis(),
                level = score.level,
                signals = session.accumulatedSignals.toList(),
            )
            viewModelScope.launch { eventSink.pushRiskEvent(event) }
            overlayManager.show(event)
        }
    }

    /**
     * 원격제어 앱 실행 (non-call) 시뮬레이션.
     * α suppression 본체 검증용 — REMOTE_CONTROL_APP_OPENED 단독 주입.
     *
     * 시나리오: 1회 눌러 세션 생성 → "안전 확인" → 다시 누르면 α가 억제 (session null 유지).
     * 억제 증거: logcat "non-call session respawn suppressed by α" + UI "현재 보호 상태" 유지.
     */
    fun simulateRemoteAppDetection() {
        val session = sessionTracker.update(
            callSignals = emptyList(),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        if (session != null) {
            val score = evaluator.evaluate(session.accumulatedSignals.toList())
            val event = RiskEvent(
                id = "debug-remote-${System.currentTimeMillis()}",
                title = "[테스트] 원격제어 앱 실행 감지",
                description = "원격제어 앱 실행 (non-call) 시뮬레이션",
                occurredAtMillis = System.currentTimeMillis(),
                level = score.level,
                signals = session.accumulatedSignals.toList(),
            )
            viewModelScope.launch { eventSink.pushRiskEvent(event) }
            overlayManager.show(event)
        }
    }

    /**
     * 원격제어 + 금융앱 실행 (non-call UPGRADE) 시뮬레이션.
     * α UPGRADE escape 검증용 — α armed 상태에서 호출 시 새 UPGRADE 신호로 억제 우회.
     *
     * 시나리오: simulateRemoteAppDetection → "안전 확인" → 본 API → 새 CRITICAL 세션 재생성.
     */
    fun simulateRemoteThenBanking() {
        val session = sessionTracker.update(
            callSignals = emptyList(),
            appSignals = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            ),
        )
        if (session != null) {
            val score = evaluator.evaluate(session.accumulatedSignals.toList())
            val event = RiskEvent(
                id = "debug-remote-banking-${System.currentTimeMillis()}",
                title = "[테스트] 원격제어 후 금융앱 실행 감지",
                description = "원격제어 직후 금융앱 실행 (UPGRADE) 시뮬레이션",
                occurredAtMillis = System.currentTimeMillis(),
                level = score.level,
                signals = session.accumulatedSignals.toList(),
            )
            viewModelScope.launch { eventSink.pushRiskEvent(event) }
            overlayManager.show(event)
        }
    }
}
