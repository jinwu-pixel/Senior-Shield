package com.example.seniorshield.feature.splash

sealed interface SplashUiState {
    data object Loading : SplashUiState
    data class Ready(val route: String) : SplashUiState
}