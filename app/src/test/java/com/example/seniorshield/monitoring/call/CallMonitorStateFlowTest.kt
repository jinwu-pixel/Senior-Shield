package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallContext
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.model.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CallMonitorState] 기반 권한 인식 Flow 구조의 단위 테스트.
 *
 * RealCallRiskMonitor는 Android 의존성(Context, TelephonyManager)이 있어
 * 직접 인스턴스화 불가하므로, 핵심 로직(권한 Flow + flatMapLatest + sealed class 분기)을
 * 동일 패턴으로 재현하여 테스트한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMonitorStateFlowTest {

    private val now = System.currentTimeMillis()

    /** 테스트용 권한 상태 Flow */
    private val permissionState = MutableStateFlow(false)

    /** 테스트용 내부 CallContext 소스 (권한 보유 전제) */
    private val internalCallContext = MutableStateFlow<CallContext?>(null)

    /**
     * RealCallRiskMonitor.observeCallContextRaw()와 동일한 구조를 재현.
     * permissionState가 false→true 전환 시 internalCallContext를 재구독한다.
     */
    private fun observeCallContextRaw(): Flow<CallMonitorState> =
        permissionState
            .flatMapLatest { granted ->
                if (granted) {
                    internalCallContext.map { ctx ->
                        if (ctx != null) CallMonitorState.Active(ctx) else CallMonitorState.Idle
                    }
                } else {
                    flowOf(CallMonitorState.NoPermission)
                }
            }

    private fun offhookContext(
        phoneNumber: String? = "010-1234-5678",
        isUnknownCaller: Boolean? = true,
    ) = CallContext(
        state = CallState.OFFHOOK,
        phoneNumber = phoneNumber,
        startedAtMillis = now,
        endedAtMillis = null,
        durationMs = 0L,
        durationSec = 0L,
        isUnknownCaller = isUnknownCaller,
        isVerifiedCaller = null,
    )

    private fun ringingContext(
        phoneNumber: String? = "010-1234-5678",
        isUnknownCaller: Boolean? = true,
    ) = CallContext(
        state = CallState.RINGING,
        phoneNumber = phoneNumber,
        startedAtMillis = null,
        endedAtMillis = null,
        durationMs = 0L,
        durationSec = 0L,
        isUnknownCaller = isUnknownCaller,
        isVerifiedCaller = null,
    )

    // ── 1. 앱 시작 시 권한 없음 → NoPermission ──────────────────────────────────

    @Test
    fun `권한 없음 초기 상태 - NoPermission 방출`() = runTest {
        permissionState.value = false
        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }
        assertTrue(results.isNotEmpty())
        assertEquals(CallMonitorState.NoPermission, results.first())
        job.cancel()
    }

    // ── 2. 권한 있음 + 통화 없음 → Idle ──────────────────────────────────────────

    @Test
    fun `권한 있음 통화 없음 - Idle 방출`() = runTest {
        permissionState.value = true
        internalCallContext.value = null
        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }
        assertTrue(results.isNotEmpty())
        assertEquals(CallMonitorState.Idle, results.first())
        job.cancel()
    }

    // ── 3. 권한 있음 + 통화 활성 → Active ────────────────────────────────────────

    @Test
    fun `권한 있음 통화 활성 - Active 방출`() = runTest {
        permissionState.value = true
        val ctx = offhookContext()
        internalCallContext.value = ctx
        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }
        assertTrue(results.isNotEmpty())
        val lastState = results.last()
        assertTrue(lastState is CallMonitorState.Active)
        assertEquals(ctx, (lastState as CallMonitorState.Active).context)
        job.cancel()
    }

    // ── 4. 권한 false→true 전환 시 현재 통화 즉시 감지 ───────────────────────────

    @Test
    fun `권한 false에서 true 전환 - 진행 중 통화 즉시 Active 방출`() = runTest {
        permissionState.value = false
        val ctx = offhookContext()
        internalCallContext.value = ctx  // 통화가 이미 진행 중

        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }

        // 초기: NoPermission
        assertEquals(CallMonitorState.NoPermission, results.first())

        // 권한 허용 → flatMapLatest 재구독 → 진행 중 통화 감지
        permissionState.value = true

        val activeStates = results.filterIsInstance<CallMonitorState.Active>()
        assertTrue("권한 전환 후 Active 상태가 방출되어야 함", activeStates.isNotEmpty())
        assertEquals(ctx, activeStates.first().context)
        job.cancel()
    }

    // ── 5. 권한 true→false 전환 시 NoPermission으로 전이 ─────────────────────────

    @Test
    fun `권한 true에서 false 전환 - NoPermission으로 전이`() = runTest {
        permissionState.value = true
        internalCallContext.value = offhookContext()

        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }

        // 초기: Active
        assertTrue(results.last() is CallMonitorState.Active)

        // 권한 철회
        permissionState.value = false

        assertEquals(
            "권한 철회 후 마지막 상태는 NoPermission",
            CallMonitorState.NoPermission,
            results.last(),
        )
        job.cancel()
    }

    // ── 6. 다중 구독 시 독립 수집 검증 (shareIn 전 raw flow 기준) ────────────────

    @Test
    fun `다중 구독자 - 각자 독립적으로 상태를 수신`() = runTest {
        permissionState.value = true
        internalCallContext.value = null

        val results1 = mutableListOf<CallMonitorState>()
        val results2 = mutableListOf<CallMonitorState>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results1)
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results2)
        }

        // 둘 다 Idle
        assertEquals(CallMonitorState.Idle, results1.last())
        assertEquals(CallMonitorState.Idle, results2.last())

        // 통화 시작
        internalCallContext.value = offhookContext()

        assertTrue(results1.last() is CallMonitorState.Active)
        assertTrue(results2.last() is CallMonitorState.Active)

        job1.cancel()
        job2.cancel()
    }

    // ── 7. observeCallSignals 분기 — NoPermission/Idle/Active 구분 ──────────────

    @Test
    fun `observeCallSignals 분기 - NoPermission과 Idle 모두 빈 신호, Active는 상태에 따라 분기`() = runTest {
        // NoPermission → 빈 신호
        val noPermResult = mapStateToSignalCategory(CallMonitorState.NoPermission)
        assertEquals("reset", noPermResult)

        // Idle → 빈 신호
        val idleResult = mapStateToSignalCategory(CallMonitorState.Idle)
        assertEquals("reset", idleResult)

        // Active(OFFHOOK) → active 분기
        val activeResult = mapStateToSignalCategory(
            CallMonitorState.Active(offhookContext()),
        )
        assertEquals("active_offhook", activeResult)

        // Active(RINGING) → else 분기
        val ringingResult = mapStateToSignalCategory(
            CallMonitorState.Active(ringingContext()),
        )
        assertEquals("active_other", ringingResult)
    }

    /**
     * observeCallSignals의 when 분기를 모사하여 각 상태가
     * 올바른 분기로 라우팅되는지 확인한다.
     */
    private fun mapStateToSignalCategory(state: CallMonitorState): String = when (state) {
        is CallMonitorState.NoPermission -> "reset"
        is CallMonitorState.Idle -> "reset"
        is CallMonitorState.Active -> {
            val ctx = state.context
            when {
                ctx.state == CallState.OFFHOOK -> "active_offhook"
                ctx.state == CallState.IDLE && ctx.endedAtMillis != null -> "active_idle_ended"
                else -> "active_other"
            }
        }
    }

    // ── 8. 상태 전이 시퀀스 전체 흐름 검증 ──────────────────────────────────────

    @Test
    fun `전체 시퀀스 - NoPermission → Idle → Active → Idle → NoPermission`() = runTest {
        permissionState.value = false
        internalCallContext.value = null

        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observeCallContextRaw().toList(results)
        }

        // 1. NoPermission
        assertEquals(CallMonitorState.NoPermission, results.last())

        // 2. 권한 허용 → Idle
        permissionState.value = true
        assertEquals(CallMonitorState.Idle, results.last())

        // 3. 통화 시작 → Active
        val ctx = offhookContext()
        internalCallContext.value = ctx
        assertTrue(results.last() is CallMonitorState.Active)

        // 4. 통화 종료 → Idle
        internalCallContext.value = null
        assertEquals(CallMonitorState.Idle, results.last())

        // 5. 권한 철회 → NoPermission
        permissionState.value = false
        assertEquals(CallMonitorState.NoPermission, results.last())

        job.cancel()
    }

    // ── 9. FakeCallRiskMonitor 반환 타입 검증 ────────────────────────────────────

    @Test
    fun `FakeCallRiskMonitor - Idle 반환`() = runTest {
        val fake = FakeCallRiskMonitor()
        val results = mutableListOf<CallMonitorState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.observeCallContext().toList(results)
        }
        assertTrue(results.isNotEmpty())
        assertEquals(CallMonitorState.Idle, results.first())
        job.cancel()
    }
}
