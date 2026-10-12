package com.example.seniorshield.feature.onboarding

import androidx.datastore.core.CorruptionException
import com.example.seniorshield.data.StorageFailureTestBase
import com.example.seniorshield.data.assertSuspendThrows
import com.example.seniorshield.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStorageFailureTest : StorageFailureTestBase() {
    @Test
    fun `corrupt settings still call onboarding completion once`() = runTest(dispatcher) {
        val (store, file) = newStore(corrupt = true)
        assertSuspendThrows<CorruptionException> { store.data.first() }
        val original = file.readBytes()
        val viewModel = OnboardingViewModel(SettingsRepositoryImpl(store)).owned()
        var completions = 0
        viewModel.completeOnboarding { completions++ }
        runCurrent()
        assertEquals(1, completions)
        assertArrayEquals(original, file.readBytes())
    }
}
