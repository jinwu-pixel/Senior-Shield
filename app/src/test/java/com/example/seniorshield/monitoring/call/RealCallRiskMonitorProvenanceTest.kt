package com.example.seniorshield.monitoring.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.model.CallContext
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.model.CallState
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RealCallRiskMonitor] 생산 provenance(A안) 검증 — legacy(PhoneStateListener) 경로 실구동.
 *
 * plain JVM은 SDK_INT=0이라 legacy 분기를 탄다. initialClaimed 선점·SEED 중립화·진입 게이트는
 * API31과 공통 규칙이므로 legacy 구동으로 계약을 검증하고, API31 경로는 seam 2종
 * (`sdkIntProvider`, `callbackExecutorFactory`=manual executor)과 [deliverState](framework의
 * callback 전달 executor 큐 경계 통과)로 enqueue↔reset↔실행 창까지 결정론 재현한다
 * (라운드 12·16). 실기 미확인은 실제 framework 타이밍·방송 순서뿐이다.
 *
 * 하네스: 실물 [RiskSessionTracker](epoch·clean-slate 실동작), mockk TelephonyManager에서
 * listener를 캡처해 테스트가 직접 콜백을 구동한다. shareIn upstream이 실제 Default dispatcher에서
 * 돌므로 방출은 [Channel] 수신 + timeout으로 단언한다 (sleep 폴링 없음).
 */
class RealCallRiskMonitorProvenanceTest {

