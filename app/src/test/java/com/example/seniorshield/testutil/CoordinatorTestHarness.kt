package com.example.seniorshield.testutil

import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
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
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.orchestrator.DefaultRiskDetectionCoordinator
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

/**
 * [DefaultRiskDetectionCoordinator] 테스트 공용 하네스 — coordinator를 쓰는 모든 테스트 파일이
 * 이 한 벌의 협력자 조립을 공유한다(과거에는 파일마다 하네스를 복제했다).
 *
 * ## 하네스 설계
 * - **실물 협력자**(Android 의존 0): [RiskSessionTracker]·[AlertStateResolver]·[RiskEvaluatorImpl]·
 *   [RiskEventFactory] — Coordinator + 세션 + 평가 + AlertState 결정의 진짜 통합을 검증한다.
 * - **손수 fake**(인터페이스): 4 monitor + [RiskEventSink] + [GuardianRepository] — 각 신호를
 *   [StampedState]로 노출해 emission을 tick 단위로 제어한다. combine은 5소스가 모두 1회
 *   emit돼야 첫 발화하므로 초기값이 필수다.
 * - **mockk(relaxed)**: [RiskOverlayManager]·[BankingCooldownManager]·[RiskNotificationManager] —
 *   final + Context 생성자라 JVM 단위테스트에서 substitution 불가. relaxed 기본값(false/0/null)으로
 *   `isEndCallSuppressed()`·`isShowing()`·timestamp가 모두 비활성 경로가 되며, 상호작용은 verify로 확인.
 *
 * ## 생산 시점 스탬프 (A안 — 생성 순서 계약)
 * **실물 [sessionTracker]를 가장 먼저 생성**하고, fake들이 그 epoch 참조를 받는다.
 * [StampedState]의 `value` setter가 **대입 시점에 epoch를 정확히 1회** 읽어 [Produced]로 저장한다 —
 * observe() 경계(수집 시점)에서 스탬프하면 과거 stamped() 결함(수신 시점 epoch)을 복제하므로 금지.
 * stale 값 주입은 [StampedState.emitAt]으로 한다. fake의 observe()에는 실물 monitor와 동일한
 * source별 dUC 계약을 부착한다: CALL(신호 리스트 value-only — phase 축이 없어 (phase, signals)
 * 계약과 등가)·APP/BANKING/DEVICE_ENV(value-only), INSTALL(기본 동등성 = (value, epoch)).
 *
 * ## 시간 처리
 * TTL 만료 경로는 [FakeClock]을 sessionTracker·eventFactory·coordinator 세 협력자에 **공유 주입**해
 * 결정론적으로 검증한다([start]의 `fakeClock` 파라미터). 공유가 핵심 — 한 객체만 바꾸면 TTL 계산이 어긋난다.
 *
 * ## 코루틴 구동
 * `ioDispatcher = UnconfinedTestDispatcher(testScheduler)`로 Flow 전파를 eager하게 만든다.
 * maintenance pulse가 무한 `delay(15s)` 루프라 `advanceUntilIdle()`는 금지(영원히 돈다).
 * 각 테스트는 `runCurrent()`로 tick을 처리하고 종료 전 `coordinator.stop()`으로 잡을 정리한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTestHarness {

    // 생성 순서 계약: tracker가 먼저 — fake들의 StampedState가 대입 시점에 이 tracker의
    // userResetEpoch를 읽는다 (KDoc "생산 시점 스탬프" 참조).
    val sessionTracker = RiskSessionTracker()

    val callMonitor = FakeCallRiskMonitor { sessionTracker.userResetEpoch }
    val appUsageMonitor = FakeAppUsageRiskMonitor { sessionTracker.userResetEpoch }
    val appInstallMonitor = FakeAppInstallRiskMonitor { sessionTracker.userResetEpoch }
    val deviceEnvMonitor = FakeDeviceEnvironmentRiskMonitor { sessionTracker.userResetEpoch }
    val eventSink = FakeRiskEventSink()
    val guardianRepository = FakeGuardianRepository()

    val evaluator = RiskEvaluatorImpl()
    val eventFactory = RiskEventFactory()
    val alertStateResolver = AlertStateResolver()

    val overlayManager = mockk<RiskOverlayManager>(relaxed = true)
    val cooldownManager = mockk<BankingCooldownManager>(relaxed = true)
    val notificationManager = mockk<RiskNotificationManager>(relaxed = true)

    fun TestScope.start(fakeClock: FakeClock? = null): DefaultRiskDetectionCoordinator {
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
        // TTL 만료 테스트: 시간축을 동기화하기 위해 세 협력자에 동일 provider를 공유 주입.
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
}

// ── 손수 fake (StampedState 기반 emission 제어) ──────────────────────────────

/**
 * 값 대입 시점에 [Produced] 스탬프를 완성하는 테스트용 상태 홀더.
 * - `value = x`: **대입 시점에 [epoch]를 정확히 1회** 읽어 저장한다 (수집 시 재독 없음).
 * - [emitAt]: stale 값 직접 주입 — "reset 이전에 생산되어 버퍼에 대기하던 값" 모델링용.
 * 기존 테스트의 `xxx.value = ...` 호출부는 소스 변경 없이 그대로 동작한다.
 */
