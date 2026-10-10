package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.testutil.CoordinatorTestHarness
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardedHistoryRecordFailureTest {
    @Test
    fun runtimeRecordFailureStillNotifiesAndNextPassiveEscalationRecords() = runTest {
        assertRecordFailureThenRecovery(IllegalStateException("history insert failed"))
    }

    @Test
    fun checkedRecordFailureStillNotifiesAndCoordinatorSurvives() = runTest {
        assertRecordFailureThenRecovery(java.io.IOException("history write failed"))
    }

    @Test
    fun stopThenRecordIOExceptionAbortsNotificationAndTickAssignment() = runTest {
        val harness = CoordinatorTestHarness()
        val clock = FakeClock(now = 1_000_000L)
        val entered = CompletableDeferred<Job>()
        val releaseRecord = CompletableDeferred<Unit>()
        val hookExited = CompletableDeferred<Unit>()
        lateinit var coordinator: DefaultRiskDetectionCoordinator
        harness.eventSink.beforeRecord = {
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            try {
                // Wait only to install probes and inspect the tick before cancellation.
                releaseRecord.await()
                // No suspension between stop and the non-cancellation exception.
                coordinator.stop()
                throw java.io.IOException("history write failed after synchronous stop")
            } finally {
                hookExited.complete(Unit)
            }
        }
        // Keep effective banking=true and previous=false without an earlier cooldown:
        // the foreground event belongs to an already dismissed cooldown interval.
        every { harness.cooldownManager.showedAtMillis } returns 900_000L
        every { harness.cooldownManager.dismissedAtMillis } returns 950_000L
        harness.appUsageMonitor.latestBankingTs = 925_000L
        harness.appUsageMonitor.bankingForeground.value = true
        harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
        coordinator = with(harness) { start(clock) }
        var popupAccountingCalls = 0
        coordinator.beforePublicationPopupAccountingCommit = { popupAccountingCalls += 1 }
        try {
            assertTrue("record must suspend before stop", entered.isCompleted)
            val tickJob = entered.await()
            assertFalse(previousBanking(coordinator))
            assertTrue(tickJob.isActive)
            verify(exactly = 0) { harness.notificationManager.notify(any()) }

            releaseRecord.complete(Unit)
            runCurrent()
            tickJob.join()

            assertTrue(hookExited.isCompleted)
            assertTrue(tickJob.isCancelled)
            assertTrue(tickJob.isCompleted)
            assertFalse(previousBanking(coordinator))
            val session = requireNotNull(harness.sessionTracker.sessionState.value)
            assertEquals(AlertState.GUARDED, harness.alertStateResolver.resolve(session))
            assertNull(session.notifiedAlertState)
            assertNull(session.notifiedLevel)
            assertTrue(session.notifiedActiveThreats.isEmpty())
            assertTrue(harness.eventSink.recorded.isEmpty())
            assertTrue(harness.eventSink.pushed.isEmpty())
            assertNull(harness.eventSink.currentEvent)
            assertEquals(0, popupAccountingCalls)
            verify(exactly = 0) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { harness.cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private fun TestScope.assertRecordFailureThenRecovery(failure: Exception) {
        val harness = CoordinatorTestHarness()
        harness.eventSink.recordFailure = failure
        var recordJob: Job? = null
        harness.eventSink.beforeRecord = { recordJob = currentCoroutineContext()[Job] }
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        var popupAccountingCalls = 0
        coordinator.beforePublicationPopupAccountingCommit = { popupAccountingCalls += 1 }
        try {
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()

            val firstSession = requireNotNull(harness.sessionTracker.sessionState.value)
            assertEquals(AlertState.GUARDED, harness.alertStateResolver.resolve(firstSession))
            assertEquals(AlertState.GUARDED, firstSession.notifiedAlertState)
            assertEquals(RiskLevel.LOW, firstSession.notifiedLevel)
            val firstTickJob = requireNotNull(recordJob)
            assertTrue(firstTickJob.isActive)
            assertTrue(harness.eventSink.recorded.isEmpty())
            assertTrue(harness.eventSink.pushed.isEmpty())
            assertNull(harness.eventSink.currentEvent)
            assertEquals(0, popupAccountingCalls)
            verify(exactly = 1) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }

            harness.eventSink.recordFailure = null
            // PASSIVE only: UNKNOWN_CALLER(20) + LONG_CALL_DURATION(30) = HIGH(50).
            harness.callMonitor.callSignals.value = listOf(
                RiskSignal.UNKNOWN_CALLER,
                RiskSignal.LONG_CALL_DURATION,
            )
            runCurrent()

            val recoveredSession = requireNotNull(harness.sessionTracker.sessionState.value)
            assertEquals(firstSession.id, recoveredSession.id)
            assertEquals(AlertState.GUARDED, harness.alertStateResolver.resolve(recoveredSession))
            assertEquals(AlertState.GUARDED, recoveredSession.notifiedAlertState)
            assertEquals(RiskLevel.HIGH, recoveredSession.notifiedLevel)
            assertTrue(recoveredSession.notifiedActiveThreats.isEmpty())
            assertTrue(firstTickJob.isActive)
            assertEquals(firstTickJob, recordJob)
            assertEquals(1, harness.eventSink.recorded.size)
            val recorded = harness.eventSink.recorded.single()
            assertEquals(RiskLevel.HIGH, recorded.level)
            assertEquals(
                setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
                recorded.signals.toSet(),
            )
            assertTrue(harness.eventSink.pushed.isEmpty())
            assertNull(harness.eventSink.currentEvent)
            assertEquals(0, popupAccountingCalls)
            verify(exactly = 2) { harness.notificationManager.notify(any()) }
            verify(exactly = 1) { harness.notificationManager.notify(recorded) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { harness.cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private fun previousBanking(coordinator: DefaultRiskDetectionCoordinator): Boolean =
        DefaultRiskDetectionCoordinator::class.java.getDeclaredField("previousBankingForeground")
            .apply { isAccessible = true }
            .getBoolean(coordinator)
}
