package com.example.seniorshield.feature.warning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
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
}
