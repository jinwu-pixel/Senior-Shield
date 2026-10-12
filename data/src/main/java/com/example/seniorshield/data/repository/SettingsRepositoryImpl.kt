package com.example.seniorshield.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.seniorshield.data.local.SettingsKeys
import com.example.seniorshield.data.local.settingsDataStore
import com.example.seniorshield.data.local.withReadFallback
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "SeniorShield-Settings"

/** 기본 생성자는 테스트에서 DataStore를 주입하기 위한 것이다. */
@Singleton
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.settingsDataStore)

    override fun observeOnboardingCompleted(): Flow<Boolean> =
        dataStore.data.withReadFallback(TAG, "settings").map { prefs ->
            prefs[SettingsKeys.OnboardingCompleted] ?: false
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.OnboardingCompleted] = completed
            }
        } catch (e: IOException) {
            Log.w(TAG, "settings write failed", e)
        }
    }

    override fun observeSmsAlertEnabled(): Flow<Boolean> =
        dataStore.data.withReadFallback(TAG, "settings").map { prefs ->
            prefs[SettingsKeys.SmsAlertEnabled] ?: false
        }

    override suspend fun setSmsAlertEnabled(enabled: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.SmsAlertEnabled] = enabled
            }
        } catch (e: IOException) {
            Log.w(TAG, "settings write failed", e)
        }
    }

    override fun observeTestModeEnabled(): Flow<Boolean> =
        dataStore.data.withReadFallback(TAG, "settings").map { prefs ->
            prefs[SettingsKeys.TestModeEnabled] ?: false
        }

    override suspend fun setTestModeEnabled(enabled: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.TestModeEnabled] = enabled
            }
        } catch (e: IOException) {
            Log.w(TAG, "settings write failed", e)
        }
    }

    override fun observeSmsMenuEnabled(): Flow<Boolean> =
        dataStore.data.withReadFallback(TAG, "settings").map { prefs ->
            prefs[SettingsKeys.SmsMenuEnabled] ?: false
        }

    override suspend fun setSmsMenuEnabled(enabled: Boolean) {
        try {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.SmsMenuEnabled] = enabled
            }
        } catch (e: IOException) {
            Log.w(TAG, "settings write failed", e)
        }
    }
}
