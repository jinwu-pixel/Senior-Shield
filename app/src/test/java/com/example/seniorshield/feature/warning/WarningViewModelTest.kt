package com.example.seniorshield.feature.warning

import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarningViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var guardianRepository: FakeGuardianRepository
    private lateinit var riskRepository: FakeRiskRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var sessionTracker: RiskSessionTracker
    private lateinit var eventSink: FakeRiskEventSink
    private lateinit var callRiskMonitor: FakeCallRiskMonitor
    private lateinit var coordinator: FakeRiskDetectionCoordinator
    private lateinit var viewModel: WarningViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        guardianRepository = FakeGuardianRepository()
        riskRepository = FakeRiskRepository()
        settingsRepository = FakeSettingsRepository()
        sessionTracker = RiskSessionTracker()
        eventSink = FakeRiskEventSink()
        callRiskMonitor = FakeCallRiskMonitor()
        coordinator = FakeRiskDetectionCoordinator()
        viewModel = WarningViewModel(
            guardianRepository,
            riskRepository,
            settingsRepository,
            sessionTracker,
            eventSink,
            callRiskMonitor,
            coordinator,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // 1. 초기 상태는 기본값
    // -----------------------------------------------------------------------

    @Test
    fun `초기 상태는 기본값`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        val state = viewModel.uiState.value

        assertTrue(state.guardians.isEmpty())
        assertFalse(state.showGuardianPicker)
        assertNull(state.message)
        assertNull(state.detectedEventTitle)
        assertNull(state.detectedEventDescription)
        assertNull(state.detectedEventLevel)
    }

    // -----------------------------------------------------------------------
    // 2. 보호자가 있으면 uiState에 반영
    // -----------------------------------------------------------------------

    @Test
    fun `보호자가 있으면 uiState에 반영`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        val guardians = listOf(
            Guardian(id = "1", name = "홍길동", phoneNumber = "010-1234-5678", relationship = "자녀"),
            Guardian(id = "2", name = "이영희", phoneNumber = "010-8765-4321", relationship = "배우자"),
        )

        guardianRepository.setGuardians(guardians)

        val state = viewModel.uiState.value

        assertEquals(2, state.guardians.size)
        assertEquals("홍길동", state.guardians[0].name)
        assertEquals("이영희", state.guardians[1].name)
    }

    // -----------------------------------------------------------------------
    // 3. 위험 이벤트가 있으면 uiState에 반영
    // -----------------------------------------------------------------------

    @Test
    fun `위험 이벤트가 있으면 uiState에 반영`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        val event = RiskEvent(
            id = "event-1",
            title = "보이스피싱 의심",
            description = "알 수 없는 발신자와 장시간 통화가 감지되었습니다.",
            occurredAtMillis = 1_000_000L,
            level = RiskLevel.HIGH,
            signals = listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
        )

        riskRepository.setCurrentEvent(event)

        val state = viewModel.uiState.value

        assertEquals("보이스피싱 의심", state.detectedEventTitle)
        assertEquals("알 수 없는 발신자와 장시간 통화가 감지되었습니다.", state.detectedEventDescription)
        assertEquals(RiskLevel.HIGH, state.detectedEventLevel)
    }

    // -----------------------------------------------------------------------
    // 4. showGuardianPicker 호출 시 showGuardianPicker가 true
    // -----------------------------------------------------------------------

    @Test
    fun `showGuardianPicker 호출 시 showGuardianPicker가 true`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.showGuardianPicker()

        assertTrue(viewModel.uiState.value.showGuardianPicker)
    }

    // -----------------------------------------------------------------------
    // 5. dismissGuardianPicker 호출 시 showGuardianPicker가 false
    // -----------------------------------------------------------------------

    @Test
    fun `dismissGuardianPicker 호출 시 showGuardianPicker가 false`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.showGuardianPicker()
        assertTrue(viewModel.uiState.value.showGuardianPicker)

        viewModel.dismissGuardianPicker()

        assertFalse(viewModel.uiState.value.showGuardianPicker)
    }

    // -----------------------------------------------------------------------
    // 6. clearMessage 호출 시 message가 null
    // -----------------------------------------------------------------------

    @Test
    fun `clearMessage 호출 시 message가 null`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        // _message는 외부에서 직접 설정할 수 없으므로,
        // clearMessage 호출 후 null인지 확인하는 방식으로 검증한다.
        viewModel.clearMessage()

        assertNull(viewModel.uiState.value.message)
    }

    // -----------------------------------------------------------------------
    // 7. confirmSafe 호출 시 세션 초기화
    // -----------------------------------------------------------------------

    @Test
    fun `confirmSafe 호출 시 세션이 초기화됨`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        // 세션 생성
        sessionTracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(sessionTracker.sessionState.value)

        // 안전 확인
        viewModel.confirmSafe()

        assertNull(sessionTracker.sessionState.value)
    }

    // -----------------------------------------------------------------------
    // T-A7. confirmSafe 호출 시 통일 종료 시퀀스 모두 실행 (B-6)
    // -----------------------------------------------------------------------

    @Test
    fun `T-A7 confirmSafe 호출 시 reset clearAnchor clearEvent 모두 호출`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        // 사전 상태: 세션 + currentEvent 존재
        sessionTracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        eventSink.pushRiskEvent(
            RiskEvent(
                id = "event-1",
                title = "test",
                description = "test",
                occurredAtMillis = 1L,
                level = RiskLevel.HIGH,
                signals = listOf(RiskSignal.UNKNOWN_CALLER),
            )
        )
        assertNotNull(sessionTracker.sessionState.value)
        assertNotNull(eventSink.currentEvent.value)
        assertEquals(0, callRiskMonitor.clearTelebankingAnchorCallCount)

        // 안전 확인
        viewModel.confirmSafe()

        // reset → session null
        assertNull(sessionTracker.sessionState.value)
        // clearTelebankingAnchor 호출
        assertEquals(1, callRiskMonitor.clearTelebankingAnchorCallCount)
        // clearCurrentRiskEvent → currentEvent null
        assertNull(eventSink.currentEvent.value)
    }

    // -----------------------------------------------------------------------
    // 8~10. Behavior Check(자가확인) — 휘발성·비침습
    // -----------------------------------------------------------------------

    @Test
    fun `behaviorCheck 응답이 uiState에 반영되고 anyYes가 파생됨`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        assertTrue(viewModel.uiState.value.behaviorCheckAnswers.isEmpty())
        assertFalse(viewModel.uiState.value.behaviorCheckAnyYes)

        viewModel.answerBehaviorCheck(0, yes = false)
        viewModel.answerBehaviorCheck(1, yes = true)

        val state = viewModel.uiState.value
        assertEquals(false, state.behaviorCheckAnswers[0])
        assertEquals(true, state.behaviorCheckAnswers[1])
        assertTrue(state.behaviorCheckAnyYes)

        // 같은 문항 재응답은 덮어쓴다 → 마지막 "예"가 사라지면 anyYes도 false
        viewModel.answerBehaviorCheck(1, yes = false)
        assertFalse(viewModel.uiState.value.behaviorCheckAnyYes)
    }

    @Test
    fun `behaviorCheck 응답은 ViewModel 재생성 시 초기화됨 - 휘발성`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.answerBehaviorCheck(0, yes = true)
        assertTrue(viewModel.uiState.value.behaviorCheckAnyYes)

        // 화면 이탈 후 재진입 = 새 ViewModel 인스턴스 — 응답은 어디에도 저장되지 않는다
        val recreated = WarningViewModel(
            guardianRepository,
            riskRepository,
            settingsRepository,
            sessionTracker,
            eventSink,
            callRiskMonitor,
            coordinator,
        )
        backgroundScope.launch(testDispatcher) { recreated.uiState.collect {} }

        assertTrue(recreated.uiState.value.behaviorCheckAnswers.isEmpty())
        assertFalse(recreated.uiState.value.behaviorCheckAnyYes)
    }

    @Test
    fun `behaviorCheck 응답은 세션·모니터·이벤트에 전달되지 않음 - 비침습`() = runTest {
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect {} }

        viewModel.answerBehaviorCheck(0, yes = true)
        viewModel.answerBehaviorCheck(4, yes = true)

        // monitoring 축 부수효과 0 — 세션 미생성, anchor/safe-confirm/mirror/이벤트 호출 0
        assertNull(sessionTracker.sessionState.value)
        assertEquals(0, callRiskMonitor.clearTelebankingAnchorCallCount)
        assertNull(callRiskMonitor.lastSafeConfirmedCallId)
        assertEquals(0, coordinator.refreshAnchorHotNowCallCount)
        assertNull(eventSink.currentEvent.value)
    }
}

