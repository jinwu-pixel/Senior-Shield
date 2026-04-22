package com.example.seniorshield.feature.warning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class WarningViewModel @Inject constructor(
    guardianRepository: GuardianRepository,
    riskRepository: RiskRepository,
    settingsRepository: SettingsRepository,
    private val sessionTracker: RiskSessionTracker,
    private val eventSink: RiskEventSink,
    private val callRiskMonitor: CallRiskMonitor,
) : ViewModel() {

    private val _showGuardianPicker = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _showSmsPicker = MutableStateFlow(false)

    val uiState: StateFlow<WarningUiState> = combine(
        combine(
            guardianRepository.observeGuardians(),
            _showGuardianPicker,
            _message,
            riskRepository.getCurrentRiskEvent(),
        ) { guardians, showPicker, message, currentEvent ->
            WarningUiState(
                guardians = guardians,
                showGuardianPicker = showPicker,
                message = message,
                detectedEventTitle = currentEvent?.title,
                detectedEventDescription = currentEvent?.description,
                detectedEventLevel = currentEvent?.level,
            )
        },
        settingsRepository.observeSmsMenuEnabled(),
        _showSmsPicker,
    ) { base, smsMenuEnabled, showSmsPicker ->
        base.copy(
            smsMenuEnabled = smsMenuEnabled,
            showSmsPicker = showSmsPicker,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WarningUiState(),
    )

    fun showGuardianPicker() { _showGuardianPicker.value = true }
    fun dismissGuardianPicker() { _showGuardianPicker.value = false }
    fun clearMessage() { _message.value = null }
    fun showSmsPicker() { _showSmsPicker.value = true }
    fun dismissSmsPicker() { _showSmsPicker.value = false }

    /**
     * 사용자가 "안전 확인"을 선택하면 현재 위험 세션을 완전히 종료한다.
     * 통일 종료 시퀀스 (A′): reset → clearTelebankingAnchor → clearCurrentRiskEvent.
     * 화면 복귀(onBack)는 호출자(Screen) 책임 — 상태 종료와 화면 종료는 분리된다.
     */
    fun confirmSafe() {
        sessionTracker.resetAfterUserConfirmedSafe()
        callRiskMonitor.clearTelebankingAnchor()
        eventSink.clearCurrentRiskEvent()
    }
}
