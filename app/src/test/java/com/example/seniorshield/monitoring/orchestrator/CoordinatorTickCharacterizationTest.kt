package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.session.DEFAULT_IDLE_TIMEOUT_MS
import com.example.seniorshield.testutil.CoordinatorTestHarness
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tick 단계 추출 전후에 보존해야 하는 성질을 테스트별로 고정한다.
 * - (a) noSessionExitAssignsDifferentEffectiveBanking: 조기 종료 시 banking 이전값 대입.
 * - (b) maintenanceWithoutTimeoutPreservesPreviousBanking: 타임아웃 없는 maintenance 출구에서 미대입.
 * - (b) maintenanceWithoutSnapshotPreservesPreviousBanking: 스냅샷 없는 maintenance 출구에서 미대입.
 * - (c) escapingCancellationDoesNotAssignDifferentEffectiveBanking: 전파되는 취소 시 미대입.
 * - (d) handledPushFailureAssignsDifferentEffectiveBanking: 처리된 push 실패 후 대입.
 * - (e) escalationReplacementAbortsSameTickNewTriggerEffects: escalation 중단 시 같은 tick의 new-trigger 효과 0.
 * - 양성 대조 unnotifiedRemoteTriggerActuallyFiresWithoutEscalation: escalation 없이 미통보 원격 trigger가 실제 발동.
 * 공개 API로 관찰할 수 없는 banking 이전값을 확인하려고 private 필드를 reflection으로 읽으며, 준비는 실제 입력 flow를 사용한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorTickCharacterizationTest {

    @Test
    fun noSessionExitAssignsDifferentEffectiveBanking() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        try {
            assertFalse(previousBanking(coordinator))
            assertNull(harness.sessionTracker.sessionState.value)
            val cleanupsBefore = harness.eventSink.clearCurrentCount

            harness.appUsageMonitor.bankingForeground.value = true
            runCurrent()

            assertEquals(harness.sessionTracker.userResetEpoch,
                harness.appUsageMonitor.bankingForeground.flow.value.producedAtEpoch)
            assertNull(harness.sessionTracker.sessionState.value)
            assertEquals(cleanupsBefore + 1, harness.eventSink.clearCurrentCount)
            assertTrue(previousBanking(coordinator))
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun maintenanceWithoutTimeoutPreservesPreviousBanking() = runTest {
        val harness = CoordinatorTestHarness()
        val clock = FakeClock(now = 1_000_000L)
        harness.appUsageMonitor.bankingForeground.value = true
        harness.callMonitor.logAnchorReads = true
        val coordinator = with(harness) { start(clock) }
        try {
            // A live, non-expired session makes the first maintenance exit explicit.
            val session = requireNotNull(harness.sessionTracker.update(
                listOf(RiskSignal.UNKNOWN_CALLER), emptyList(),
            ))
            assertTrue(previousBanking(coordinator))
            assertFalse(harness.sessionTracker.isCurrentSessionIdleTimedOut())
            val mirrorsBefore = harness.safeConfirmationOperations.count { it == "mirror" }
            val cleanupsBefore = harness.eventSink.clearCurrentCount

            advanceTimeBy(ANCHOR_MIRROR_INTERVAL_MS)
            runCurrent()

            assertEquals(mirrorsBefore + 1,
                harness.safeConfirmationOperations.count { it == "mirror" })
            assertEquals(session.id, harness.sessionTracker.sessionState.value?.id)
            assertEquals(cleanupsBefore, harness.eventSink.clearCurrentCount)
            assertTrue(previousBanking(coordinator))
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun maintenanceWithoutSnapshotPreservesPreviousBanking() = runTest {
        val harness = CoordinatorTestHarness()
        val clock = FakeClock(now = 1_000_000L)
        var allowCallEmissions = true
        val gatedCallMonitor = object : CallRiskMonitor by harness.callMonitor {
            override fun observeCallSignals(): Flow<Produced<List<RiskSignal>>> = flow {
                if (allowCallEmissions) {
                    emitAll(harness.callMonitor.observeCallSignals())
                } else {
                    awaitCancellation()
                }
            }
        }
        harness.appUsageMonitor.bankingForeground.value = true
        val coordinator = newCoordinator(harness, clock, gatedCallMonitor)
        coordinator.start()
        runCurrent()
        try {
            // Seed true through a real tick, then obtain a fresh launch-local snapshot.
            assertTrue(previousBanking(coordinator))
            coordinator.stop()
            runCurrent()
            allowCallEmissions = false
            harness.appUsageMonitor.bankingForeground.value = false
            coordinator.start()
            runCurrent()
            assertTrue(previousBanking(coordinator))
            harness.sessionTracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
            val cleanupsBefore = harness.eventSink.clearCurrentCount
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
            assertTrue(harness.sessionTracker.isCurrentSessionIdleTimedOut())

            advanceTimeBy(ANCHOR_MIRROR_INTERVAL_MS)
            runCurrent()

            // Without CALL emission, combine cannot set latestSignals in this launch.
            assertNull(harness.sessionTracker.sessionState.value)
            assertEquals(cleanupsBefore + 1, harness.eventSink.clearCurrentCount)
            assertFalse(harness.appUsageMonitor.bankingForeground.value)
            assertTrue(previousBanking(coordinator))
            verify(exactly = 0) { harness.notificationManager.notify(any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun escapingCancellationDoesNotAssignDifferentEffectiveBanking() = runTest {
        val harness = CoordinatorTestHarness()
        harness.appUsageMonitor.bankingForeground.value = true
        harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val coordinator = newCoordinator(harness, FakeClock(now = 1_000_000L))
        val entered = CompletableDeferred<Job>()
        val hookExited = CompletableDeferred<Unit>()
        coordinator.beforePublicationNotificationCommit = {
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            try {
                awaitCancellation()
            } finally {
                hookExited.complete(Unit)
            }
        }
        try {
            assertFalse(previousBanking(coordinator))
            coordinator.start()
            runCurrent()
            assertTrue("target tick must reach the suspend hook", entered.isCompleted)
            val tickJob = entered.await()
            assertTrue(harness.appUsageMonitor.bankingForeground.value)
            assertEquals(harness.sessionTracker.userResetEpoch,
                harness.appUsageMonitor.bankingForeground.flow.value.producedAtEpoch)
            assertFalse(previousBanking(coordinator))
            assertEquals(1, harness.eventSink.pushed.size)

            coordinator.stop()
            runCurrent()
            tickJob.join() // stop() itself only requests cancellation.

            assertTrue(hookExited.isCompleted)
            assertTrue(tickJob.isCancelled)
            assertTrue(tickJob.isCompleted)
            assertFalse(previousBanking(coordinator))
            verify(exactly = 0) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun handledPushFailureAssignsDifferentEffectiveBanking() = runTest {
        val harness = CoordinatorTestHarness()
        harness.appUsageMonitor.bankingForeground.value = true
        harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        var pushAttempts = 0
        harness.eventSink.beforeCurrentEventSet = {
            pushAttempts += 1
            throw IllegalStateException("R1.0 handled storage failure")
        }
        val coordinator = newCoordinator(harness, FakeClock(now = 1_000_000L))
        try {
            assertFalse(previousBanking(coordinator))
            assertEquals(harness.sessionTracker.userResetEpoch,
                harness.appUsageMonitor.bankingForeground.flow.value.producedAtEpoch)
            coordinator.start()
            runCurrent()

            assertEquals(1, pushAttempts)
            assertTrue(harness.eventSink.pushed.isEmpty())
            assertNull(harness.eventSink.currentEvent)
            assertTrue(previousBanking(coordinator))
            verify(exactly = 0) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }

            // An ordinary caught push failure must leave collection able to process a later tick.
            harness.eventSink.beforeCurrentEventSet = null
            harness.appUsageMonitor.bankingForeground.value = false
            runCurrent()
            assertFalse(previousBanking(coordinator))
            assertEquals(1, harness.eventSink.pushed.size)
            verify(exactly = 1) { harness.notificationManager.notify(any()) }
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            harness.eventSink.beforeCurrentEventSet = null
            coordinator.stop()
            runCurrent()
        }
    }

    @Test
    fun escalationReplacementAbortsSameTickNewTriggerEffects() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        val entered = CompletableDeferred<RiskEvent>()
        val release = CompletableDeferred<Unit>()
        val notifiedIds = mutableListOf<String>()
        val overlayIds = mutableListOf<String>()
        every { harness.notificationManager.notify(any()) } answers {
            notifiedIds += firstArg<RiskEvent>().id
        }
        every { harness.overlayManager.show(any(), any(), any()) } answers {
            overlayIds += firstArg<RiskEvent>().id
        }
        coordinator.beforePublicationNotificationCommit = { event ->
            // Only hold the original escalation; an erroneous later new-trigger must run.
            if (entered.complete(event)) release.await()
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()
            assertTrue(entered.isCompleted)
            val original = entered.await()
            val session = requireNotNull(harness.sessionTracker.sessionState.value)
            assertEquals(AlertState.INTERRUPT, harness.alertStateResolver.resolve(session))
            assertTrue(session.notifiedActiveThreats.isEmpty())
            assertFalse(harness.cooldownManager.isShowing())
            assertFalse(harness.appUsageMonitor.bankingForeground.value)
            assertTrue(notifiedIds.isEmpty())
            assertTrue(overlayIds.isEmpty())

            val replacement = original.copy(id = "R1.0-replacement-publisher")
            coordinator.publishAndShowDebugOverlay(replacement)
            assertEquals(listOf(original.id, replacement.id), harness.eventSink.pushed.map { it.id })
            assertEquals(listOf(replacement.id), overlayIds)
            // Baseline AFTER replacement: its event/overlay cannot be mistaken for target effects.
            val pushedAfterReplacement = harness.eventSink.pushed.toList()
            val recordedAfterReplacement = harness.eventSink.recorded.toList()
            val notificationsAfterReplacement = notifiedIds.toList()
            val overlaysAfterReplacement = overlayIds.toList()

            release.complete(Unit)
            runCurrent()

            assertEquals(pushedAfterReplacement, harness.eventSink.pushed)
            assertEquals(recordedAfterReplacement, harness.eventSink.recorded)
            assertEquals(notificationsAfterReplacement, notifiedIds)
            assertEquals(overlaysAfterReplacement, overlayIds)
            assertEquals(replacement.id, harness.eventSink.currentEvent?.id)
            assertTrue(requireNotNull(harness.sessionTracker.sessionState.value).notifiedActiveThreats.isEmpty())
            // If escalation's abort were swallowed, popupShownThisTick/cooldownFiredThisTick
            // are false, REC is unnotified and S2 is unarmed: new-trigger would publish.
            // The next test is an executable positive control for that active input.
        } finally {
            coordinator.stop()
            release.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun unnotifiedRemoteTriggerActuallyFiresWithoutEscalation() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        try {
            val remote = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            val session = requireNotNull(harness.sessionTracker.update(emptyList(), remote))
            // Disable only escalation. Preserve the same raw input, unnotified trigger,
            // banking=false, no cooldown and unarmed S2 as the replacement test.
            harness.sessionTracker.markAlertStateNotified(harness.alertStateResolver.resolve(session))
            harness.sessionTracker.markNotified(harness.evaluator.evaluate(session.accumulatedSignals.toList()).level)
            assertTrue(session.notifiedActiveThreats.isEmpty())
            assertTrue(harness.eventSink.pushed.isEmpty())

            harness.appUsageMonitor.appSignals.value = remote
            runCurrent()

            assertEquals(1, harness.eventSink.pushed.size)
            assertTrue(harness.eventSink.recorded.isEmpty())
            verify(exactly = 1) { harness.notificationManager.notify(any()) }
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(remote.toSet(),
                requireNotNull(harness.sessionTracker.sessionState.value).notifiedActiveThreats)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private fun previousBanking(coordinator: DefaultRiskDetectionCoordinator): Boolean =
        DefaultRiskDetectionCoordinator::class.java.getDeclaredField("previousBankingForeground")
            .apply { isAccessible = true }
            .getBoolean(coordinator)

    // Same assembly as CoordinatorTestHarness.start(), but allows installing a hook
    // before the first combine emission or withholding CALL after a stop/start.
    private fun TestScope.newCoordinator(
        harness: CoordinatorTestHarness,
        clock: FakeClock,
        callMonitor: CallRiskMonitor = harness.callMonitor,
    ): DefaultRiskDetectionCoordinator {
        harness.sessionTracker.clock = clock.provider
        harness.eventFactory.clock = clock.provider
        return DefaultRiskDetectionCoordinator(
            callMonitor = callMonitor,
            appUsageMonitor = harness.appUsageMonitor,
            appInstallMonitor = harness.appInstallMonitor,
            deviceEnvMonitor = harness.deviceEnvMonitor,
            evaluator = harness.evaluator,
            eventFactory = harness.eventFactory,
            eventSink = harness.eventSink,
            notificationManager = harness.notificationManager,
            overlayManager = harness.overlayManager,
            cooldownManager = harness.cooldownManager,
            sessionTracker = harness.sessionTracker,
            alertStateResolver = harness.alertStateResolver,
            guardianRepository = harness.guardianRepository,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        ).also { it.clock = clock.provider }
    }
}