// ---------------------------------------------------------------------------
// Fake 구현
// ---------------------------------------------------------------------------

private class FakeGuardianRepository : GuardianRepository {
    private val _guardians = MutableStateFlow<List<Guardian>>(emptyList())

    override fun observeGuardians(): Flow<List<Guardian>> = _guardians

    override suspend fun addGuardian(guardian: Guardian): Boolean {
        val current = _guardians.value
        return if (current.size < Guardian.MAX_COUNT) {
            _guardians.value = current + guardian
            true
        } else {
            false
        }
    }

    override suspend fun removeGuardian(id: String) {
        _guardians.value = _guardians.value.filter { it.id != id }
    }

    override suspend fun getGuardians(): List<Guardian> = _guardians.value

    fun setGuardians(list: List<Guardian>) {
        _guardians.value = list
    }
}

private class FakeRiskRepository : RiskRepository {
    private val _currentEvent = MutableStateFlow<RiskEvent?>(null)
    private val _recentEvents = MutableStateFlow<List<RiskEvent>>(emptyList())

    override fun getCurrentRiskEvent(): Flow<RiskEvent?> = _currentEvent

    override fun getRecentRiskEvents(): Flow<List<RiskEvent>> = _recentEvents

    override suspend fun countEventsSince(sinceMillis: Long): Int = 0

