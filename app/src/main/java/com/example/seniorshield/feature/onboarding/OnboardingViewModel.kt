package com.example.seniorshield.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    fun completeOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            onDone()
        }
    }
}