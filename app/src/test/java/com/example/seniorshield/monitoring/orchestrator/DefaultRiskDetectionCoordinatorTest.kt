package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.monitoring.appinstall.AppInstallRiskMonitor
import com.example.seniorshield.monitoring.appusage.AppUsageRiskMonitor
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.DeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.evaluator.RiskEvaluatorImpl
import com.example.seniorshield.monitoring.event.RiskEventFactory
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultRiskDetectionCoordinator] 통합 테스트.
 *
 * ## 하네스 설계
 * - **실물 협력자**(Android 의존 0): `RiskSessionTracker`·`AlertStateResolver`·`RiskEvaluatorImpl`·
 *   `RiskEventFactory` — Coordinator + 세션 + 평가 + AlertState 결정의 진짜 통합을 검증한다.
 * - **손수 fake**(인터페이스): 4 monitor + `RiskEventSink` + `GuardianRepository` — 각 Flow를
 *   `MutableStateFlow`로 노출해 신호 emission을 tick 단위로 제어한다. combine은 5소스가 모두 1회
 *   emit돼야 첫 발화하므로 초기값이 필수다.
 * - **mockk(relaxed)**: `RiskOverlayManager`·`BankingCooldownManager`·`RiskNotificationManager` —
 *   final + Context 생성자라 JVM 단위테스트에서 substitution 불가. relaxed 기본값(false/0/null)으로
 *   `isEndCallSuppressed()`·`isShowing()`·timestamp가 모두 비활성 경로가 되며, 상호작용은 verify로 확인.
 *
 * ## 시간 처리
 * Coordinator·RiskSessionTracker·RiskEventFactory는 `clock` seam(`@VisibleForTesting internal var
 * clock: () -> Long`)을 노출한다. **set 기반 경로**(upgrade 해제, S2 동일-scope 재발화, 세션당 1회,
 * AlertState 분기)는 실 clock으로 충분하지만(틱 간 실제 경과는 ms 단위 → 모든 TTL 창보다 훨씬 짧다),
 * **TTL 만료 경로**(snooze 15분, S2 REC-REFIRE 30초 escape, ghost fallback 창)는 [FakeClock]을 세
 * 협력자에 **공유 주입**해 결정론적으로 검증한다([startCoordinator]의 `fakeClock` 파라미터). 공유가
 * 핵심 — 한 객체만 바꾸면 TTL 계산이 어긋난다.
 *
 * ## 코루틴 구동
 * `ioDispatcher = UnconfinedTestDispatcher(testScheduler)`로 Flow 전파를 eager하게 만든다.
 * anchor-hot mirror ticker가 무한 `delay(15s)` 루프라 `advanceUntilIdle()`는 금지(영원히 돈다).
 * 각 테스트는 `runCurrent()`로 tick을 처리하고 종료 전 `coordinator.stop()`으로 잡을 정리한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRiskDetectionCoordinatorTest {

    private val callMonitor = FakeCallRiskMonitor()
    private val appUsageMonitor = FakeAppUsageRiskMonitor()
    private val appInstallMonitor = FakeAppInstallRiskMonitor()
    private val deviceEnvMonitor = FakeDeviceEnvironmentRiskMonitor()
    private val eventSink = FakeRiskEventSink()
    private val guardianRepository = FakeGuardianRepository()

    private val evaluator = RiskEvaluatorImpl()
    private val eventFactory = RiskEventFactory()
    private val alertStateResolver = AlertStateResolver()
    private val sessionTracker = RiskSessionTracker()

    private val overlayManager = mockk<RiskOverlayManager>(relaxed = true)
    private val cooldownManager = mockk<BankingCooldownManager>(relaxed = true)
    private val notificationManager = mockk<RiskNotificationManager>(relaxed = true)

    private fun TestScope.startCoordinator(
        fakeClock: FakeClock? = null,
    ): DefaultRiskDetectionCoordinator {
        val coordinator = DefaultRiskDetectionCoordinator(
            callMonitor = callMonitor,
            appUsageMonitor = appUsageMonitor,
            appInstallMonitor = appInstallMonitor,
            deviceEnvMonitor = deviceEnvMonitor,
            evaluator = evaluator,
            eventFactory = eventFactory,
            eventSink = eventSink,
            notificationManager = notificationManager,
            overlayManager = overlayManager,
            cooldownManager = cooldownManager,
            sessionTracker = sessionTracker,
            alertStateResolver = alertStateResolver,
            guardianRepository = guardianRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // TTL 만료 테스트: 시간축을 동기화하기 위해 세 실물 협력자에 동일 provider를 공유 주입.
        // (coordinator.clock만 바꾸면 sessionTracker·eventFactory는 실 clock으로 남아 TTL이 어긋난다.)
        if (fakeClock != null) {
            sessionTracker.clock = fakeClock.provider
            eventFactory.clock = fakeClock.provider
            coordinator.clock = fakeClock.provider
        }
        coordinator.start()
        runCurrent()
        return coordinator
    }

    /** #1 snooze 활성 중 같은 통화에 upgrade trigger가 오면 snooze가 풀리고 팝업이 재발화한다. */
    @Test
    fun `snooze 활성 중 upgrade trigger 발생 시 snooze 해제 + 팝업 재발화`() = runTest {
        val coordinator = startCoordinator()

        // 통화 세션 생성: PASSIVE only → GUARDED (팝업 없음)
        callMonitor.callId = 123L
        callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        runCurrent()

        // "통화 경고 닫기" 효과 모사: 같은 callId로 snooze arm
        sessionTracker.snoozeForCall(123L)
        assertTrue("snooze armed", sessionTracker.isSnoozeActive())

        // 같은 통화 중 원격제어 앱(UPGRADE trigger) 등장
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()

        assertFalse("upgrade trigger가 snooze를 해제", sessionTracker.isSnoozeActive())
        verify(exactly = 1) { overlayManager.show(any(), any()) }

        coordinator.stop()
        runCurrent()
    }

    /** #2 30초 내 동일 원격제어 재출현(disappear→reappear)은 S2 REC-REFIRE debounce가 팝업을 억제한다. */
    @Test
    fun `S2 REC-REFIRE 30초 내 동일 원격제어 재출현은 팝업 억제`() = runTest {
        val coordinator = startCoordinator()

        // tick1: 원격제어 앱 등장 → INTERRUPT → 팝업 1회 + S2 arm
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()
        // tick2: 원격제어 사라짐(백그라운드) — notifiedActiveThreats에서 제거됨
        appUsageMonitor.appSignals.value = emptyList()
        runCurrent()
        // tick3: 30초 내 동일 원격제어 재출현 → S2 debounce가 재발화 억제
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()

        verify(exactly = 1) { overlayManager.show(any(), any()) }

        coordinator.stop()
        runCurrent()
    }

    /** #3 통화 세션에서 텔레뱅킹 발신은 CRITICAL로 승격되어 쿨다운을 세션당 1회만 발동(팝업은 같은 tick 생략). */
    @Test
    fun `텔레뱅킹 발신은 통화 세션에서 CRITICAL 쿨다운을 세션당 1회만 발동`() = runTest {
        val coordinator = startCoordinator()

        // 통화 세션(UNKNOWN_CALLER) → GUARDED
        callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        runCurrent()

        // 텔레뱅킹 발신 감지 → call 세션 + TRIGGER → CRITICAL → 쿨다운 발동
        callMonitor.callSignals.value =
            listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.TELEBANKING_AFTER_SUSPICIOUS)
        runCurrent()

        // 같은 세션에서 재평가(deviceEnv 변화로 re-combine, 텔레뱅킹 신호는 유지) → 세션당 1회 정책으로 재발동 없음
        deviceEnvMonitor.deviceEnvSignals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
        runCurrent()

        verify(exactly = 1) { cooldownManager.triggerIfNotActive(RiskLevel.CRITICAL, any(), any()) }
        // CRITICAL 쿨다운이 같은 tick에 떴으므로 팝업은 생략(modal surface 1개 원칙)
        verify(exactly = 0) { overlayManager.show(any(), any()) }

        coordinator.stop()
        runCurrent()
    }

    /** #4 PASSIVE 신호만으로 점수가 HIGH(≥50)여도 TRIGGER가 없으면 GUARDED에 머물러 팝업이 뜨지 않는다. */
    @Test
    fun `PASSIVE 신호만 HIGH 점수여도 팝업 없이 GUARDED 유지`() = runTest {
        val coordinator = startCoordinator()

        // UNKNOWN_CALLER(20) + LONG_CALL_DURATION(30) = 50 → RiskLevel.HIGH, 그러나 TRIGGER 0
        callMonitor.callSignals.value =
            listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)
        runCurrent()

        // 점수는 HIGH지만 AlertState는 GUARDED → 팝업/쿨다운 없음, notification만 발생
        verify(exactly = 0) { overlayManager.show(any(), any()) }
        verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any()) }
        verify(atLeast = 1) { notificationManager.notify(any()) }

        coordinator.stop()
        runCurrent()
    }

    /** #4b GUARDED escalation은 이력에만 기록(record), currentEvent 승격(push)은 INTERRUPT+부터. */
    @Test
    fun `GUARDED 세션 이벤트는 이력만 기록, currentEvent 승격은 INTERRUPT부터`() = runTest {
        val coordinator = startCoordinator()

        // PASSIVE only → GUARDED escalation: record 경로 (승격 없음, notification은 발생)
        callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        runCurrent()

        assertEquals("GUARDED: currentEvent 승격 0건", 0, eventSink.pushed.size)
        assertEquals("GUARDED: 이력 기록 1건", 1, eventSink.recorded.size)
        verify(atLeast = 1) { notificationManager.notify(any()) }

        // TRIGGER 등장 → INTERRUPT+ escalation: push 경로 (승격)
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()

        assertTrue("INTERRUPT+: currentEvent 승격 발생", eventSink.pushed.isNotEmpty())

        coordinator.stop()
        runCurrent()
    }

    /** #5 snooze 15분 TTL 경과 시 자동 해제 — TTL 미만은 유지하는 sibling과 대비(공유 clock 주입). */
    @Test
    fun `snooze 15분 TTL 경과 시 자동 해제, TTL 미만은 유지`() = runTest {
        val fakeClock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(fakeClock)

        // 통화 세션(UNKNOWN_CALLER) → GUARDED
        callMonitor.callId = 123L
        callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        runCurrent()

        // "통화 경고 닫기" 효과 모사: 같은 callId로 snooze arm (snoozedAt = fakeClock.now = 1_000_000)
        sessionTracker.snoozeForCall(123L)
        assertTrue("snooze armed", sessionTracker.isSnoozeActive())

        // sibling: TTL 미만(−1ms) 경과 + flow nudge(deviceEnv) → 같은 통화·upgrade 없음 → 유지
        fakeClock.advanceMs(SNOOZE_TTL_MS - 1)
        deviceEnvMonitor.deviceEnvSignals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
        runCurrent()
        assertTrue("TTL 미만이면 snooze 유지", sessionTracker.isSnoozeActive())

        // TTL 초과(+2ms 추가 → 누적 +1ms) + flow nudge → TTL 분기로 snooze 해제
        fakeClock.advanceMs(2)
        deviceEnvMonitor.deviceEnvSignals.value = emptyList()
        runCurrent()
        assertFalse("TTL 경과 시 snooze 해제", sessionTracker.isSnoozeActive())

        coordinator.stop()
        runCurrent()
    }

    /**
     * #6 S2 REC-REFIRE 30초 경과 후 동일 원격제어 재출현은 팝업이 재발화한다.
     * #2(window 내 억제)의 결정론적 complement — `advanceMs(S2_REC_REFIRE_TTL_MS + 1)`로 escape.
     */
    @Test
    fun `S2 REC-REFIRE 30초 경과 후 동일 원격제어 재출현은 팝업 재발화`() = runTest {
        val fakeClock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(fakeClock)

        // tick1: 원격제어 등장 → INTERRUPT → 팝업1 + S2 arm(lastFiredAt=1_000_000)
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()
        // tick2: 원격제어 사라짐 → notifiedActiveThreats에서 제거
        appUsageMonitor.appSignals.value = emptyList()
        runCurrent()
        // 30초 + 1ms 경과 → S2 TTL escape
        fakeClock.advanceMs(S2_REC_REFIRE_TTL_MS + 1)
        // tick3: 동일 원격제어 재출현 → TTL 경과로 억제 해제 → 팝업2
        appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        runCurrent()

        verify(exactly = 2) { overlayManager.show(any(), any()) }

        coordinator.stop()
        runCurrent()
    }

    /**
     * #7 ghost fallback 경계 — `latestBankingForegroundEventTimestamp()=null`로 fallback 강제.
     * `now - dismissedAt < lastCountdownSec*1000` 이면 거짓 전이(쿨다운 생략),
     * 창 경과 후 전이는 진짜 재진입(쿨다운 발동). 경계 양쪽을 coordinator.clock으로 검증.
     */
    @Test
    fun `ghost fallback - 쿨다운창 이내 전이는 생략, 창 경과 후 전이는 발동`() = runTest {
        val fakeClock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(fakeClock)

        // fallback 경로 강제: eventTs=null + 쿨다운 활성 구간 [showedAt, dismissedAt] stub
        every { cooldownManager.showedAtMillis } returns 1_000_000L
        every { cooldownManager.dismissedAtMillis } returns 1_010_000L
        every { cooldownManager.lastCountdownSec } returns 60   // fallbackWindow = 60_000ms
        appUsageMonitor.latestBankingTs = null

        // call-based GUARDED 세션
        callMonitor.callId = 777L
        callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        runCurrent()

        // 경계 A: now(1_040_000) - dismissedAt(1_010_000) = 30_000 < 60_000 → ghost → 쿨다운 생략
        fakeClock.advanceMs(40_000)
        appUsageMonitor.bankingForeground.value = true
        runCurrent()
        verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any()) }

        // 다음 false→true 전이를 만들기 위해 banking foreground를 내린다
        appUsageMonitor.bankingForeground.value = false
        runCurrent()

        // 경계 B: now(1_070_001) - dismissedAt(1_010_000) = 60_001 ≥ 60_000 → 진짜 재진입 → 쿨다운 발동
        fakeClock.advanceMs(30_001)
        appUsageMonitor.bankingForeground.value = true
        runCurrent()
        verify(exactly = 1) { cooldownManager.triggerIfNotActive(any(), any(), any()) }

        coordinator.stop()
        runCurrent()
    }
}

