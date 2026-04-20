package com.example.seniorshield.feature.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GuardianViewModel @Inject constructor(
    private val guardianRepository: GuardianRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    private val _showSmsPicker = MutableStateFlow(false)

    val uiState: StateFlow<GuardianUiState> = combine(
        guardianRepository.observeGuardians(),
        _message,
        settingsRepository.observeSmsMenuEnabled(),
        _showSmsPicker,
    ) { guardians, msg, smsMenuEnabled, showSmsPicker ->
        GuardianUiState(
            guardians = guardians,
            canAddMore = guardians.size < Guardian.MAX_COUNT,
            message = msg,
            smsMenuEnabled = smsMenuEnabled,
            showSmsPicker = showSmsPicker,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GuardianUiState(),
    )

    fun clearMessage() { _message.value = null }
    fun showSmsPicker() { _showSmsPicker.value = true }
    fun dismissSmsPicker() { _showSmsPicker.value = false }

    fun removeGuardian(id: String) {
        viewModelScope.launch {
            guardianRepository.removeGuardian(id)
            _message.value = "보호자가 삭제되었습니다."
        }
    }
}
