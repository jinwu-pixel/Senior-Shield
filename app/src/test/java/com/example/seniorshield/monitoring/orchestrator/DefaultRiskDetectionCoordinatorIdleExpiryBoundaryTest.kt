package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.core.overlay.BankingCooldownManager
import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.appinstall.AppInstallRiskMonitor
import com.example.seniorshield.monitoring.appusage.AppUsageRiskMonitor
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.DeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.evaluator.RiskEvaluatorImpl
import com.example.seniorshield.monitoring.event.RiskEventFactory
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.DEFAULT_IDLE_TIMEOUT_MS
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.monitoring.session.TRIGGER_IDLE_TIMEOUT_MS
import com.example.seniorshield.testutil.CoordinatorTestHarness
import com.example.seniorshield.testutil.FakeClock
import com.example.seniorshield.testutil.FakeGuardianRepository
import com.example.seniorshield.testutil.FakeRiskEventSink
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * maintenance pulse의 세션 idle TTL 처리 — 경계 보존·renewal·fresh episode·순서 역전·epoch 회귀.
 *
 * ## D1 개정 (2026-07-14): renewal은 표시 연속성만 유지한다
 * TTL 초과 시 [com.example.seniorshield.monitoring.session.RiskSessionTracker.transition]이
 * 단일 시각으로 원자 결정한다:
 * - **진짜 idle**(이번 tick 신호 없음/생성 불가) → 만료, cleanup 정확히 1회.
 * - **latched 위협**(신호가 기존 accumulated와 겹침) → **같은 세션 ID renewal** + notified* 승계
 *   → notification·이력·GUARDED 카드·쿨다운이 자동 재발동되지 않는다 (재알림 주기는 별도 정책).
 * - **겹침 없는 새 신호** → fresh episode(새 ID), 통상 알림 경로 발화. 경계 창에서 통화 컨텍스트가
 *   이미 끝나 있으면 CRITICAL 대신 INTERRUPT가 되는 것은 의도된 수용(2026-07-14 D2).
 * - maintenance의 스냅샷 renewal은 **다음 실측 방출이 항상 우선** — 근거가 사라졌으면 즉시 무효화.
 * - 사용자 "안전 확인"(userResetEpoch)은 tick 어느 단계에 끼어들어도 잔여 효과보다 우선한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRiskDetectionCoordinatorIdleExpiryBoundaryTest {

    private val harness = CoordinatorTestHarness()
    private val callMonitor get() = harness.callMonitor
    private val appUsageMonitor get() = harness.appUsageMonitor
    private val deviceEnvMonitor get() = harness.deviceEnvMonitor
    private val eventSink get() = harness.eventSink
    private val sessionTracker get() = harness.sessionTracker
    private val overlayManager get() = harness.overlayManager
    private val cooldownManager get() = harness.cooldownManager
    private val notificationManager get() = harness.notificationManager

    private fun TestScope.startCoordinator(clock: FakeClock): DefaultRiskDetectionCoordinator =
        with(harness) { start(clock) }

    // ── 경계 보존 + 진짜 idle 만료 ──────────────────────────────────────

    @Test
    fun `passive session stays at 30 minutes then expires once when truly idle`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            callSignalTick(emptyList()) // 통화 종료 — 진짜 idle
            resetInteractionCounts()

            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS)
            runMaintenanceTick()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)
            assertNoCleanup()

            clock.advanceMs(1L)
            runMaintenanceTick()
            assertNull(sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()

            runMaintenanceTick()
            // 세션이 이미 없으므로 idle 게이트에서 멱등 종료 — 중복 cleanup 없음
            assertNull(sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun `trigger session stays at 60 minutes then clears current event exactly once when truly idle`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            assertNotNull(eventSink.currentEvent)
            appSignalTick(emptyList()) // 원격제어 앱 종료 — 진짜 idle
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS)
            runMaintenanceTick()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)
            assertNotNull(eventSink.currentEvent)
            assertNoCleanup()

            clock.advanceMs(1L)
            runMaintenanceTick()
            assertNull(sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertCleanupExactlyOnce()

            runMaintenanceTick()
            assertNull(sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── fresh episode (겹침 없는 새 신호) ────────────────────────────────

    /**
     * 진짜 idle 후 TTL 경계 창에 새 trigger 도착: 구 세션 부활이 아니라 fresh episode(새 ID).
     * 통화 컨텍스트가 이미 끝나 있으므로 CRITICAL이 아닌 INTERRUPT — 의도된 수용 (2026-07-14 D2).
     */
    @Test
    fun `signal arriving after TTL with no overlap starts a fresh episode`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val oldSessionId = requireNotNull(sessionTracker.sessionState.value).id
            callSignalTick(emptyList())
            resetInteractionCounts()

            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))

            val newSession = requireNotNull(sessionTracker.sessionState.value)
            assertNotEquals(oldSessionId, newSession.id)
            assertEquals(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), newSession.accumulatedSignals)
            assertNotNull("fresh episode의 push가 currentEvent를 채운다", eventSink.currentEvent)
            assertCleanupExactlyOnce() // 구 세션 presentation은 새 효과 이전에 정리
            verify(exactly = 1) { overlayManager.show(any(), any(), any()) }

            runMaintenanceTick()
            assertEquals(newSession.id, sessionTracker.sessionState.value?.id)
            assertNotNull(eventSink.currentEvent)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 필수 테스트 5: fresh episode에서 currentEvent가 실제 **새 이벤트 ID**로 교체되는지. */
    @Test
    fun `fresh episode replaces currentEvent with a new event id`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            val oldEventId = requireNotNull(eventSink.currentEvent).id
            appSignalTick(emptyList())
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            // 겹침 없는 다른 trigger → fresh episode + 새 이벤트 push
            appSignalTick(listOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP))

            val newEvent = requireNotNull(eventSink.currentEvent)
            assertNotEquals("currentEvent는 새 이벤트로 교체되어야 함", oldEventId, newEvent.id)
            assertEquals(newEvent.id, eventSink.pushed.last().id)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── renewal (latched 위협, D1 개정: 재발동 없음) ─────────────────────

    /**
     * 필수 테스트 6 포함: latched TRIGGER의 TTL renewal은 같은 세션 ID로 이어지고
     * (StateFlow에 중간 null 없음), notification·이력·팝업·이벤트가 재발동되지 않는다.
     */
    @Test
    fun `latched trigger renews the same session without refiring anything`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            val original = requireNotNull(sessionTracker.sessionState.value)
            val originalEventId = requireNotNull(eventSink.currentEvent).id
            val recordedBefore = eventSink.recorded.size
            val pushedBefore = eventSink.pushed.size
            resetInteractionCounts()

            // rollover 동안 StateFlow 방출 기록 (필수 테스트 6: 불필요한 중간 null 금지).
            // StateFlow conflation 특성상 이는 "관찰자에게 null이 도달하지 않는다"는 사용자
            // 레벨 속성을 고정한다 — 구현 레벨 보장은 transition()의 단일 대입 구조.
            val emissions = mutableListOf<Boolean>() // true = non-null
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                sessionTracker.sessionState.collect { emissions += (it != null) }
            }

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()

            val renewed = requireNotNull(sessionTracker.sessionState.value) {
                "latched 위협이면 renewal로 세션이 이어져야 함"
            }
            assertEquals("renewal은 같은 세션 ID", original.id, renewed.id)
            assertTrue("lastSignalAt이 rebase되어야 함", renewed.lastSignalAt > original.lastSignalAt)
            assertEquals(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), renewed.accumulatedSignals)
            // 재발동 없음: 이벤트·이력·알림·팝업·cleanup 전부 0
            assertEquals("currentEvent는 그대로", originalEventId, eventSink.currentEvent?.id)
            assertEquals(pushedBefore, eventSink.pushed.size)
            assertEquals(recordedBefore, eventSink.recorded.size)
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
            assertNoCleanup()
            assertFalse("rollover 중 중간 null 방출 금지", emissions.contains(false))

            collectJob.cancel()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** latched PASSIVE renewal: 같은 ID·GUARDED 유지 — 알림/이력/카드 재발동 없음 (D1 개정). */
    @Test
    fun `latched passive renews silently without notification or history`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            val recordedBefore = eventSink.recorded.size
            resetInteractionCounts()

            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()

            val renewed = requireNotNull(sessionTracker.sessionState.value)
            assertEquals("같은 세션 ID → GUARDED 카드 재노출도 구조적으로 차단", originalId, renewed.id)
            assertEquals(recordedBefore, eventSink.recorded.size)
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
            assertNoCleanup()
            assertNull(eventSink.currentEvent)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** renewal downgrade: trigger가 사라진 채 renewal되면 잔존 팝업/currentEvent만 걷어낸다. */
    @Test
    fun `renewal downgraded below interrupt clears stale popup and current event only`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // 통화+trigger → CRITICAL, event push
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            assertNotNull(eventSink.currentEvent)
            appSignalTick(emptyList()) // trigger 종료 — 통화(latched)만 남음
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()

            val renewed = requireNotNull(sessionTracker.sessionState.value)
            assertEquals(originalId, renewed.id)
            assertEquals(setOf(RiskSignal.UNKNOWN_CALLER), renewed.accumulatedSignals)
            assertEquals(1, eventSink.clearCurrentCount)
            assertNull(eventSink.currentEvent)
            verify(exactly = 1) { overlayManager.dismiss() }
            verify(exactly = 0) { cooldownManager.dismissIfShowing() } // 쿨다운은 유지
            verify(exactly = 0) { notificationManager.notify(any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun `renewal downgrade after failed safe confirmation retains the fail-closed Event`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)
        val cleanupReady = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            appSignalTick(emptyList())
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val eventId = (request.subject as SafeConfirmationSubject.Event).eventId
            coordinator.beforeRenewalDowngradeCleanup = {
                cleanupReady.complete(Unit)
                releaseCleanup.await()
            }

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()
            cleanupReady.await()

            callMonitor.failClearTelebankingAnchor = true
            assertFalse(coordinator.confirmSafe(request))
            releaseCleanup.complete(Unit)
            runCurrent()

            assertEquals(eventId, eventSink.currentEvent?.id)
        } finally {
            releaseCleanup.complete(Unit)
            callMonitor.failClearTelebankingAnchor = false
            coordinator.beforeRenewalDowngradeCleanup = {}
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun `renewal downgrade preserves a newer Event published while cleanup is suspended`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)
        val cleanupReady = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val newer = RiskEvent(
            id = "newer-during-renewal-cleanup",
            title = "test",
            description = "test",
            occurredAtMillis = 2L,
            level = RiskLevel.CRITICAL,
            signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            appSignalTick(emptyList())
            coordinator.beforeRenewalDowngradeCleanup = {
                cleanupReady.complete(Unit)
                releaseCleanup.await()
            }

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()
            cleanupReady.await()
            coordinator.publishAndShowDebugOverlay(newer)
            assertEquals(newer.id, eventSink.currentEvent?.id)

            releaseCleanup.complete(Unit)
            runCurrent()

            assertEquals(newer.id, eventSink.currentEvent?.id)
        } finally {
            releaseCleanup.complete(Unit)
            coordinator.beforeRenewalDowngradeCleanup = {}
            coordinator.stop()
            runCurrent()
        }
    }

    /** 필수 테스트 4: isShowing=true인 실제 카운트다운 중 renewal이 쿨다운을 끊지 않는다. */
    @Test
    fun `renewal during an actively showing cooldown never cuts the countdown`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id

            // TTL 직전 뱅킹 진입 → call-based 쿨다운 발동, 이후 카운트다운 표시 중(isShowing=true)
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS - 10_000L)
            appUsageMonitor.bankingForeground.value = true
            runCurrent()
            verify(exactly = 1) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
            every { cooldownManager.isShowing() } returns true
            resetInteractionCounts()

            clock.advanceMs(10_001L)
            runMaintenanceTick()

            assertEquals("renewal은 같은 ID — 쿨다운 세션당 1회 정책도 그대로 유효", originalId, sessionTracker.sessionState.value?.id)
            verify(exactly = 0) { cooldownManager.dismissIfShowing() }
            verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── 필수 테스트 1: empty 방출 vs maintenance — 양 순서 결정적 재현 ──────

    @Test
    fun `order A - empty emission before maintenance expires and cleans up fully`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            assertNotNull(eventSink.currentEvent)
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            appSignalTick(emptyList()) // empty가 먼저 처리됨 → 진짜 idle 만료

            assertNull(sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertCleanupExactlyOnce()

            runMaintenanceTick() // 뒤따르는 maintenance는 아무 것도 하지 않는다
            assertNull(sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun `order B - stale maintenance renewal is invalidated by the late empty emission`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            assertNotNull(eventSink.currentEvent)
            resetInteractionCounts()

            // maintenance가 stale 스냅샷으로 먼저 renewal (위협은 실제로는 끝났지만 방출이 늦음)
            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            // 뒤늦게 도착한 empty 실측 방출이 renewal 근거를 부정 → 무효화 + 전체 정리
            appSignalTick(emptyList())

            assertNull("실측 empty가 stale renewal보다 우선해야 함", sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── 필수 테스트 2: confirmSafe가 tick 중간에 끼어드는 경우 ──────────────

    @Test
    fun `confirmSafe before transition aborts session creation for that tick`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            var injectReset = false
            every { overlayManager.isEndCallSuppressed() } answers {
                if (injectReset) {
                    injectReset = false
                    sessionTracker.resetAfterUserConfirmedSafe()
                }
                false
            }

            injectReset = true
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))

            assertNull("사용자 확인이 우선 — 이번 tick의 세션 재생성 중단", sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertEquals(0, eventSink.pushed.size)
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun `confirmSafe between transition and effects aborts all external effects`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            var injectReset = false
            every { cooldownManager.isShowing() } answers {
                if (injectReset) {
                    injectReset = false
                    sessionTracker.resetAfterUserConfirmedSafe()
                }
                false
            }

            injectReset = true
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))

            assertNull("사용자 확인이 우선 — 세션은 사용자 reset 결과대로 종료", sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertEquals("push 등 외부 효과 전부 중단", 0, eventSink.pushed.size)
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── 필수 테스트 3: 정확한 TTL 경계 straddle — 단일 시각 결정 ────────────

    @Test
    fun `exact ttl boundary holds atomically then renews consistently one ms later`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // 통화+trigger → CRITICAL, popup+event
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            appSignalTick(emptyList()) // trigger 종료, 통화 latched
            resetInteractionCounts()

            // 정확히 TTL 경계에서 도착한 실측 tick(뱅킹 진입): 만료도 renewal도 아님 —
            // 세션·currentEvent 유지, call-based 쿨다운은 정상 발동.
            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS)
            appUsageMonitor.bankingForeground.value = true
            runCurrent()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)
            assertNotNull(eventSink.currentEvent)
            assertEquals(0, eventSink.clearCurrentCount)
            verify(exactly = 1) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }

            // +1ms 실측 tick: 같은 tick 안에서 renewal(같은 ID)과 downgrade 정리가 일관 수행 —
            // 절반 상태(만료는 됐는데 정리는 안 됨) 없음. 쿨다운은 유지.
            clock.advanceMs(1L)
            appUsageMonitor.bankingForeground.value = false
            runCurrent()

            val renewed = requireNotNull(sessionTracker.sessionState.value)
            assertEquals(originalId, renewed.id)
            assertEquals(setOf(RiskSignal.UNKNOWN_CALLER), renewed.accumulatedSignals)
            assertEquals(1, eventSink.clearCurrentCount)
            assertNull(eventSink.currentEvent)
            verify(exactly = 1) { overlayManager.dismiss() }
            verify(exactly = 0) { cooldownManager.dismissIfShowing() }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── 스냅샷 renewal 검증 창의 경계 동작 ─────────────────────────────────

    /**
     * 창 내 **부분** 소멸: 해당 source의 실측 재방출이 소멸한 몫만 원자 rebase로 걷어내고
     * 세션은 같은 ID로 유지한다. 내려간 표시(팝업/currentEvent)는 정리, 쿨다운·알림은 불변.
     */
    @Test
    fun `partial signal decay within the validation window rebases the renewal in place`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // CRITICAL, popup+event
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal basis = {UNKNOWN_CALLER, REMOTE_CONTROL}
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            // 원격제어 종료 실측이 창 내 도착 — APP 몫 {REMOTE}만 소멸 → rebase, 세션 유지
            appSignalTick(emptyList())

            val rebased = requireNotNull(sessionTracker.sessionState.value)
            assertEquals("부분 소멸은 무효화가 아니라 rebase", originalId, rebased.id)
            assertEquals(setOf(RiskSignal.UNKNOWN_CALLER), rebased.accumulatedSignals)
            assertEquals(1, eventSink.clearCurrentCount) // downgrade — 잔존 INTERRUPT 표시 정리
            verify(exactly = 1) { overlayManager.dismiss() }
            verify(exactly = 0) { cooldownManager.dismissIfShowing() }
            verify(exactly = 0) { notificationManager.notify(any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 무관한 source 방출(banking flip)은 renewal 검증 토큰을 소비하지 못한다. */
    @Test
    fun `unrelated banking emission does not consume the renewal validation token`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal, token = {APP_USAGE: {REMOTE_CONTROL}}
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            // 무관한 banking 방출 — APP source 재방출이 아니므로 토큰 몫 소비 없음
            appUsageMonitor.bankingForeground.value = true
            runCurrent()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            // 이후 도착한 진짜 empty가 여전히 renewal을 무효화해야 한다
            appSignalTick(emptyList())
            assertNull("실측 empty의 무효화 권리가 보존되어야 함", sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 신호 경로 renewal(비-maintenance)에도 검증 토큰이 발급된다. */
    @Test
    fun `non-maintenance renewal also gets a validation token`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            // TTL 직후 deviceEnv 방출이 tick을 깨움 — latched 통화 신호(conflated, 미재방출)와의
            // 겹침으로 신호 경로 renewal이 일어난다. 그 통화 값 역시 stale일 수 있으므로 토큰 발급.
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            deviceEnvMonitor.deviceEnvSignals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
            runCurrent()
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            // 통화 종료 실측이 창 내 도착 → CALL 몫 {UNKNOWN_CALLER} 소멸 → 무효화
            callSignalTick(emptyList())
            assertNull("신호 경로 renewal도 실측 재방출로 검증되어야 함", sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** reset 이전에 생산된 스냅샷 캐시는 maintenance가 재사용하지 못한다 (flush 규칙). */
    @Test
    fun `maintenance never reuses a snapshot produced before a user reset`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER)) // 캐시(pre-reset 생산)
            sessionTracker.resetAfterUserConfirmedSafe() // 사용자 확인 — epoch 전진
            // combine 방출 없이 debug 경로로 새 세션 생성 → 캐시는 여전히 pre-reset 데이터
            sessionTracker.update(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            assertNotNull(sessionTracker.sessionState.value)
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick()

            // stale 캐시([UNKNOWN_CALLER])로 fresh episode/renewal을 만들지 않고 만료+정리만
            assertNull("reset 이전 캐시가 세션을 되살리면 안 됨", sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
            verify(exactly = 0) { notificationManager.notify(any()) }
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 창 **밖**의 신호 소멸은 무효화가 아니라 통상 TTL 의미론에 맡긴다 (통화 종료 ≠ 즉시 세션 종료). */
    @Test
    fun `signal decay after the validation window is left to normal ttl semantics`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val originalId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal basis = {UNKNOWN_CALLER}
            assertEquals(originalId, sessionTracker.sessionState.value?.id)

            clock.advanceMs(RENEWAL_VALIDATION_WINDOW_MS + 1L)
            callSignalTick(emptyList()) // 통화 종료 — 창 밖 정상 감쇠

            assertEquals("무효화 없이 세션 유지 — 이후 TTL이 처리", originalId, sessionTracker.sessionState.value?.id)
            assertNoCleanup()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── per-source reset 위생검사 (혼합 epoch 우회 차단) ────────────────────

    /** reset 후 무관한 BANKING 방출이 stale CALL 값을 승인해 세션·쿨다운을 되살리면 안 된다. */
    @Test
    fun `stale call value cannot recreate a session after reset via banking emission`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER)) // CALL 값은 reset 이전 생산
            sessionTracker.resetAfterUserConfirmedSafe()
            resetInteractionCounts()

            appUsageMonitor.bankingForeground.value = true // BANKING만 fresh
            runCurrent()

            assertNull("stale CALL 값이 세션을 되살리면 안 됨", sessionTracker.sessionState.value)
            verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
            verify(exactly = 0) { notificationManager.notify(any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** reset 후 DEVICE_ENV만 방출됐을 때 stale APP trigger가 팝업/세션을 되살리면 안 된다. */
    @Test
    fun `stale app trigger cannot recreate a session after reset via device emission`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // APP 값은 reset 이전 생산
            sessionTracker.resetAfterUserConfirmedSafe()
            resetInteractionCounts()

            deviceEnvMonitor.deviceEnvSignals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
            runCurrent()

            assertNull("stale APP trigger가 세션을 되살리면 안 됨", sessionTracker.sessionState.value)
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { notificationManager.notify(any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── renewal 정체성: 근거 전멸 분리·직접 확인 source ─────────────────────

    /** renewal 근거가 전멸하면 잔존 새 신호는 낡은 ID를 승계하지 않고 fresh episode가 된다. */
    @Test
    fun `dead renewal basis splits surviving new signals into a fresh episode`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val oldId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            // TTL 직후 새 trigger 도착 — latched 통화 신호와의 겹침으로 renewal(같은 ID) + REMOTE 추가
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            assertEquals(oldId, sessionTracker.sessionState.value?.id)
            val eventAfterRenewal = requireNotNull(eventSink.currentEvent)

            // 통화 종료 실측 → renewal 근거 {UNKNOWN_CALLER} 전멸 → REMOTE는 새 episode로 분리
            callSignalTick(emptyList())

            val fresh = requireNotNull(sessionTracker.sessionState.value) {
                "잔존 신호(REMOTE)는 새 episode로 살아있어야 함"
            }
            assertNotEquals("죽은 근거 위의 정체성 승계 금지", oldId, fresh.id)
            assertEquals(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), fresh.accumulatedSignals)
            val eventAfterSplit = requireNotNull(eventSink.currentEvent)
            assertNotEquals("새 episode의 이벤트로 교체", eventAfterRenewal.id, eventAfterSplit.id)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** renewal을 직접 정당화한(그 tick에서 전진한) source의 몫은 즉시 confirmed — 이후 감쇠는 정상 TTL. */
    @Test
    fun `renewal confirmed by its own contributing source survives later decay`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            val oldId = requireNotNull(sessionTracker.sessionState.value).id
            resetInteractionCounts()

            // CALL source가 직접 재방출하며 renewal — 근거 {UNKNOWN_CALLER}는 이번 tick 실측
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION))
            assertEquals(oldId, sessionTracker.sessionState.value?.id)

            // 직후 통화 종료 — stale 반박이 아니라 정상 감쇠: 세션은 TTL까지 유지
            callSignalTick(emptyList())

            assertEquals("직접 확인된 renewal은 즉시 무효화되지 않는다", oldId, sessionTracker.sessionState.value?.id)
            assertNoCleanup()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 45초 창의 정확한 경계(==)까지는 검증이 유효하다 (+1ms 초과는 별도 테스트가 고정). */
    @Test
    fun `renewal token still validates at the exact window boundary`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal, token {APP_USAGE: {REMOTE_CONTROL}}

            clock.advanceMs(RENEWAL_VALIDATION_WINDOW_MS) // 정확히 경계
            appSignalTick(emptyList())

            assertNull("경계 시각(==)까지는 무효화 유효", sessionTracker.sessionState.value)
            assertCleanupExactlyOnce()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /**
     * stale BANKING 값은 edge를 만들지도, previousBankingForeground를 오염시키지도 못한다.
     * (참고: 이 테스트는 stale 판정 경로의 종단 안전성을 고정한다.
     *  "reset 전 생산 + 버퍼 대기 + reset 후 전달" 시나리오는 A안 구현으로 활성화된
     *  Standard dispatcher + Channel<Produced> 게이트 기반 테스트 2건이 별도로 고정한다.)
     */
    @Test
    fun `stale banking value cannot fire a cooldown edge after user reset`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            var injectReset = false
            every { overlayManager.isEndCallSuppressed() } answers {
                if (injectReset) {
                    injectReset = false
                    sessionTracker.resetAfterUserConfirmedSafe()
                }
                false
            }

            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            resetInteractionCounts()

            // banking=true 처리 도중 사용자 확인 — 이후 tick들에서 banking 구성원은 stale
            injectReset = true
            appUsageMonitor.bankingForeground.value = true
            runCurrent()
            assertNull(sessionTracker.sessionState.value)

            // fresh CALL 재방출로 세션이 정당히 재생성돼도 stale banking은 쿨다운을 못 만든다
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION))
            assertNotNull(sessionTracker.sessionState.value)
            verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** split은 정제된 현재 live 신호만 사용한다 — 이미 사라진 신호(과거 누적값)의 부활 금지. */
    @Test
    fun `dead basis split cannot resurrect signals that already vanished`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            resetInteractionCounts()
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal basis {UNKNOWN_CALLER}

            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // 출현
            appSignalTick(emptyList()) // 소멸 (accumulated에는 잔존)
            callSignalTick(emptyList()) // basis 전멸

            assertNull("사라진 REMOTE가 fresh episode로 부활하면 안 됨", sessionTracker.sessionState.value)
            assertNull(eventSink.currentEvent)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** basis 전멸과 새 신호가 같은 tick에 도착 — 중간 null 없이 old→fresh(새 ID) 단일 전이. */
    @Test
    fun `dead basis with simultaneous new signal splits atomically to a fresh id`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            resetInteractionCounts()
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal basis {UNKNOWN_CALLER}
            val renewedId = requireNotNull(sessionTracker.sessionState.value).id

            val emissions = mutableListOf<Boolean>() // true = non-null
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                sessionTracker.sessionState.collect { emissions += (it != null) }
            }

            // 같은 CALL 방출이 {UNKNOWN_CALLER} 소멸과 새 신호 도착을 동시에 나른다
            callSignalTick(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))

            val fresh = requireNotNull(sessionTracker.sessionState.value)
            assertNotEquals("죽은 근거 위 ID 승계 금지", renewedId, fresh.id)
            assertEquals(setOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS), fresh.accumulatedSignals)
            assertFalse("old→fresh 단일 전이 — 중간 null 금지", emissions.contains(false))

            collectJob.cancel()
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** HRDE 승계 예외는 DEVICE_ENV source 한정 — stale APP_USAGE가 나르는 HRDE는 승계 금지. */
    @Test
    fun `stale app source cannot carry the device modifier exemption`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            appSignalTick(listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)) // APP source가 HRDE를 나르는 비정상 경로 모델
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            sessionTracker.resetAfterUserConfirmedSafe()

            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)) // fresh CALL

            val next = requireNotNull(sessionTracker.sessionState.value)
            assertFalse(
                "APP source의 stale HRDE는 승계되지 않아야 함",
                RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT in next.accumulatedSignals,
            )
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** 지속 환경 modifier(HRDE)는 reset 이후 생성된 새 세션에도 승계된다 (초기화 1회 방출 모델). */
    @Test
    fun `static device modifier survives a user reset into the next session`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            deviceEnvMonitor.deviceEnvSignals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
            runCurrent() // 초기화 시 1회 방출 모델링
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            sessionTracker.resetAfterUserConfirmedSafe()

            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)) // fresh CALL

            val next = requireNotNull(sessionTracker.sessionState.value)
            assertTrue(
                "지속 환경 modifier는 reset 후 새 세션에도 유지되어야 함",
                RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT in next.accumulatedSignals,
            )
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── epoch guard (b)·재상승 재무장·no-snapshot 폴백 ─────────────────────

    /** guard (b) 직접 고정: 쿨다운 발동 직전에 끼어든 사용자 확인은 쿨다운을 막아야 한다. */
    @Test
    fun `confirmSafe during the cooldown stage aborts the cooldown trigger`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            var injectReset = false
            every { cooldownManager.isShowing() } answers {
                if (injectReset) {
                    injectReset = false
                    sessionTracker.resetAfterUserConfirmedSafe()
                }
                false
            }

            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER)) // call-based GUARDED

            injectReset = true
            appUsageMonitor.bankingForeground.value = true // 쿨다운 발동 tick 도중 사용자 확인
            runCurrent()

            verify(exactly = 0) { cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
            assertNull(sessionTracker.sessionState.value)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** renewal downgrade 시 통보 상한을 클램프해, 이후의 진짜 재상승이 다시 알림/이력을 낸다. */
    @Test
    fun `renewal downgrade re-arms future level escalation`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val coordinator = startCoordinator(clock)

        try {
            callSignalTick(listOf(RiskSignal.UNKNOWN_CALLER))
            appSignalTick(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) // CRITICAL 도달
            appSignalTick(emptyList()) // trigger 종료
            resetInteractionCounts()

            clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS + 1L)
            runMaintenanceTick() // renewal → GUARDED downgrade + 통보 상한 클램프
            verify(exactly = 0) { notificationManager.notify(any()) }
            val recordedBefore = eventSink.recorded.size

            // 진짜 재상승: 새 PASSIVE 신호 누적 → 클램프된 상한을 넘는 level 상승
            callSignalTick(
                listOf(
                    RiskSignal.UNKNOWN_CALLER,
                    RiskSignal.LONG_CALL_DURATION,
                    RiskSignal.REPEATED_UNKNOWN_CALLER,
                ),
            )

            verify(exactly = 1) { notificationManager.notify(any()) }
            assertEquals("재상승은 이력에 다시 기록되어야 함", recordedBefore + 1, eventSink.recorded.size)
            verify(exactly = 0) { overlayManager.show(any(), any(), any()) } // GUARDED — 팝업 없음
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /**
     * A안 종결 기준 1 (2026-07-16 활성화): 과거 stamped()는 flowOn 버퍼 **하류(수신 시점)**에서
     * epoch를 읽어, reset 이전에 생산되어 버퍼에 대기하던 BANKING=true가 reset 이후 전달되면
     * 새 epoch로 오표기됐다 (RED 실측: 쿨다운 1회 발동). 생산 경계 스탬프([Produced])가 도입된
     * 지금은 pre-reset 생산분이 정직하게 stale로 남아 per-source 위생검사에서 걸러진다.
     */
    @Test
    fun `banking produced before reset but delivered after must not fire a cooldown`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val bankingGate = Channel<Produced<Boolean>>(Channel.UNLIMITED)
        val gatedCallSignals = MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        val callM = mockk<CallRiskMonitor> {
            every { observeCallSignals() } returns gatedCallSignals
            every { currentCallId() } returns null
            every { isTelebankingAnchorHot() } returns false
        }
        val appM = mockk<AppUsageRiskMonitor> {
            every { observeAppUsageSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
            // flowOn 버퍼 모델링: 생산(trySend, 생산 시점 epoch 스탬프)과 전달(collect 재개)이
            // 분리된 banking flow
            every { observeBankingAppForeground() } returns flow {
                emit(Produced(false, 0L))
                for (value in bankingGate) emit(value)
            }
            every { latestBankingForegroundEventTimestamp(any()) } returns null
        }
        val installM = mockk<AppInstallRiskMonitor> {
            every { observeInstallSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val deviceM = mockk<DeviceEnvironmentRiskMonitor> {
            every { observeDeviceEnvironmentSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val sink = FakeRiskEventSink()
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val factory = RiskEventFactory().also { it.clock = clock.provider }
        val cooldown = mockk<BankingCooldownManager>(relaxed = true)
        val coordinator = DefaultRiskDetectionCoordinator(
            callMonitor = callM,
            appUsageMonitor = appM,
            appInstallMonitor = installM,
            deviceEnvMonitor = deviceM,
            evaluator = RiskEvaluatorImpl(),
            eventFactory = factory,
            eventSink = sink,
            notificationManager = mockk(relaxed = true),
            overlayManager = mockk(relaxed = true),
            cooldownManager = cooldown,
            sessionTracker = tracker,
            alertStateResolver = AlertStateResolver(),
            guardianRepository = FakeGuardianRepository(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.clock = clock.provider }
        coordinator.start()
        runCurrent() // 초기 tick (banking=false, previous=false)

        try {
            // 생산 시점(reset 전) epoch를 스탬프해 버퍼에 적재 — flowOn 버퍼 대기 모델링.
            assertTrue(
                "생산(버퍼 적재)이 성공해야 함",
                bankingGate.trySend(Produced(true, tracker.userResetEpoch)).isSuccess,
            )
            tracker.resetAfterUserConfirmedSafe() // 사용자 안전 확인
            val sessionAfterReset =
                requireNotNull(tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList()))
            runCurrent() // 버퍼 전달 재개 — 생산 경계 스탬프라 stale로 정직하게 판정된다

            // liveness: BANKING=true가 나른 tick이 실제 처리됐음을 증명 (false-green 방지) —
            // reset 후 세션의 GUARDED escalation 이력이 정확히 1건 기록된다.
            assertEquals("banking tick 처리 증거", 1, sink.recorded.size)
            assertEquals("세션 ID 유지", sessionAfterReset.id, tracker.sessionState.value?.id)
            verify(exactly = 0) { cooldown.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /**
     * A안 종결 기준 2 (2026-07-16 활성화): reset 이전에 생산되어 버퍼에 대기하던 **creator 신호**
     * (APP의 REMOTE_CONTROL)가 reset 이후 전달돼도 세션·알림·이력·팝업을 만들지 못한다
     * (RED 실측: 세션 생성). 생산 경계 스탬프가 pre-reset 생산분을 정직하게 stale로 유지한다.
     */
    @Test
    fun `app creator produced before reset but delivered after must not create a session`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val appGate = Channel<Produced<List<RiskSignal>>>(Channel.UNLIMITED)
        val callM = mockk<CallRiskMonitor> {
            every { observeCallSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
            every { currentCallId() } returns null
            every { isTelebankingAnchorHot() } returns false
        }
        val appM = mockk<AppUsageRiskMonitor> {
            // flowOn 버퍼 모델링: 생산(trySend, 생산 시점 epoch 스탬프)과 전달(collect 재개)이
            // 분리된 app-signal flow
            every { observeAppUsageSignals() } returns flow {
                emit(Produced(emptyList<RiskSignal>(), 0L))
                for (value in appGate) emit(value)
            }
            every { observeBankingAppForeground() } returns MutableStateFlow(Produced(false, 0L))
            every { latestBankingForegroundEventTimestamp(any()) } returns null
        }
        val installM = mockk<AppInstallRiskMonitor> {
            every { observeInstallSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val deviceM = mockk<DeviceEnvironmentRiskMonitor> {
            every { observeDeviceEnvironmentSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val sink = FakeRiskEventSink()
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val factory = RiskEventFactory().also { it.clock = clock.provider }
        val overlay = mockk<RiskOverlayManager>(relaxed = true)
        val notif = mockk<RiskNotificationManager>(relaxed = true)
        val coordinator = DefaultRiskDetectionCoordinator(
            callMonitor = callM,
            appUsageMonitor = appM,
            appInstallMonitor = installM,
            deviceEnvMonitor = deviceM,
            evaluator = RiskEvaluatorImpl(),
            eventFactory = factory,
            eventSink = sink,
            notificationManager = notif,
            overlayManager = overlay,
            cooldownManager = mockk(relaxed = true),
            sessionTracker = tracker,
            alertStateResolver = AlertStateResolver(),
            guardianRepository = FakeGuardianRepository(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ).also { it.clock = clock.provider }
        coordinator.start()
        runCurrent()

        try {
            // 생산 시점(reset 전) epoch를 스탬프해 버퍼에 적재.
            assertTrue(
                "생산(버퍼 적재)이 성공해야 함",
                appGate.trySend(
                    Produced(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), tracker.userResetEpoch),
                ).isSuccess,
            )
            tracker.resetAfterUserConfirmedSafe() // 사용자 안전 확인
            runCurrent() // 버퍼 전달 재개

            assertNull("stale creator가 세션을 만들면 안 됨", tracker.sessionState.value)
            assertEquals("이력 0", 0, sink.recorded.size)
            assertEquals("승격 0", 0, sink.pushed.size)
            verify(exactly = 0) { notif.notify(any()) }
            verify(exactly = 0) { overlay.show(any(), any(), any()) }

            // liveness: reset "이후" 생산된 같은 신호는 정상적으로 세션을 만든다
            assertTrue(
                appGate.trySend(
                    Produced(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), tracker.userResetEpoch),
                ).isSuccess,
            )
            runCurrent()
            assertNotNull("정상 경로 생존 증거", tracker.sessionState.value)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /**
     * ghost 조회(동기 latestBankingForegroundEventTimestamp) **도중** 사용자 확인이 끼어들면,
     * 조회 복귀 후의 외부 효과(쿨다운)는 실행되어서는 안 된다 — 쿨다운 stage 직전의 epoch
     * 재검증(b)은 조회보다 앞서 실행되므로 이 창을 보지 못한다.
     * RED-first: 수정(조회 직후·cooldown 호출 전 재검증) 전에는 쿨다운이 1회 발동한다.
     */
    @Test
    fun `reset during ghost banking query must not fire the cooldown afterwards`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val callSignals = MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        val banking = MutableStateFlow(Produced(false, 0L))
        val callM = mockk<CallRiskMonitor> {
            every { observeCallSignals() } returns callSignals
            every { currentCallId() } returns null
            every { isTelebankingAnchorHot() } returns false
        }
        val appM = mockk<AppUsageRiskMonitor> {
            every { observeAppUsageSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
            every { observeBankingAppForeground() } returns banking
            // ghost 조회가 도는 동안 사용자 안전 확인이 끼어든다.
            // 반환값(700_000)은 쿨다운 구간 [500_000, 600_000] 밖 — ghost=false로 판정되어
            // 쿨다운 발동 경로가 계속 진행된다.
            every { latestBankingForegroundEventTimestamp(any()) } answers {
                tracker.resetAfterUserConfirmedSafe()
                700_000L
            }
        }
        val installM = mockk<AppInstallRiskMonitor> {
            every { observeInstallSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val deviceM = mockk<DeviceEnvironmentRiskMonitor> {
            every { observeDeviceEnvironmentSignals() } returns MutableStateFlow(Produced(emptyList<RiskSignal>(), 0L))
        }
        val sink = FakeRiskEventSink()
        val factory = RiskEventFactory().also { it.clock = clock.provider }
        val cooldown = mockk<BankingCooldownManager>(relaxed = true) {
            every { showedAtMillis } returns 500_000L
            every { dismissedAtMillis } returns 600_000L
            every { isShowing() } returns false
        }
        val coordinator = DefaultRiskDetectionCoordinator(
            callMonitor = callM,
            appUsageMonitor = appM,
            appInstallMonitor = installM,
            deviceEnvMonitor = deviceM,
            evaluator = RiskEvaluatorImpl(),
            eventFactory = factory,
            eventSink = sink,
            notificationManager = mockk(relaxed = true),
            overlayManager = mockk(relaxed = true),
            cooldownManager = cooldown,
            sessionTracker = tracker,
            alertStateResolver = AlertStateResolver(),
            guardianRepository = FakeGuardianRepository(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        ).also { it.clock = clock.provider }
        coordinator.start()
        runCurrent()

        try {
            callSignals.value = Produced(listOf(RiskSignal.UNKNOWN_CALLER), 0L) // call-based GUARDED 세션
            runCurrent()
            banking.value = Produced(true, 0L) // banking edge → ghost 조회 (조회 도중 reset 주입)
            runCurrent()

            verify(exactly = 0) { cooldown.triggerIfNotActive(any(), any(), any(), any()) }
            assertNull("사용자 확인 결과가 유지되어야 함", tracker.sessionState.value)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    /** combine 방출 전(스냅샷 없음)에 만든 세션(DebugViewModel 경로)도 maintenance가 만료+정리한다. */
    @Test
    fun `maintenance expires a session created before any combine emission`() = runTest {
        val clock = FakeClock(now = 1_000_000L)
        val silentCall = MutableSharedFlow<Produced<List<RiskSignal>>>()
        val silentApp = MutableSharedFlow<Produced<List<RiskSignal>>>()
        val silentBanking = MutableSharedFlow<Produced<Boolean>>()
        val silentInstall = MutableSharedFlow<Produced<List<RiskSignal>>>()
        val silentDevice = MutableSharedFlow<Produced<List<RiskSignal>>>()
        val callM = mockk<CallRiskMonitor> {
            every { observeCallSignals() } returns silentCall
            every { currentCallId() } returns null
            every { isTelebankingAnchorHot() } returns false
        }
        val appM = mockk<AppUsageRiskMonitor> {
            every { observeAppUsageSignals() } returns silentApp
            every { observeBankingAppForeground() } returns silentBanking
            every { latestBankingForegroundEventTimestamp(any()) } returns null
        }
        val installM = mockk<AppInstallRiskMonitor> { every { observeInstallSignals() } returns silentInstall }
        val deviceM = mockk<DeviceEnvironmentRiskMonitor> {
            every { observeDeviceEnvironmentSignals() } returns silentDevice
        }
        val sink = FakeRiskEventSink()
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val factory = RiskEventFactory().also { it.clock = clock.provider }
        val overlay = mockk<RiskOverlayManager>(relaxed = true)
        val cooldown = mockk<BankingCooldownManager>(relaxed = true)
        val notif = mockk<RiskNotificationManager>(relaxed = true)
        val coordinator = DefaultRiskDetectionCoordinator(
            callMonitor = callM,
            appUsageMonitor = appM,
            appInstallMonitor = installM,
            deviceEnvMonitor = deviceM,
            evaluator = RiskEvaluatorImpl(),
            eventFactory = factory,
            eventSink = sink,
            notificationManager = notif,
            overlayManager = overlay,
            cooldownManager = cooldown,
            sessionTracker = tracker,
            alertStateResolver = AlertStateResolver(),
            guardianRepository = FakeGuardianRepository(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        ).also { it.clock = clock.provider }
        coordinator.start()
        runCurrent()

        try {
            tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList()) // DebugViewModel 경로
            assertNotNull(tracker.sessionState.value)

            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            advanceTimeBy(ANCHOR_MIRROR_INTERVAL_MS)
            runCurrent()

            assertNull("스냅샷이 없어도 만료+정리는 수행되어야 함", tracker.sessionState.value)
            assertEquals(1, sink.clearCurrentCount)
            verify(exactly = 1) { overlay.dismiss() }
            verify(exactly = 1) { cooldown.dismissIfShowing() }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun TestScope.callSignalTick(signals: List<RiskSignal>) {
        callMonitor.callSignals.value = signals
        runCurrent()
    }

    private fun TestScope.appSignalTick(signals: List<RiskSignal>) {
        appUsageMonitor.appSignals.value = signals
        runCurrent()
    }

    private fun TestScope.runMaintenanceTick() {
        advanceTimeBy(ANCHOR_MIRROR_INTERVAL_MS)
        runCurrent()
    }

    private fun resetInteractionCounts() {
        clearMocks(
            overlayManager,
            cooldownManager,
            notificationManager,
            answers = false,
            recordedCalls = true,
        )
        eventSink.clearCurrentCount = 0
    }

    private fun assertNoCleanup() {
        assertEquals(0, eventSink.clearCurrentCount)
        verify(exactly = 0) { overlayManager.dismiss() }
        verify(exactly = 0) { cooldownManager.dismissIfShowing() }
    }

    private fun assertCleanupExactlyOnce() {
        assertEquals(1, eventSink.clearCurrentCount)
        verify(exactly = 1) { overlayManager.dismiss() }
        verify(exactly = 1) { cooldownManager.dismissIfShowing() }
    }
}