class StampedState<T>(initial: T, private val epoch: () -> Long) {
    val flow = MutableStateFlow(Produced(initial, epoch()))
    var value: T
        get() = flow.value.value
        set(v) {
            flow.value = Produced(v, epoch())
        }

    fun emitAt(v: T, atEpoch: Long) {
        flow.value = Produced(v, atEpoch)
    }
}

class FakeCallRiskMonitor(epoch: () -> Long) : CallRiskMonitor {
    val callContext = MutableStateFlow<CallMonitorState>(CallMonitorState.Idle)
    val callSignals = StampedState<List<RiskSignal>>(emptyList(), epoch)
    var callId: Long? = null
    var anchorHot: Boolean = false
    override fun observeCallContext(): Flow<CallMonitorState> = callContext.asStateFlow()

    // 실물 계약: (phase, signals) 비교·epoch 제외 — fake에는 phase 축이 없으므로
    // 신호 리스트 value-only 비교가 계약 등가다.
    override fun observeCallSignals(): Flow<Produced<List<RiskSignal>>> =
        callSignals.flow.distinctUntilChanged { a, b -> a.value == b.value }

    override fun currentCallId(): Long? = callId
    override fun clearTelebankingAnchor() {}
    override fun markCurrentCallConfirmedSafe(callId: Long) {}
    override fun isTelebankingAnchorHot(): Boolean = anchorHot
}

class FakeAppUsageRiskMonitor(epoch: () -> Long) : AppUsageRiskMonitor {
    val appSignals = StampedState<List<RiskSignal>>(emptyList(), epoch)
    val bankingForeground = StampedState(false, epoch)
    var latestBankingTs: Long? = null
    override fun observeAppUsageSignals(): Flow<Produced<List<RiskSignal>>> =
        appSignals.flow.distinctUntilChanged { a, b -> a.value == b.value }

    override fun observeBankingAppForeground(): Flow<Produced<Boolean>> =
        bankingForeground.flow.distinctUntilChanged { a, b -> a.value == b.value }

    override fun latestBankingForegroundEventTimestamp(windowMs: Long): Long? = latestBankingTs
}

class FakeAppInstallRiskMonitor(epoch: () -> Long) : AppInstallRiskMonitor {
    val installSignals = StampedState<List<RiskSignal>>(emptyList(), epoch)

    // 사건 flow 계약: (value, epoch) 기본 동등성 — StateFlow conflation이 그대로 계약이다.
    override fun observeInstallSignals(): Flow<Produced<List<RiskSignal>>> =
        installSignals.flow.asStateFlow()
}

class FakeDeviceEnvironmentRiskMonitor(epoch: () -> Long) : DeviceEnvironmentRiskMonitor {
    val deviceEnvSignals = StampedState<List<RiskSignal>>(emptyList(), epoch)
    override fun observeDeviceEnvironmentSignals(): Flow<Produced<List<RiskSignal>>> =
        deviceEnvSignals.flow.distinctUntilChanged { a, b -> a.value == b.value }
}

/**
 * 이력(pushed/recorded)과 현재 이벤트(currentEvent) 상태를 모두 추적하는 단일 fake.
 * 과거 no-op `clearCurrentRiskEvent`의 별도 사본(CountingRiskEventSink)과 갈라져 있던 것을 통합 —
 * cleanup 횟수 검증은 [clearCurrentCount]로 한다.
 */
class FakeRiskEventSink : RiskEventSink {
    val pushed = mutableListOf<RiskEvent>()
    val recorded = mutableListOf<RiskEvent>()
    var currentEvent: RiskEvent? = null
        private set
    var clearCurrentCount: Int = 0

    override suspend fun pushRiskEvent(event: RiskEvent) {
        pushed += event
        currentEvent = event
    }

    override suspend fun recordRiskEvent(event: RiskEvent) {
        recorded += event
    }

    override suspend fun updateCurrentRiskEvent(event: RiskEvent) {
        currentEvent = event
    }

    override fun clearCurrentRiskEvent() {
        clearCurrentCount += 1
        currentEvent = null
    }

    override suspend fun clearAll() {
        pushed.clear()
        recorded.clear()
        currentEvent = null
    }
}

class FakeGuardianRepository : GuardianRepository {
    override fun observeGuardians(): Flow<List<Guardian>> = flowOf(emptyList())
    override suspend fun addGuardian(guardian: Guardian): Boolean = true
    override suspend fun removeGuardian(id: String) {}
    override suspend fun getGuardians(): List<Guardian> = emptyList()
}
