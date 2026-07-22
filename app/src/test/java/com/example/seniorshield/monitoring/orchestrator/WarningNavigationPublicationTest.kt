package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.testutil.CoordinatorTestHarness
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarningNavigationPublicationTest {

    @Test
    fun `production navigation receipt is emitted only for a published capturable Event`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.warningNavigationEvents.first()
            }
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()

            val payload = received.await()
            val event = requireNotNull(harness.eventSink.currentEvent)
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )

            assertEquals(event.id, payload.eventId)
            assertEquals(harness.sessionTracker.userResetEpoch, payload.expectedResetEpoch)
            assertEquals(SafeConfirmationSubject.Event(event.id), request.subject)
            assertEquals(payload.expectedResetEpoch, request.expectedResetEpoch)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `reset after sink mutation suppresses the stale navigation receipt`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val received = mutableListOf<WarningNavigationPayload>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.warningNavigationEvents.collect { received += it }
        }
        try {
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = {
                harness.sessionTracker.resetAfterUserConfirmedSafe()
            }

            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()

            assertTrue(received.isEmpty())
            assertNull(harness.eventSink.currentEvent)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `published Debug event uses the same typed navigation receipt`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val event = riskEvent("debug-event", RiskLevel.CRITICAL)
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.warningNavigationEvents.first()
            }

            coordinator.publishAndShowDebugOverlay(event)

            assertEquals(
                WarningNavigationPayload(
                    eventId = event.id,
                    expectedResetEpoch = harness.sessionTracker.userResetEpoch,
                ),
                received.await(),
            )
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `published sub-HIGH Debug event preserves the old no-navigation threshold`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val received = mutableListOf<WarningNavigationPayload>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.warningNavigationEvents.collect { received += it }
        }
        try {
            coordinator.publishAndShowDebugOverlay(riskEvent("medium-debug", RiskLevel.MEDIUM))
            runCurrent()

            assertTrue(received.isEmpty())
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `slow subscriber retains the newest publication instead of an obsolete buffered receipt`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val firstReceived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val received = mutableListOf<WarningNavigationPayload>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.warningNavigationEvents.take(2).collect { payload ->
                received += payload
                if (received.size == 1) {
                    firstReceived.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        try {
            coordinator.publishAndShowDebugOverlay(riskEvent("event-1", RiskLevel.CRITICAL))
            firstReceived.await()
            coordinator.publishAndShowDebugOverlay(riskEvent("event-2", RiskLevel.CRITICAL))
            coordinator.publishAndShowDebugOverlay(riskEvent("event-3", RiskLevel.CRITICAL))

            releaseFirst.complete(Unit)
            runCurrent()

            assertEquals(listOf("event-1", "event-3"), received.map { it.eventId })
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `publication receipts do not replay into a later Home recreation`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        try {
            coordinator.publishAndShowDebugOverlay(riskEvent("before-home", RiskLevel.CRITICAL))

            val replayed = withTimeoutOrNull(1L) {
                coordinator.warningNavigationEvents.first()
            }

            assertNull(replayed)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `newer publication suppresses stale notification and popup accounting from older tick`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val newer = riskEvent("newer-publication-wins", RiskLevel.CRITICAL)
        val olderPushed = CompletableDeferred<RiskEvent>()
        val releaseOlderPush = CompletableDeferred<Unit>()
        val notifiedIds = mutableListOf<String>()
        every { harness.notificationManager.notify(any()) } answers {
            notifiedIds += firstArg<RiskEvent>().id
        }
        harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
            if (event.id != newer.id && olderPushed.complete(event)) {
                releaseOlderPush.await()
            }
        }
        try {
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            val older = olderPushed.await()

            val newerJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.publishAndShowDebugOverlay(newer)
            }
            runCurrent()
            releaseOlderPush.complete(Unit)
            newerJob.join()
            runCurrent()

            val staleNotificationSent = older.id in notifiedIds
            val stalePopupMarked = RiskSignal.REMOTE_CONTROL_APP_OPENED in
                harness.sessionTracker.sessionState.value?.notifiedActiveThreats.orEmpty()
            assertEquals(
                "a superseded publication must commit neither notification nor popup metadata",
                listOf(false, false),
                listOf(staleNotificationSent, stalePopupMarked),
            )
        } finally {
            releaseOlderPush.complete(Unit)
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            coordinator.stop()
        }
    }

    @Test
    fun `newer publication after navigation gate suppresses stale notification commit`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val newer = riskEvent("newer-after-navigation-gate", RiskLevel.CRITICAL)
        val notificationReady = CompletableDeferred<RiskEvent>()
        val releaseNotification = CompletableDeferred<Unit>()
        val notifiedIds = mutableListOf<String>()
        every { harness.notificationManager.notify(any()) } answers {
            notifiedIds += firstArg<RiskEvent>().id
        }
        coordinator.beforePublicationNotificationCommit = { event ->
            if (RiskSignal.REMOTE_CONTROL_APP_OPENED in event.signals &&
                event.id != newer.id && notificationReady.complete(event)
            ) {
                releaseNotification.await()
            }
        }
        try {
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            val older = notificationReady.await()

            coordinator.publishAndShowDebugOverlay(newer)
            releaseNotification.complete(Unit)
            runCurrent()

            assertTrue(older.id !in notifiedIds)
        } finally {
            releaseNotification.complete(Unit)
            coordinator.beforePublicationNotificationCommit = {}
            coordinator.stop()
        }
    }

    @Test
    fun `newer publication after overlay gate suppresses stale popup accounting`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val newer = riskEvent("newer-after-overlay-gate", RiskLevel.CRITICAL)
        val accountingReady = CompletableDeferred<RiskEvent>()
        val releaseAccounting = CompletableDeferred<Unit>()
        coordinator.beforePublicationPopupAccountingCommit = { event ->
            if (event.id != newer.id && accountingReady.complete(event)) {
                releaseAccounting.await()
            }
        }
        try {
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            accountingReady.await()

            coordinator.publishAndShowDebugOverlay(newer)
            releaseAccounting.complete(Unit)
            runCurrent()

            assertTrue(
                RiskSignal.REMOTE_CONTROL_APP_OPENED !in
                    harness.sessionTracker.sessionState.value?.notifiedActiveThreats.orEmpty(),
            )
        } finally {
            releaseAccounting.complete(Unit)
            coordinator.beforePublicationPopupAccountingCommit = {}
            coordinator.stop()
        }
    }

    private fun riskEvent(id: String, level: RiskLevel): RiskEvent = RiskEvent(
        id = id,
        title = "test",
        description = "test",
        occurredAtMillis = 1L,
        level = level,
        signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
    )
}