// ── 손수 fake (MutableStateFlow 기반 emission 제어) ──────────────────────────

private class FakeCallRiskMonitor : CallRiskMonitor {
    val callContext = MutableStateFlow<CallMonitorState>(CallMonitorState.Idle)
    val callSignals = MutableStateFlow<List<RiskSignal>>(emptyList())
    var callId: Long? = null
    var anchorHot: Boolean = false
    override fun observeCallContext(): Flow<CallMonitorState> = callContext.asStateFlow()
    override fun observeCallSignals(): Flow<List<RiskSignal>> = callSignals.asStateFlow()
    override fun currentCallId(): Long? = callId
    override fun clearTelebankingAnchor() {}
    override fun markCurrentCallConfirmedSafe(callId: Long) {}
    override fun isTelebankingAnchorHot(): Boolean = anchorHot
}

private class FakeAppUsageRiskMonitor : AppUsageRiskMonitor {
    val appSignals = MutableStateFlow<List<RiskSignal>>(emptyList())
    val bankingForeground = MutableStateFlow(false)
    var latestBankingTs: Long? = null
    override fun observeAppUsageSignals(): Flow<List<RiskSignal>> = appSignals.asStateFlow()
    override fun observeBankingAppForeground(): Flow<Boolean> = bankingForeground.asStateFlow()
    override fun latestBankingForegroundEventTimestamp(windowMs: Long): Long? = latestBankingTs
}

