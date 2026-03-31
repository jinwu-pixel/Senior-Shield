package com.example.seniorshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.example.seniorshield.data.local.SettingsKeys
import com.example.seniorshield.data.local.settingsDataStore
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    override fun observeOnboardingCompleted(): Flow<Boolean> =
        context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.OnboardingCompleted] ?: false
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.OnboardingCompleted] = completed
        }
    }

    override fun observeSmsAlertEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.SmsAlertEnabled] ?: false
        }

    override suspend fun setSmsAlertEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.SmsAlertEnabled] = enabled
        }
    }

    override fun observeTestModeEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.TestModeEnabled] ?: false
        }

    override suspend fun setTestModeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.TestModeEnabled] = enabled
        }
    }
}