    private val fakeClock = FakeClock(now = 1_000_000L)
    private val tracker = RiskSessionTracker().also { it.clock = fakeClock.provider }
    private val listenerSlot = slot<PhoneStateListener>()
    private val telephonyManager = mockk<TelephonyManager>(relaxed = true) {
        every { listen(capture(listenerSlot), any()) } returns Unit
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.TELEPHONY_SERVICE) } returns telephonyManager
    }
    private val contactChecker = mockk<CallerContactChecker> {
        every { checkCaller(any()) } returns CallerCheckResult.NOT_IN_CONTACTS
    }
    private val testModeEnabled = MutableStateFlow(false)
    private val settingsRepository = mockk<SettingsRepository> {
        every { observeTestModeEnabled() } returns testModeEnabled
    }
    private val bankArsRegistry = mockk<BankArsRegistry> {
        every { matches(any()) } returns true
    }
    private val mapper = mockk<CallSignalMapper>(relaxed = true)
    private val monitor = RealCallRiskMonitor(
        context,
        mapper,
        contactChecker,
        settingsRepository,
        bankArsRegistry,
        tracker,
    ).apply {
        clock = fakeClock.provider
        monotonicClock = fakeClock.provider
    }

    private val emissions = Channel<Produced<List<RiskSignal>>>(Channel.UNLIMITED)
    private var collectorJob: Job? = null

    @After
    fun tearDown() {
        collectorJob?.cancel()
        OutgoingCallReceiver.clear()
    }

    private suspend fun startCollector(): Job {
        val job = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        collectorJob = job
        withTimeout(5_000) {
            while (!listenerSlot.isCaptured) delay(10)
        }
        return job
    }

    private suspend fun awaitEmission(): Produced<List<RiskSignal>> =
        withTimeout(5_000) { emissions.receive() }

    private suspend fun awaitEmissionWhere(
        predicate: (Produced<List<RiskSignal>>) -> Boolean,
    ): Produced<List<RiskSignal>> = withTimeout(5_000) {
        var e = emissions.receive()
        while (!predicate(e)) e = emissions.receive()
        e
    }

    private suspend fun assertNoFurtherEmission(waitMs: Long = 500) {
        assertNull("추가 방출이 없어야 함", withTimeoutOrNull(waitMs) { emissions.receive() })
    }

    private fun drive(state: Int, number: String? = null) {
        listenerSlot.captured.onCallStateChanged(state, number)
    }

    /** 계약 1: 첫 콜백(암묵 seed)은 SEED — 상태만 복원하고 위험신호·부수효과를 파생하지 않는다. */
    @Test
    fun `first callback is treated as seed and derives no signals`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")

        val first = awaitEmission()
        assertTrue("SEED는 중립 방출", first.value.isEmpty())
        assertEquals("SEED epoch는 callState 관측 시점", 0L, first.producedAtEpoch)
        assertNotNull("상태 복원 — snooze 바인딩용 callId", monitor.currentCallId())
        assertTrue("반복호출 버퍼 미기록 (부수효과 금지)", monitor.recentUnknownCalls.isEmpty())
        assertNull("anchor 미장전", monitor.lastSuspiciousCallEndedAt)
        assertNoFurtherEmission()
    }

    /** SEED 이후의 실제 전이는 CALLBACK — 생산 시점 epoch로 신호를 파생한다. */
    @Test
    fun `transitions after the seed derive signals with the production epoch`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE) // 암묵 seed (IDLE)
        assertTrue(awaitEmission().value.isEmpty())

        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222") // 번호 캡처 (중립 — dUC 흡수)
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")

        val offhook = awaitEmission()
        assertEquals(listOf(RiskSignal.UNKNOWN_CALLER), offhook.value)
        assertEquals("생산 시점 epoch 승계", 0L, offhook.producedAtEpoch)
        assertEquals("반복호출 버퍼 1건 기록", 1, monitor.recentUnknownCalls.size)
    }

    /** 진입 게이트: reset 후 shareIn replay는 부수효과 재실행 없이 stale-epoch 중립 방출만 낸다. */
    @Test
    fun `stale replay after reset must not rerun side effects`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmission() // [UNKNOWN_CALLER] @0
        val recordedAt = monitor.recentUnknownCalls.toList()
        assertEquals(1, recordedAt.size)

        tracker.resetAfterUserConfirmedSafe() // epoch 0 → 1
        fakeClock.advanceMs(60_000L) // replay가 부수효과를 재실행하면 새 타임스탬프가 기록된다

        collectorJob?.cancel() // 재구독 (WhileSubscribed 5초 내 — replay 캐시 생존)
        val replayEmissions = Channel<Produced<List<RiskSignal>>>(Channel.UNLIMITED)
        val job2 = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { replayEmissions.trySend(it) }
        }
        try {
            val replayed = withTimeout(5_000) { replayEmissions.receive() }
            assertTrue("replay는 중립 방출로 격리", replayed.value.isEmpty())
            assertEquals("원본 생산 epoch 승계 (재스탬프 금지)", 0L, replayed.producedAtEpoch)
            assertEquals(
                "부수효과 미재실행 — 반복호출 버퍼 내용 불변",
                recordedAt,
                monitor.recentUnknownCalls.toList(),
            )
            assertNull("anchor 미장전 유지", monitor.lastSuspiciousCallEndedAt)
        } finally {
            job2.cancel()
        }
    }

    /** 계약 2: OFFHOOK→OFFHOOK 중복은 무시된다 — callId·반복호출 버퍼가 조작되지 않는다. */
    @Test
    fun `duplicate offhook does not restart the call`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmission()
        val callId = monitor.currentCallId()
        assertNotNull(callId)

        fakeClock.advanceMs(1_000L)
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222") // 중복 OFFHOOK

        assertEquals("callId 불변 (새 통화로 조작 금지)", callId, monitor.currentCallId())
        assertEquals("반복호출 이중 기록 금지", 1, monitor.recentUnknownCalls.size)
        assertNoFurtherEmission()
    }

    @Test
    fun `test mode flip during offhook does not record or emit the same call twice`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        val first = awaitEmissionWhere { RiskSignal.UNKNOWN_CALLER in it.value }
        val callId = monitor.currentCallId()

        assertEquals(listOf(RiskSignal.UNKNOWN_CALLER), first.value)
        assertEquals(1, monitor.recentUnknownCalls.size)
        assertNotNull(callId)
        // 실제 coordinator는 첫 UNKNOWN 방출로 세션을 연다. 활성 세션을 재현해야 두 번째
        // recordUnknownCall이 clean-slate clear에 가려지지 않고 이중 기록으로 드러난다.
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())

        testModeEnabled.value = true

        assertNoFurtherEmission()
        assertEquals("동일 OFFHOOK의 testMode 재시작은 호출을 재기록하지 않음", 1, monitor.recentUnknownCalls.size)
        assertEquals("설정 flip은 통화 회차/callId를 바꾸지 않음", callId, monitor.currentCallId())
    }

    @Test
    fun `test mode flip after safe-confirmed idle does not rearm the anchor`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmissionWhere { RiskSignal.UNKNOWN_CALLER in it.value }
        val callId = requireNotNull(monitor.currentCallId())
        monitor.markCurrentCallConfirmedSafe(callId)

        fakeClock.advanceMs(1_000L)
        drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
        awaitEmissionWhere { it.value.isEmpty() }
        assertNull("안전확인된 통화의 첫 IDLE은 anchor를 장전하지 않음", monitor.lastSuspiciousCallEndedAt)

        testModeEnabled.value = true

        assertNoFurtherEmission()
        assertNull("완료된 IDLE을 설정 flip으로 재처리해 anchor를 되살리면 안 됨", monitor.lastSuspiciousCallEndedAt)
    }

    @Test
    fun `reset while idle waits for test mode prevents stale resume side effects`() = runBlocking {
        val collectionCount = AtomicInteger(0)
        val idleSettingsStarted = CompletableDeferred<Unit>()
        val releaseIdleSettings = CompletableDeferred<Unit>()
        every { settingsRepository.observeTestModeEnabled() } returns flow {
            if (collectionCount.incrementAndGet() == 1) {
                emit(false) // OFFHOOK LONG 타이머의 최초 설정값
            } else {
                idleSettingsStarted.complete(Unit)
                releaseIdleSettings.await()
                emit(false)
            }
        }

        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmissionWhere { RiskSignal.UNKNOWN_CALLER in it.value }

        fakeClock.advanceMs(1_000L)
        drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
        withTimeout(5_000) { idleSettingsStarted.await() }

        tracker.resetAfterUserConfirmedSafe()
        monitor.clearTelebankingAnchor()
        releaseIdleSettings.complete(Unit)

        assertNoFurtherEmission(waitMs = 1_000)
        assertNull("reset 뒤 stale IDLE 재개가 anchor를 다시 장전하면 안 됨", monitor.lastSuspiciousCallEndedAt)
    }

    /**
     * 라운드 12-①: API31 번호 receiver 작업은 **enqueue 시점** epoch로 판정되어야 한다 —
     * 작업이 executor 큐에 대기하는 동안 reset이 끼면, 실행 시점 epoch 읽기는 reset 전
     * 번호를 fresh로 위장시킨다. stale 작업은 caller metadata(연락처 조회 포함)도 변경하지 않는다.
     */
    // mockk stub 캡처일 뿐 실제 등록이 아니다 — 실물 등록(RealCallRiskMonitor)은 NOT_EXPORTED 사용.
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `number enrichment enqueued before reset must not run after it`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // 명시 seed 실행 → 중립 방출
        awaitEmission()

        // RINGING 전이 후, 번호 broadcast 도착 — 작업이 큐에 대기하는 동안 reset이 끼어든다.
        deliverState(
            executorSlot.captured,
            callbackSlot.captured as TelephonyCallback.CallStateListener,
            TelephonyManager.CALL_STATE_RINGING,
        )
        manualExecutor.drain()
        val ringingIntent = mockk<Intent> {
            every { action } returns TelephonyManager.ACTION_PHONE_STATE_CHANGED
            // EXTRA_STATE_RINGING은 stub jar에서 non-const(null) — 실기기 값 리터럴을 사용한다.
            every { getStringExtra(TelephonyManager.EXTRA_STATE) } returns "RINGING"
            @Suppress("DEPRECATION")
            every { getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) } returns "01011112222"
        }
        broadcastSlot.captured.onReceive(context, ringingIntent)
        assertEquals("stale 작업 enqueue 확인", 1, manualExecutor.pendingCount())
        tracker.resetAfterUserConfirmedSafe() // 큐 대기 중 사용자 확인
        manualExecutor.drain()

        verify(exactly = 0) { contactChecker.checkCaller(any()) }

        // liveness: reset 이후 도착한 broadcast는 정상 처리된다
        broadcastSlot.captured.onReceive(context, ringingIntent)
        assertEquals("liveness 작업 enqueue 확인", 1, manualExecutor.pendingCount())
        manualExecutor.drain()
        verify(exactly = 1) { contactChecker.checkCaller(any()) }
    }

    /**
     * 라운드 13-① (legacy): 연락처 조회(checkCaller) **도중** reset이 끼면 조회 결과를
     * shared caller metadata에 반영하면 안 된다 — 반영되면 reset 이후의 fresh OFFHOOK이
     * 옛 번호·분류를 재사용해 UNKNOWN_CALLER를 세탁한다.
     */
    @Test
    fun `reset during contact lookup must not leak stale caller info into the next transition`() = runBlocking {
        every { contactChecker.checkCaller(any()) } answers {
            tracker.resetAfterUserConfirmedSafe() // 조회 도중 사용자 확인
            CallerCheckResult.NOT_IN_CONTACTS
        }
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE) // 암묵 seed
        awaitEmission()

        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222") // 조회 도중 reset 발생
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222") // reset 이후의 fresh 전이

        // fresh OFFHOOK 방출을 epoch로 정확히 선별 (라운드 14 권장)
        val offhook = awaitEmissionWhere { it.producedAtEpoch == 1L }
        assertTrue(
            "reset 전 조회된 번호·분류가 fresh OFFHOOK에 재사용되면 안 됨 (UNKNOWN_CALLER 미파생)",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /** 라운드 13-① (API31 receiver): 조회 도중 reset — 지역 계산 + 조회 후 재검증 + 원자 반영. */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `reset during contact lookup must not leak stale caller info via the api31 receiver`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null
        every { contactChecker.checkCaller(any()) } answers {
            tracker.resetAfterUserConfirmedSafe() // 조회 도중 사용자 확인
            CallerCheckResult.NOT_IN_CONTACTS
        }

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING)
        manualExecutor.drain()
        broadcastSlot.captured.onReceive(
            context,
            mockk {
                every { action } returns TelephonyManager.ACTION_PHONE_STATE_CHANGED
                every { getStringExtra(TelephonyManager.EXTRA_STATE) } returns "RINGING"
                @Suppress("DEPRECATION")
                every { getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) } returns "01011112222"
            },
        )
        manualExecutor.drain() // 조회 실행 — 그 도중 reset

        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK) // fresh 전이 (reset 후 제출)
        manualExecutor.drain()
        // fresh OFFHOOK 방출을 epoch로 정확히 선별 (라운드 14 권장)
        val offhook = awaitEmissionWhere { it.producedAtEpoch == 1L }
        assertTrue(
            "조회 결과가 shared에 반영됐다면 fresh OFFHOOK이 UNKNOWN_CALLER를 세탁한다",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /**
     * 라운드 14-① (legacy): 조회가 **정상 완료된 뒤** reset이 끼면, 커밋된 caller metadata는
     * 생산 epoch에 결속되어야 한다 — 결속이 없으면 reset 이후의 fresh OFFHOOK이 reset 전
     * 번호·분류를 재사용해 UNKNOWN_CALLER와 반복호출 기록을 되살린다 (안전 확인 의미 파괴).
     */
    @Test
    fun `caller metadata committed before a reset is not reused by a fresh offhook`() = runBlocking {
        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE) // 암묵 seed
        awaitEmission()

        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222") // 조회 정상 완료 — metadata @0
        tracker.resetAfterUserConfirmedSafe() // 사용자 안전 확인 (epoch 0 → 1)
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222") // reset 이후의 fresh 전이

        val offhook = awaitEmissionWhere { it.producedAtEpoch == 1L }
        assertTrue(
            "reset 전 커밋된 번호·분류가 fresh OFFHOOK에 재사용되면 안 됨 (UNKNOWN_CALLER 미파생)",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /** 라운드 14-① (API31 receiver): 정상 완료된 조회의 metadata도 epoch에 결속되어야 한다. */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `caller metadata committed before a reset is not reused by a fresh offhook via the api31 receiver`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING)
        manualExecutor.drain()
        broadcastSlot.captured.onReceive(
            context,
            mockk {
                every { action } returns TelephonyManager.ACTION_PHONE_STATE_CHANGED
                every { getStringExtra(TelephonyManager.EXTRA_STATE) } returns "RINGING"
                @Suppress("DEPRECATION")
                every { getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) } returns "01011112222"
            },
        )
        manualExecutor.drain() // 조회 정상 완료 — metadata 커밋 @0 (+ 번호 보강 재방출)
        verify(exactly = 1) { contactChecker.checkCaller(any()) } // 조회가 실제 완료됐음을 고정

        tracker.resetAfterUserConfirmedSafe() // epoch 0 → 1
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK) // fresh 전이 (reset 후 제출)
        manualExecutor.drain()

        val offhook = awaitEmissionWhere { it.producedAtEpoch == 1L }
        assertTrue(
            "커밋된 metadata가 epoch 결속 없이 재사용되면 fresh OFFHOOK이 UNKNOWN_CALLER를 세탁한다",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /**
     * 라운드 15-①: READ_PHONE_STATE만 가진 수신자용으로 번호 extra 없이 한 번 더 오는 중복
     * 방송(두 권한 보유 시 2회 송신·순서 비보장 — Android 공식 계약)이, 이미 확정 커밋된
     * 발신자 정보를 UNAVAILABLE로 강등시키면 안 된다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `numberless duplicate broadcast must not downgrade committed caller info`() = runBlocking {
        every { contactChecker.checkCaller(null) } returns CallerCheckResult.UNAVAILABLE
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING)
        manualExecutor.drain()
        broadcastSlot.captured.onReceive(context, numberedRingingIntent())
        manualExecutor.drain() // 번호 방송 — 확정 정보 커밋
        broadcastSlot.captured.onReceive(context, numberlessRingingIntent())
        manualExecutor.drain() // 번호 없는 중복 방송 — 확정 정보를 강등시키면 안 된다

        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK)
        manualExecutor.drain()
        val offhook = awaitEmission()
        assertEquals(
            "확정된 미저장 발신자 분류가 유지되어야 함",
            listOf(RiskSignal.UNKNOWN_CALLER),
            offhook.value,
        )
        assertEquals("반복호출 버퍼 1건", 1, monitor.recentUnknownCalls.size)
        // 번호 없는 방송은 조회 자체를 생략해야 한다
        verify(exactly = 1) { contactChecker.checkCaller(any()) }
    }

    /**
     * 라운드 15-②: 통화 중 enqueue된 번호 작업이 IDLE(회차 종료) 뒤에 실행되면, 발신자
     * 정보를 재충전해 **다음 통화**의 분류를 오염시키면 안 된다 — metadata는 epoch뿐
     * 아니라 통화 회차(generation)에도 결속되어야 한다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `a number task queued before idle must not refill caller info for the next call`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING) // 통화 1
        manualExecutor.drain()
        // 실기 인터리브: IDLE callback이 binder에서 먼저 큐에 제출되고, 번호 broadcast의
        // 작업이 그 뒤에 enqueue된다 — 작업은 IDLE(회차 경계) **이후에** 실행된다.
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_IDLE) // 통화 1 종료
        broadcastSlot.captured.onReceive(context, numberedRingingIntent())
        assertEquals("IDLE callback + 번호 작업이 큐에 대기", 2, manualExecutor.pendingCount())
        manualExecutor.drain() // IDLE(회차 경계) 실행 → 늦은 작업 실행 — 재충전하면 안 된다

        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING) // 통화 2 — 번호 방송 없음
        manualExecutor.drain()
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK)
        manualExecutor.drain()
        val offhook = awaitEmission()
        assertTrue(
            "이전 회차의 늦은 번호가 다음 통화를 분류하면 안 됨",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /**
     * 라운드 15-②: reset **전에 시작된 벨**의 번호 방송이 reset **후에 배달**되면, 진입
     * 시점의 새 epoch가 아니라 RINGING **원인 epoch**에 결속되어야 한다 — 아니면 reset 전
     * 발신자 분류가 새 epoch로 위장되어 reset 이후의 전이에 섞인다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `a late broadcast from a pre-reset ringing must not be stamped with the new epoch`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING) // 원인 epoch 0
        manualExecutor.drain()
        tracker.resetAfterUserConfirmedSafe() // 벨 도중 사용자 안전 확인 (epoch 0 → 1)
        broadcastSlot.captured.onReceive(context, numberedRingingIntent()) // reset 후 배달
        manualExecutor.drain()

        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK) // 전이 epoch 1
        manualExecutor.drain()
        val offhook = awaitEmissionWhere { it.producedAtEpoch == 1L }
        assertTrue(
            "reset 전 벨의 발신자 분류가 새 epoch로 위장되면 안 됨",
            offhook.value.isEmpty(),
        )
        assertTrue("반복호출 버퍼 미기록", monitor.recentUnknownCalls.isEmpty())
    }

    /**
     * 라운드 16-①: framework는 callback을 등록된 executor 큐를 통해 전달한다 — 상태 변화가
     * 큐에 제출된 뒤 reset이 끼면, 실행 시점 epoch 읽기는 reset 전 상태 전이를 새 epoch로
     * 위장시킨다. epoch는 executor 제출(execute) 진입 시점에 캡처해 callback까지 승계해야 한다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `a callback enqueued before reset must not be stamped with the new epoch`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING) // 벨 @0
        manualExecutor.drain()

        // OFFHOOK이 framework 큐에 제출된 뒤(epoch 0), 실행 전에 사용자 안전 확인이 끼어든다.
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK)
        tracker.resetAfterUserConfirmedSafe() // epoch 0 → 1
        manualExecutor.drain()

        // 제출 시점 epoch(0)으로 스탬프되어야 — stale 처리로 중립 흡수되어 신규 방출이 없다.
        // (실행 시점 읽기라면 reset 전 통화가 LIVE@epoch1로 위장 방출된다.)
        assertNoFurtherEmission()
    }

    /**
     * 라운드 16-②: awaitClose의 shutdown은 이미 제출된 작업을 계속 실행시킨다 (Java
     * ExecutorService 계약) — 종료된 collector의 늦은 OFFHOOK callback이 전역 상태
     * (callOwnership.currentCallId)를 오염시키면 안 된다. 소유 세대 검증이 1차 방어이고 shutdownNow는
     * 보조다 — linger executor는 shutdownNow가 못 지우는 창(이미 실행 단계 진입)을 모사한다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `a late callback after the collector closes must not mutate global call state`() = runBlocking {
        val manualExecutor = ManualExecutorService(lingerOnShutdownNow = true)
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        var unregistered = false
        every { telephonyManager.unregisterTelephonyCallback(any()) } answers { unregistered = true }
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null
        var permissionResult = PackageManager.PERMISSION_GRANTED
        every { context.checkPermission(any(), any(), any()) } answers { permissionResult }

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain() // seed (IDLE) — currentCallId null
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        // framework 큐에 OFFHOOK 제출 → 드레인 전에 권한 회수로 collector가 종료된다.
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK)
        permissionResult = PackageManager.PERMISSION_DENIED
        withTimeout(10_000) {
            while (!unregistered) delay(50) // 권한 폴링(3초) → flatMapLatest 전환 → awaitClose
        }
        manualExecutor.drain() // 종료 후 잔존 작업 실행 — shutdownNow가 못 지운 창

        assertNull(
            "종료된 collector의 늦은 callback이 전역 callId를 오염시키면 안 됨",
            monitor.currentCallId(),
        )
    }

    /**
     * 라운드 17: seed가 owner 진입 검사를 통과한 뒤 callState 조회 안에서 collector가
     * 종료되어도, 종료를 건넌 늦은 OFFHOOK 기록은 원자적으로 거부되어야 한다.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `a seed blocked across collector close must not write a stale call id`() = runBlocking {
        val manualExecutor = ManualExecutorService(lingerOnShutdownNow = true)
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        val permissionResult = AtomicInteger(PackageManager.PERMISSION_GRANTED)
        val released = CountDownLatch(1)
        val seedEntered = CountDownLatch(1)
        every { context.checkPermission(any(), any(), any()) } answers { permissionResult.get() }
        every { telephonyManager.unregisterTelephonyCallback(any()) } answers { released.countDown() }
        every { telephonyManager.callState } answers {
            seedEntered.countDown()
            permissionResult.set(PackageManager.PERMISSION_DENIED)
            check(released.await(15, TimeUnit.SECONDS)) { "collector release 미도달" }
            TelephonyManager.CALL_STATE_OFFHOOK
        }
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }

        manualExecutor.drain()

        assertEquals("seed가 실제 실행 중이어야 함", 0L, seedEntered.count)
        assertNull(
            "종료를 건넌 seed가 stale callId를 기록하면 안 됨",
            monitor.currentCallId(),
        )
    }

    /** 라운드 17: collector release는 활성 callId를 unregister 전에 즉시 null로 정리한다. */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `closing a collector with an active call must clear the call id immediately`() = runBlocking {
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every { telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot)) } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_OFFHOOK
        val permissionResult = AtomicInteger(PackageManager.PERMISSION_GRANTED)
        val released = CountDownLatch(1)
        every { context.checkPermission(any(), any(), any()) } answers { permissionResult.get() }
        every { telephonyManager.unregisterTelephonyCallback(any()) } answers { released.countDown() }
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain()
        assertNotNull("활성 callId 확보", monitor.currentCallId())

        permissionResult.set(PackageManager.PERMISSION_DENIED)
        assertTrue("collector release 미도달", released.await(15, TimeUnit.SECONDS))

        assertNull(
            "release는 callId를 즉시 null로 정리해야 함",
            monitor.currentCallId(),
        )
    }

    /** 라운드 17 권장 invariant: 이전 owner의 늦은 release는 새 owner의 callId를 지우지 않는다. */
    @Test
    fun `a late release from an old owner must not clear the new owner call id`() {
        val oldOwner = monitor.claimOwnership()
        assertTrue("이전 owner callId 기록", monitor.writeCallId(oldOwner, 1_111L))
        val newOwner = monitor.claimOwnership()
        assertTrue("새 owner callId 기록", monitor.writeCallId(newOwner, 2_222L))

        assertTrue(
            "이전 owner의 늦은 release는 거부되어야 함",
            !monitor.releaseOwnership(oldOwner),
        )
        assertEquals("새 owner callId 보존", 2_222L, monitor.currentCallId())
        assertTrue("새 owner generation 보존", monitor.writeCallId(newOwner, 3_333L))
        assertEquals("새 owner 후속 callId 기록", 3_333L, monitor.currentCallId())
    }

    @Test
    fun `wall clock rollback does not make legacy call duration negative`() = runBlocking {
        val elapsedClock = FakeClock(now = 2_000_000L)
        monitor.monotonicClock = elapsedClock.provider
        val contextSlot = slot<CallContext>()
        every { mapper.map(capture(contextSlot), any()) } returns emptyList()

        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE)
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
        drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
        awaitEmissionWhere { RiskSignal.UNKNOWN_CALLER in it.value }
        assertEquals("callId는 monotonic이 아닌 wall 시작시각", 1_000_000L, monitor.currentCallId())

        elapsedClock.advanceMs(30_000L)
        fakeClock.advanceMs(-30_000L) // 실제 30초 경과 중 wall clock 60초 역행
        drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
        withTimeout(5_000) {
            while (!contextSlot.isCaptured) delay(10)
        }

        val ended = contextSlot.captured
        assertEquals("노출 시작시각은 wall clock", 1_000_000L, ended.startedAtMillis)
        assertEquals("노출 종료시각은 조정된 wall clock", 970_000L, ended.endedAtMillis)
        assertEquals("duration은 monotonic 경과시간", 30_000L, ended.durationMs)
        assertEquals(30L, ended.durationSec)
    }

    @Test
    fun `replayed idle anchors at callback production elapsed time`() = runBlocking {
        val elapsedClock = FakeClock(now = 2_000_000L)
        monitor.monotonicClock = elapsedClock.provider
        val contextStates = Channel<CallMonitorState>(Channel.UNLIMITED)
        val contextJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallContext().collect { contextStates.trySend(it) }
        }

        suspend fun awaitContext(predicate: (CallMonitorState) -> Boolean): CallMonitorState =
            withTimeout(5_000) {
                var state = contextStates.receive()
                while (!predicate(state)) state = contextStates.receive()
                state
            }

        try {
            withTimeout(5_000) {
                while (!listenerSlot.isCaptured) delay(10)
            }
            drive(TelephonyManager.CALL_STATE_IDLE)
            awaitContext { it is CallMonitorState.Idle }
            drive(TelephonyManager.CALL_STATE_RINGING, "01011112222")
            drive(TelephonyManager.CALL_STATE_OFFHOOK, "01011112222")
            awaitContext {
                it is CallMonitorState.Active && it.context.state == CallState.OFFHOOK
            }

            fakeClock.advanceMs(30_000L)
            elapsedClock.advanceMs(30_000L)
            val producedEndElapsed = elapsedClock.provider()
            drive(TelephonyManager.CALL_STATE_IDLE, "01011112222")
            awaitContext {
                it is CallMonitorState.Active && it.context.state == CallState.IDLE
            }
            assertNull("신호 구독 전에는 anchor 부수효과가 실행되지 않음", monitor.lastSuspiciousCallEndedAt)

            elapsedClock.advanceMs(5 * 60 * 1000L + 30_000L)
            startCollector()
            awaitEmission()

            assertEquals(
                "늦은 replay 소비가 아니라 callback IDLE 생산시각을 anchor로 유지",
                producedEndElapsed,
                monitor.lastSuspiciousCallEndedAt,
            )
            assertFalse("생산시각 기준 5분30초 경과 후 anchor는 만료", monitor.isTelebankingWindow())
        } finally {
            contextJob.cancel()
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    @Test
    fun `wall clock jump does not inflate api31 call duration`() = runBlocking {
        val elapsedClock = FakeClock(now = 1_000_000L)
        monitor.monotonicClock = elapsedClock.provider
        val contextSlot = slot<CallContext>()
        every { mapper.map(capture(contextSlot), any()) } returns emptyList()
        val manualExecutor = ManualExecutorService()
        monitor.sdkIntProvider = { Build.VERSION_CODES.S }
        monitor.callbackExecutorFactory = { manualExecutor }
        val callbackSlot = slot<TelephonyCallback>()
        val executorSlot = slot<java.util.concurrent.Executor>()
        every {
            telephonyManager.registerTelephonyCallback(capture(executorSlot), capture(callbackSlot))
        } returns Unit
        every { telephonyManager.callState } returns TelephonyManager.CALL_STATE_IDLE
        val broadcastSlot = slot<BroadcastReceiver>()
        every { context.registerReceiver(capture(broadcastSlot), any<IntentFilter>()) } returns null

        collectorJob = CoroutineScope(Dispatchers.Default).launch {
            monitor.observeCallSignals().collect { emissions.trySend(it) }
        }
        withTimeout(5_000) {
            while (!broadcastSlot.isCaptured || !callbackSlot.isCaptured ||
                manualExecutor.pendingCount() == 0
            ) delay(10)
        }
        manualExecutor.drain()
        awaitEmission()

        val listener = callbackSlot.captured as TelephonyCallback.CallStateListener
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_RINGING)
        manualExecutor.drain()
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_OFFHOOK)
        manualExecutor.drain()
        awaitEmission()

        elapsedClock.advanceMs(30_000L)
        fakeClock.advanceMs(90_000L) // 실제 30초 경과 중 wall clock 60초 도약
        deliverState(executorSlot.captured, listener, TelephonyManager.CALL_STATE_IDLE)
        manualExecutor.drain()
        withTimeout(5_000) {
            while (!contextSlot.isCaptured) delay(10)
        }

        val ended = contextSlot.captured
        assertEquals("노출 시작시각은 wall clock", 1_000_000L, ended.startedAtMillis)
        assertEquals("노출 종료시각은 조정된 wall clock", 1_090_000L, ended.endedAtMillis)
        assertEquals("duration은 monotonic 경과시간", 30_000L, ended.durationMs)
        assertEquals(30L, ended.durationSec)
    }

    /** framework의 callback 전달 모사 — 등록된 executor 큐 경계를 실제로 통과시킨다 (라운드 16-①). */
    private fun deliverState(
        deliveryExecutor: java.util.concurrent.Executor,
        listener: TelephonyCallback.CallStateListener,
        state: Int,
    ) {
        deliveryExecutor.execute { listener.onCallStateChanged(state) }
    }

    private fun numberedRingingIntent(number: String = "01011112222"): Intent = mockk {
        every { action } returns TelephonyManager.ACTION_PHONE_STATE_CHANGED
        // EXTRA_STATE_RINGING은 stub jar에서 non-const(null) — 실기기 값 리터럴 사용.
        every { getStringExtra(TelephonyManager.EXTRA_STATE) } returns "RINGING"
        @Suppress("DEPRECATION")
        every { getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) } returns number
    }

    private fun numberlessRingingIntent(): Intent = mockk {
        every { action } returns TelephonyManager.ACTION_PHONE_STATE_CHANGED
        every { getStringExtra(TelephonyManager.EXTRA_STATE) } returns "RINGING"
        @Suppress("DEPRECATION")
        every { getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) } returns null
    }

    /**
     * 라운드 12-④: IDLE 텔레뱅킹 매칭·emit이 도는 동안 reset이 끼면, 이후의
     * `OutgoingCallReceiver.clear()`가 다음 통화의 새 선캡처를 지우면 안 된다.
     */
    @Test
    fun `reset during telebanking match must not clear a fresh precapture`() = runBlocking {
        var matchCalls = 0
        every { bankArsRegistry.matches(any()) } answers {
            // 1번째 = OFFHOOK 선캡처 매칭, 2번째 = IDLE 폴백 매칭 — 그 도중 사용자 확인 주입.
            if (++matchCalls == 2) tracker.resetAfterUserConfirmedSafe()
            true
        }
        monitor.lastSuspiciousCallEndedAt = fakeClock.provider() // 텔레뱅킹 윈도우 hot
        OutgoingCallReceiver().onReceive(
            mockk(relaxed = true),
            mockk {
                every { action } returns Intent.ACTION_NEW_OUTGOING_CALL
                every { getStringExtra(Intent.EXTRA_PHONE_NUMBER) } returns "15881234"
            },
        )

        startCollector()
        drive(TelephonyManager.CALL_STATE_IDLE) // 암묵 seed
        awaitEmission()
        drive(TelephonyManager.CALL_STATE_OFFHOOK) // IDLE→OFFHOOK = 발신 통화
        awaitEmissionWhere { RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in it.value }
        fakeClock.advanceMs(1_000L)
        drive(TelephonyManager.CALL_STATE_IDLE) // 통화 종료 — IDLE 분기 처리 중 reset
        awaitEmissionWhere { it.value.isEmpty() } // IDLE 분기 종료(RESET-empty)까지 대기

        assertNotNull(
            "stale 경로의 clear가 새 선캡처를 지우면 안 됨 (clear 직전 재검증)",
            OutgoingCallReceiver.lastOutgoingNumber,
        )
    }

    /** 계약 1: SEED OFFHOOK은 방향을 추론하지 않는다 — 발신 텔레뱅킹 분기·receiver 소비 금지. */
    @Test
    fun `seed offhook is not treated as an outgoing call`() = runBlocking {
        // 텔레뱅킹 윈도우 hot + 선캡처 번호 존재 — 과거 결함(seed→isOutgoing=true)이라면
        // 발신 분기가 번호를 매칭해 TELEBANKING을 방출했을 조합.
        monitor.lastSuspiciousCallEndedAt = fakeClock.provider()
        OutgoingCallReceiver().onReceive(
            mockk(relaxed = true),
            mockk {
                every { action } returns Intent.ACTION_NEW_OUTGOING_CALL
                every { getStringExtra(Intent.EXTRA_PHONE_NUMBER) } returns "15881234"
            },
        )

        startCollector()
        drive(TelephonyManager.CALL_STATE_OFFHOOK) // 진행 중 통화 seed

        val first = awaitEmission()
        assertTrue("SEED는 중립 방출 — 발신 분기 미실행", first.value.isEmpty())
        assertNoFurtherEmission(waitMs = 700) // 300ms 재시도 창 포함 — TELEBANKING 미방출
        assertNotNull(
            "선캡처 번호 미소비 (IDLE clear 미실행)",
            OutgoingCallReceiver.lastOutgoingNumber,
        )
    }

    /**
     * enqueue↔실행 창을 테스트가 직접 제어하는 수동 executor.
     * [lingerOnShutdownNow]=true는 shutdownNow가 큐를 비우지 못하는 잔여 창(이미 실행 단계에
     * 들어간 작업 — Java ExecutorService 계약상 중단 보장 없음)을 모사한다 (라운드 16-②).
     */
    private class ManualExecutorService(
        private val lingerOnShutdownNow: Boolean = false,
    ) : java.util.concurrent.AbstractExecutorService() {
        private val queue = ArrayDeque<Runnable>()

        @Volatile
        private var isShutdownFlag = false

        override fun execute(command: Runnable) {
            if (isShutdownFlag) throw java.util.concurrent.RejectedExecutionException("shut down")
            synchronized(queue) { queue += command }
        }

        fun pendingCount(): Int = synchronized(queue) { queue.size }

        fun drain() {
            while (true) {
                val task = synchronized(queue) { queue.removeFirstOrNull() } ?: return
                task.run()
            }
        }

        override fun shutdown() {
            isShutdownFlag = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            isShutdownFlag = true
            if (lingerOnShutdownNow) return mutableListOf()
            return synchronized(queue) {
                val remaining = queue.toMutableList()
                queue.clear()
                remaining
            }
        }

        override fun isShutdown(): Boolean = isShutdownFlag
        override fun isTerminated(): Boolean = isShutdownFlag && pendingCount() == 0
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
    }
}
