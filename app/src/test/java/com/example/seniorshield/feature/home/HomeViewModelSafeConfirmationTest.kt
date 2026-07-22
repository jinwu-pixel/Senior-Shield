package com.example.seniorshield.feature.home

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOrigin
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationRequest
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationSubject
import com.example.seniorshield.monitoring.orchestrator.WarningNavigationPayload
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSafeConfirmationTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context

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
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `Home delegates safe confirmation and refreshes weekly snapshot only on success`() = runTest {
        val repository = CountingRiskRepository()
        val tracker = RiskSessionTracker().also {
            it.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        }
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = true)
        val viewModel = homeViewModel(repository, tracker, coordinator)
        runCurrent()
        val weeklyReadsBefore = repository.countEventsSinceCallCount
        val sessionBefore = tracker.sessionState.value
        val epochBefore = tracker.userResetEpoch

        assertTrue(viewModel.confirmSafe())
        runCurrent()

        assertEquals(1, coordinator.captureCallCount)
        assertEquals(1, coordinator.confirmCallCount)
        assertEquals(SafeConfirmationOrigin.HOME, coordinator.lastOrigin)
        assertEquals(weeklyReadsBefore + 1, repository.countEventsSinceCallCount)
        assertSame("ViewModel must not reset tracker directly", sessionBefore, tracker.sessionState.value)
        assertEquals(epochBefore, tracker.userResetEpoch)
    }

    @Test
    fun `Home failure does not refresh weekly snapshot or mutate tracker`() = runTest {
        val repository = CountingRiskRepository()
        val tracker = RiskSessionTracker().also {
            it.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        }
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = false)
        val viewModel = homeViewModel(repository, tracker, coordinator)
        runCurrent()
        val weeklyReadsBefore = repository.countEventsSinceCallCount
        val sessionBefore = tracker.sessionState.value
        val epochBefore = tracker.userResetEpoch

        assertFalse(viewModel.confirmSafe())
        runCurrent()

        assertEquals(weeklyReadsBefore, repository.countEventsSinceCallCount)
        assertSame(sessionBefore, tracker.sessionState.value)
        assertEquals(epochBefore, tracker.userResetEpoch)
    }

    @Test
    fun `published warning navigation carries event identity and reset epoch`() = runTest {
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = true)
        val viewModel = homeViewModel(CountingRiskRepository(), RiskSessionTracker(), coordinator)
        val payload = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 0L)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.navigateToWarning.first()
        }

        coordinator.emitWarningNavigation(payload)

        assertEquals(payload, received.await())
        assertTrue(viewModel.isWarningNavigationPayloadCurrent(payload))
    }

    @Test
    fun `warning navigation emitted before safe confirmation is stale at consume time`() = runTest {
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = true)
        val viewModel = homeViewModel(CountingRiskRepository(), RiskSessionTracker(), coordinator)
        val payload = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 0L)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.navigateToWarning.first()
        }
        coordinator.emitWarningNavigation(payload)
        val emitted = received.await()

        assertTrue(viewModel.confirmSafe())

        assertFalse(viewModel.isWarningNavigationPayloadCurrent(emitted))
    }

    @Test
    fun `failed confirmation after reset cannot revive an old navigation payload`() = runTest {
        val tracker = RiskSessionTracker()
        val coordinator = RecordingSafeConfirmationCoordinator(
            confirmResult = false,
            onConfirm = { tracker.resetAfterUserConfirmedSafe() },
        )
        val viewModel = homeViewModel(CountingRiskRepository(), tracker, coordinator)
        val payload = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 0L)

        assertFalse(viewModel.confirmSafe())
        assertEquals(1L, tracker.userResetEpoch)
        assertEquals(0L, coordinator.currentRequest?.expectedResetEpoch)

        assertFalse(viewModel.isWarningNavigationPayloadCurrent(payload))
    }

    @Test
    fun `warning navigation never substitutes an eventless Session subject`() = runTest {
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = true)
        val viewModel = homeViewModel(CountingRiskRepository(), RiskSessionTracker(), coordinator)
        val payload = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 0L)
        coordinator.currentRequest = SafeConfirmationRequest(
            origin = SafeConfirmationOrigin.HOME,
            subject = SafeConfirmationSubject.Session("session-1"),
            expectedResetEpoch = payload.expectedResetEpoch,
            liveCallId = null,
            signals = emptySet(),
        )

        assertFalse(viewModel.isWarningNavigationPayloadCurrent(payload))
    }

    @Test
    fun `same event and epoch dedupes while same event in a fresh epoch is delivered`() = runTest {
        val coordinator = RecordingSafeConfirmationCoordinator(confirmResult = true)
        val tracker = RiskSessionTracker()
        val viewModel = homeViewModel(CountingRiskRepository(), tracker, coordinator)
        val epoch0 = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 0L)
        val epoch1 = WarningNavigationPayload(eventId = "event-1", expectedResetEpoch = 1L)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.navigateToWarning.take(2).toList()
        }

        coordinator.emitWarningNavigation(epoch0)
        coordinator.emitWarningNavigation(epoch0)
        coordinator.emitWarningNavigation(epoch1)

        assertEquals(listOf(epoch0, epoch1), received.await())
        coordinator.currentRequest = SafeConfirmationRequest(
            origin = SafeConfirmationOrigin.HOME,
            subject = SafeConfirmationSubject.Event(epoch1.eventId),
            expectedResetEpoch = epoch1.expectedResetEpoch,
            liveCallId = null,
            signals = emptySet(),
            expectedEventId = epoch1.eventId,
        )
        assertFalse(viewModel.isWarningNavigationPayloadCurrent(epoch0))
        tracker.resetAfterUserConfirmedSafe()
        assertTrue(viewModel.isWarningNavigationPayloadCurrent(epoch1))
    }

    private fun homeViewModel(
        repository: RiskRepository,
        tracker: RiskSessionTracker,
        coordinator: RiskDetectionCoordinator,
    ) = HomeViewModel(
        riskRepository = repository,
        sessionTracker = tracker,
        alertStateResolver = AlertStateResolver(),
        guardianRepository = EmptyGuardianRepository(),
        coordinator = coordinator,
        context = context,
    )
}

