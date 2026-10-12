package com.example.seniorshield.feature.guardian

import com.example.seniorshield.data.StorageFailureTestBase
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardianStorageFailureViewModelTest : StorageFailureTestBase() {
    @Test
    fun `add IO failure shows storage message and sends no saved event`() = runTest(dispatcher) {
        val repository = addRepository()
        coEvery { repository.addGuardian(any()) } throws IOException("write fixture")
        val viewModel = GuardianAddViewModel(repository).owned()
        var saved = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.savedEvent.collect { saved++ } }
        viewModel.updateName("Test")
        viewModel.updatePhone("01012345678")
        viewModel.save()
        runCurrent()
        assertEquals("저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", viewModel.uiState.value.error)
        assertEquals(0, saved)
        coVerify(exactly = 1) { repository.addGuardian(any()) }
    }

    @Test
    fun `full guardian list keeps capacity message and sends no saved event`() = runTest(dispatcher) {
        val repository = addRepository()
        coEvery { repository.addGuardian(any()) } returns false
        val viewModel = GuardianAddViewModel(repository).owned()
        var saved = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.savedEvent.collect { saved++ } }
        viewModel.updateName("Test")
        viewModel.updatePhone("01012345678")
        viewModel.save()
        runCurrent()
        assertEquals("최대 3명까지 등록할 수 있습니다.", viewModel.uiState.value.error)
        assertEquals(0, saved)
        coVerify(exactly = 1) { repository.addGuardian(any()) }
    }

    @Test
    fun `remove IO failure shows storage message instead of success`() = runTest(dispatcher) {
        val repository = mockk<GuardianRepository>()
        every { repository.observeGuardians() } returns flowOf(emptyList())
        coEvery { repository.removeGuardian("one") } throws IOException("write fixture")
        val settings = mockk<SettingsRepository>()
        every { settings.observeSmsMenuEnabled() } returns flowOf(false)
        val viewModel = GuardianViewModel(repository, settings).owned()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.removeGuardian("one")
        runCurrent()
        assertEquals("저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", viewModel.uiState.value.message)
        coVerify(exactly = 1) { repository.removeGuardian("one") }
    }

    private fun addRepository(): GuardianRepository = mockk<GuardianRepository>().also {
        coEvery { it.getGuardians() } returns emptyList()
    }
}