    fun setCurrentEvent(event: RiskEvent?) {
        _currentEvent.value = event
    }
}

private class FakeRiskEventSink : RiskEventSink {
    private val _currentEvent = MutableStateFlow<RiskEvent?>(null)
    val currentEvent: StateFlow<RiskEvent?> = _currentEvent

    override suspend fun pushRiskEvent(event: RiskEvent) { _currentEvent.value = event }
    override suspend fun updateCurrentRiskEvent(event: RiskEvent) { _currentEvent.value = event }
    override fun clearCurrentRiskEvent() { _currentEvent.value = null }
    override suspend fun clearAll() { _currentEvent.value = null }
}

private class FakeCallRiskMonitor : CallRiskMonitor {
    var clearTelebankingAnchorCallCount = 0
    var lastSafeConfirmedCallId: Long? = null

    override fun observeCallContext(): Flow<CallMonitorState> = flowOf(CallMonitorState.Idle)
    override fun observeCallSignals(): Flow<List<RiskSignal>> = flowOf(emptyList())
    override fun currentCallId(): Long? = null
    override fun clearTelebankingAnchor() { clearTelebankingAnchorCallCount++ }
    override fun markCurrentCallConfirmedSafe(callId: Long) { lastSafeConfirmedCallId = callId }
    override fun isTelebankingAnchorHot(): Boolean = false
}

private class FakeRiskDetectionCoordinator : RiskDetectionCoordinator {
    var refreshAnchorHotNowCallCount = 0
    override fun start() {}
    override fun stop() {}
    override val anchorHotState: StateFlow<Boolean> = MutableStateFlow(false)
    override fun refreshAnchorHotNow() { refreshAnchorHotNowCallCount++ }
}

private class FakeSettingsRepository : SettingsRepository {
    private val _onboardingCompleted = MutableStateFlow(false)
    private val _smsAlertEnabled = MutableStateFlow(false)
    private val _testModeEnabled = MutableStateFlow(false)
    private val _smsMenuEnabled = MutableStateFlow(false)

    override fun observeOnboardingCompleted(): Flow<Boolean> = _onboardingCompleted
    override suspend fun setOnboardingCompleted(completed: Boolean) { _onboardingCompleted.value = completed }

    override fun observeSmsAlertEnabled(): Flow<Boolean> = _smsAlertEnabled
    override suspend fun setSmsAlertEnabled(enabled: Boolean) { _smsAlertEnabled.value = enabled }

    override fun observeTestModeEnabled(): Flow<Boolean> = _testModeEnabled
    override suspend fun setTestModeEnabled(enabled: Boolean) { _testModeEnabled.value = enabled }

    override fun observeSmsMenuEnabled(): Flow<Boolean> = _smsMenuEnabled
    override suspend fun setSmsMenuEnabled(enabled: Boolean) { _smsMenuEnabled.value = enabled }
}
