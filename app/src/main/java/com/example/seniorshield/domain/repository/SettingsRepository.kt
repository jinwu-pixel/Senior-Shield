package com.example.seniorshield.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeOnboardingCompleted(): Flow<Boolean>
    suspend fun setOnboardingCompleted(completed: Boolean)

    fun observeSmsAlertEnabled(): Flow<Boolean>
    suspend fun setSmsAlertEnabled(enabled: Boolean)

    fun observeTestModeEnabled(): Flow<Boolean>
    suspend fun setTestModeEnabled(enabled: Boolean)

    fun observeSmsMenuEnabled(): Flow<Boolean>
    suspend fun setSmsMenuEnabled(enabled: Boolean)
}