private class RecordingSafeConfirmationCoordinator(
    var confirmResult: Boolean,
    private val onConfirm: (SafeConfirmationRequest) -> Unit = {},
) : RiskDetectionCoordinator {
    override val anchorHotState: StateFlow<Boolean> = MutableStateFlow(false)
    private val warningNavigation = MutableSharedFlow<WarningNavigationPayload>(
        extraBufferCapacity = 4,
    )
    override val warningNavigationEvents: Flow<WarningNavigationPayload> = warningNavigation
    var captureCallCount = 0
    var confirmCallCount = 0
    var lastOrigin: SafeConfirmationOrigin? = null
    var currentRequest: SafeConfirmationRequest? = SafeConfirmationRequest(
        origin = SafeConfirmationOrigin.HOME,
        subject = SafeConfirmationSubject.Event("event-1"),
        expectedResetEpoch = 0L,
        liveCallId = null,
        signals = emptySet(),
        expectedEventId = "event-1",
    )

    override fun start() = Unit
    override fun stop() = Unit
    override fun refreshAnchorHotNow() = Unit
    override fun showDebugOverlay(event: RiskEvent) = Unit
    override suspend fun publishAndShowDebugOverlay(event: RiskEvent) = Unit

    override fun captureSafeConfirmationRequest(
        origin: SafeConfirmationOrigin,
    ): SafeConfirmationRequest? {
        captureCallCount += 1
        lastOrigin = origin
        return currentRequest?.copy(origin = origin)
    }

    override fun confirmSafe(request: SafeConfirmationRequest): Boolean {
        confirmCallCount += 1
        onConfirm(request)
        if (confirmResult) currentRequest = null
        return confirmResult
    }

    fun emitWarningNavigation(payload: WarningNavigationPayload) {
        check(warningNavigation.tryEmit(payload))
    }
}

private class CountingRiskRepository : RiskRepository {
    private val current = MutableStateFlow<RiskEvent?>(null)
    private val recent = MutableStateFlow<List<RiskEvent>>(emptyList())
    var countEventsSinceCallCount = 0

    override fun getRecentRiskEvents(): Flow<List<RiskEvent>> = recent
    override fun getCurrentRiskEvent(): Flow<RiskEvent?> = current
    override suspend fun countEventsSince(sinceMillis: Long): Int {
        countEventsSinceCallCount += 1
        return 0
    }
}

private class EmptyGuardianRepository : GuardianRepository {
    override fun observeGuardians(): Flow<List<Guardian>> = MutableStateFlow(emptyList())
    override suspend fun addGuardian(guardian: Guardian): Boolean = true
    override suspend fun removeGuardian(id: String) = Unit
    override suspend fun getGuardians(): List<Guardian> = emptyList()
}