private class FakeAppInstallRiskMonitor : AppInstallRiskMonitor {
    val installSignals = MutableStateFlow<List<RiskSignal>>(emptyList())
    override fun observeInstallSignals(): Flow<List<RiskSignal>> = installSignals.asStateFlow()
}

private class FakeDeviceEnvironmentRiskMonitor : DeviceEnvironmentRiskMonitor {
    val deviceEnvSignals = MutableStateFlow<List<RiskSignal>>(emptyList())
    override fun observeDeviceEnvironmentSignals(): Flow<List<RiskSignal>> =
        deviceEnvSignals.asStateFlow()
}

private class FakeRiskEventSink : RiskEventSink {
    val pushed = mutableListOf<RiskEvent>()
    val recorded = mutableListOf<RiskEvent>()
    override suspend fun pushRiskEvent(event: RiskEvent) { pushed += event }
    override suspend fun recordRiskEvent(event: RiskEvent) { recorded += event }
    override suspend fun updateCurrentRiskEvent(event: RiskEvent) {}
    override fun clearCurrentRiskEvent() {}
    override suspend fun clearAll() {}
}

private class FakeGuardianRepository : GuardianRepository {
    override fun observeGuardians(): Flow<List<Guardian>> = flowOf(emptyList())
    override suspend fun addGuardian(guardian: Guardian): Boolean = true
    override suspend fun removeGuardian(id: String) {}
    override suspend fun getGuardians(): List<Guardian> = emptyList()
}
