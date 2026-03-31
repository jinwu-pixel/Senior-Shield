package com.example.seniorshield.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "senior_shield_settings")

object SettingsKeys {
    val OnboardingCompleted = booleanPreferencesKey("onboarding_completed")
    val SmsAlertEnabled = booleanPreferencesKey("sms_alert_enabled")
    val TestModeEnabled = booleanPreferencesKey("test_mode_enabled")
}