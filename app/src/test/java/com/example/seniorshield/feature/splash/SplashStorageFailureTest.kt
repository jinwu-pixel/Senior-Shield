package com.example.seniorshield.feature.splash

import androidx.datastore.core.CorruptionException
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.data.StorageFailureTestBase
import com.example.seniorshield.data.assertSuspendThrows
import com.example.seniorshield.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SplashStorageFailureTest : StorageFailureTestBase() {
    @Test
    fun `corrupt settings reach onboarding instead of crashing splash`() = runTest(dispatcher) {
        val (store, _) = newStore(corrupt = true)
        assertSuspendThrows<CorruptionException> { store.data.first() }
        val viewModel = SplashViewModel(SettingsRepositoryImpl(store)).owned()
        runCurrent()
        assertEquals(SplashUiState.Ready(SeniorShieldDestination.ONBOARDING), viewModel.uiState.value)
    }
}
