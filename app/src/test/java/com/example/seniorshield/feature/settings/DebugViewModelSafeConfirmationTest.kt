package com.example.seniorshield.feature.settings

import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.evaluator.RiskEvaluatorImpl
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelSafeConfirmationTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var coordinator: RiskDetectionCoordinator
    private lateinit var viewModel: DebugViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.observeTestModeEnabled() } returns flowOf(false)
        every { settings.observeSmsMenuEnabled() } returns flowOf(false)
        coordinator = mockk(relaxed = true)
        viewModel = DebugViewModel(
            sessionTracker = RiskSessionTracker(),
            eventSink = mockk<RiskEventSink>(relaxed = true),
            evaluator = RiskEvaluatorImpl(),
            coordinator = coordinator,
            cooldownManager = mockk<BankingCooldownManager>(relaxed = true),
            settingsRepository = settings,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `all four Debug overlay paths use explicit Coordinator issuers`() = runTest(dispatcher) {
        viewModel.showTestOverlay()
        viewModel.simulateTelebankingDetection()
        viewModel.simulateRemoteAppDetection()
        viewModel.simulateRemoteThenBanking()
        runCurrent()

        verify(exactly = 1) { coordinator.showDebugOverlay(any<RiskEvent>()) }
        coVerify(exactly = 3) { coordinator.publishAndShowDebugOverlay(any<RiskEvent>()) }
    }
}
