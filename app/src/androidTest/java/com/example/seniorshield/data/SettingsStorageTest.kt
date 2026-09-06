package com.example.seniorshield.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsStorageTest {
    @Before
    fun resetPreferences() = storageTest { SuiteDataStores.reset() }

    @Test
    fun allFourMissingKeysDefaultToFalse() = storageTest {
        val repository = SettingsRepositoryImpl(SuiteDataStores.context)

        assertTrue(SuiteDataStores.settings.data.first().asMap().isEmpty())
        assertFalse(repository.observeOnboardingCompleted().first())
        assertFalse(repository.observeSmsAlertEnabled().first())
        assertFalse(repository.observeTestModeEnabled().first())
        assertFalse(repository.observeSmsMenuEnabled().first())
    }

    @Test
    fun fourKeysRoundTripAndAnotherRepositoryObservesTheSameActiveFile() = storageTest {
        val writer = SettingsRepositoryImpl(SuiteDataStores.context)
        val reader = SettingsRepositoryImpl(SuiteDataStores.context)
        val setters: List<Pair<String, suspend (Boolean) -> Unit>> = listOf(
            "onboarding_completed" to writer::setOnboardingCompleted,
            "sms_alert_enabled" to writer::setSmsAlertEnabled,
            "test_mode_enabled" to writer::setTestModeEnabled,
            "sms_menu_enabled" to writer::setSmsMenuEnabled,
        )
        val observations = mapOf(
            "onboarding_completed" to reader.observeOnboardingCompleted(),
            "sms_alert_enabled" to reader.observeSmsAlertEnabled(),
            "test_mode_enabled" to reader.observeTestModeEnabled(),
            "sms_menu_enabled" to reader.observeSmsMenuEnabled(),
        )
        val expected = linkedMapOf<String, Boolean>()

        for ((key, setValue) in setters) {
            for (enabled in listOf(true, false)) {
                setValue(enabled)
                expected[key] = enabled
                for ((observedKey, values) in observations) {
                    assertEquals(observedKey, expected[observedKey] ?: false, values.first())
                }
                assertEquals(
                    expected,
                    SuiteDataStores.settings.data.first().asMap().mapKeys { it.key.name },
                )
            }
        }
        assertTrue(
            File(SuiteDataStores.context.filesDir, "datastore/senior_shield_settings.preferences_pb").isFile,
        )
    }
}
