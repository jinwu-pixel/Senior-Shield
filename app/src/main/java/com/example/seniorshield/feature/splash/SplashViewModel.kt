package com.example.seniorshield.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SplashUiState>(SplashUiState.Loading)
    val uiState: StateFlow<SplashUiState> = _uiState

    init {
        viewModelScope.launch {
            val completed = settingsRepository.observeOnboardingCompleted().first()
            _uiState.value = SplashUiState.Ready(
                route = if (completed) SeniorShieldDestination.HOME else SeniorShieldDestination.ONBOARDING
            )
        }
    }
}