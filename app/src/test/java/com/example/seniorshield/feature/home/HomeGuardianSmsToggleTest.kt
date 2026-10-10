package com.example.seniorshield.feature.home

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.orchestrator.WarningNavigationPayload
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.testutil.FakeGuardianRepository
import com.example.seniorshield.testutil.FakeSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeGuardianSmsToggleTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private val viewModels = mutableListOf<HomeViewModel>()
    private val guardian = Guardian("guardian-1", "Test Guardian", "01000000000")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        mockkStatic(ContextCompat::class)
        mockkStatic(Settings::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns
            PackageManager.PERMISSION_DENIED
        every { Settings.canDrawOverlays(any()) } returns false
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun smsMenuOffWithGuardianIsFalse() = runTest {
        val viewModel = homeViewModel(HomeSettingsRepository(initialEnabled = false))
        subscribe(viewModel)
        runCurrent()

        assertTrue(viewModel.uiState.value.hasGuardian)
        assertEquals(guardian.phoneNumber, viewModel.uiState.value.guardianPhone)
        assertFalse(viewModel.uiState.value.smsMenuEnabled)
    }

    @Test
    fun smsMenuOnWithGuardianIsTrue() = runTest {
        val viewModel = homeViewModel(HomeSettingsRepository(initialEnabled = true))
        subscribe(viewModel)
        runCurrent()

        assertTrue(viewModel.uiState.value.hasGuardian)
        assertEquals(guardian.phoneNumber, viewModel.uiState.value.guardianPhone)
        assertTrue(viewModel.uiState.value.smsMenuEnabled)
    }

    @Test
    fun settingFailureHidesSmsMenuAndHomeKeepsUpdating() = runTest {
        val settings = HomeSettingsRepository(initialEnabled = true)
        val risk = HomeRiskRepository()
        val viewModel = homeViewModel(settings, risk)
        subscribe(viewModel)
        runCurrent()

        // Fail after the ON emission: retaining the last setting must not keep SMS visible.
        settings.fail()
        runCurrent()
        assertFalse(viewModel.uiState.value.smsMenuEnabled)

        val event = riskEvent("after-failure", RiskLevel.CRITICAL)
        risk.current.value = event
        risk.recent.value = listOf(event)
        runCurrent()

        assertTrue(viewModel.uiState.value.hasGuardian)
        assertEquals(HomeStatus.WARNING, viewModel.uiState.value.homeStatus)
        assertEquals(RiskLevel.CRITICAL, viewModel.uiState.value.currentRiskLevel)
        assertTrue(viewModel.uiState.value.currentRiskBody.contains(event.title))
        assertEquals(1, viewModel.uiState.value.recentEventCount)
        assertFalse(viewModel.uiState.value.smsMenuEnabled)
    }

    @Test
    fun silentSettingFlowDoesNotFreezeHomeAtSafe() = runTest {
        val risk = HomeRiskRepository()
        risk.current.value = riskEvent("initial-risk", RiskLevel.HIGH)
        val viewModel = homeViewModel(
            HomeSettingsRepository(initialEnabled = false, neverEmit = true),
            risk,
        )
        subscribe(viewModel)
        runCurrent()

        assertEquals(HomeStatus.WARNING, viewModel.uiState.value.homeStatus)
        assertEquals(RiskLevel.HIGH, viewModel.uiState.value.currentRiskLevel)
        assertFalse(viewModel.uiState.value.smsMenuEnabled)

        val event = riskEvent("updated-risk", RiskLevel.CRITICAL)
        risk.current.value = event
        runCurrent()

        assertEquals(HomeStatus.WARNING, viewModel.uiState.value.homeStatus)
        assertEquals(RiskLevel.CRITICAL, viewModel.uiState.value.currentRiskLevel)
        assertTrue(viewModel.uiState.value.currentRiskBody.contains(event.title))
    }

    @Test
    fun toggleAfterFailureIsIgnoredWhileHomeRemainsSubscribed() = runTest {
        val settings = HomeSettingsRepository(initialEnabled = false).apply { fail() }
        val risk = HomeRiskRepository()
        val viewModel = homeViewModel(settings, risk)
        subscribe(viewModel)
        runCurrent()
        assertTrue(viewModel.uiState.value.hasGuardian)
        assertFalse(viewModel.uiState.value.smsMenuEnabled)

        settings.setSmsMenuEnabled(true)
        // Keep the subscriber alive beyond WhileSubscribed's stop timeout; no re-entry occurs.
        advanceTimeBy(5_001)
        val event = riskEvent("still-subscribed", RiskLevel.HIGH)
        risk.current.value = event
        runCurrent()

        assertEquals(HomeStatus.WARNING, viewModel.uiState.value.homeStatus)
        assertTrue(viewModel.uiState.value.currentRiskBody.contains(event.title))
        assertFalse(viewModel.uiState.value.smsMenuEnabled)
    }

    private fun TestScope.subscribe(viewModel: HomeViewModel): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

    private fun homeViewModel(
        settings: SettingsRepository,
        risk: RiskRepository = HomeRiskRepository(),
    ): HomeViewModel {
        val coordinator = mockk<RiskDetectionCoordinator>()
        every { coordinator.anchorHotState } returns MutableStateFlow(false)
        every { coordinator.warningNavigationEvents } returns MutableSharedFlow<WarningNavigationPayload>()
        val tracker = RiskSessionTracker().also {
            it.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        }
        return HomeViewModel(
            riskRepository = risk,
            sessionTracker = tracker,
            alertStateResolver = AlertStateResolver(),
            guardianRepository = FakeGuardianRepository().apply { guardians = listOf(guardian) },
            settingsRepository = settings,
            coordinator = coordinator,
            context = context,
        ).also { viewModels += it }
    }

    private fun riskEvent(id: String, level: RiskLevel) = RiskEvent(
        id = id,
        title = id,
        description = "Home risk update fixture",
        occurredAtMillis = 1_000_000L,
        level = level,
        signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
    )
}

private class HomeRiskRepository : RiskRepository {
    val current = MutableStateFlow<RiskEvent?>(null)
    val recent = MutableStateFlow<List<RiskEvent>>(emptyList())

    override fun getCurrentRiskEvent(): Flow<RiskEvent?> = current
    override fun getRecentRiskEvents(): Flow<List<RiskEvent>> = recent
    override suspend fun countEventsSince(sinceMillis: Long): Int = recent.value.size
}

// Reuse the shared fake for unrelated settings; its one-shot SMS Flow cannot model live changes.
private class HomeSettingsRepository(
    initialEnabled: Boolean,
    private val neverEmit: Boolean = false,
) : SettingsRepository by FakeSettingsRepository() {
    private val smsMenu = MutableStateFlow<Result<Boolean>>(Result.success(initialEnabled))

    override fun observeSmsMenuEnabled(): Flow<Boolean> = flow {
        if (neverEmit) awaitCancellation()
        smsMenu.collect { emit(it.getOrThrow()) }
    }

    override suspend fun setSmsMenuEnabled(enabled: Boolean) {
        smsMenu.value = Result.success(enabled)
    }

    fun fail() {
        smsMenu.value = Result.failure(IOException("settings datastore read failed"))
    }
}
