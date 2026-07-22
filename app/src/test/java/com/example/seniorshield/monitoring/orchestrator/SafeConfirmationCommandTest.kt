package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.testutil.CoordinatorTestHarness
import io.mockk.every
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafeConfirmationCommandTest {

    private val harness = CoordinatorTestHarness()

    @Test
    fun `HOME executes reset source mirror event clear and cleanup in exact order`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val epochBefore = harness.sessionTracker.userResetEpoch
            var resetObservedBeforeSource = false
            harness.callMonitor.onClearTelebankingAnchor = {
                resetObservedBeforeSource =
                    harness.sessionTracker.userResetEpoch == epochBefore + 1 &&
                        harness.sessionTracker.sessionState.value == null
            }

            assertTrue(coordinator.confirmSafe(request))

            assertTrue(resetObservedBeforeSource)
            assertEquals(
                listOf("source", "mirror", "event", "overlay", "cooldown"),
                harness.safeConfirmationOperations,
            )
            assertNull(harness.eventSink.currentEvent)
            assertFalse(coordinator.anchorHotState.value)
        }
    }

    @Test
    fun `WARNING executes reset source mirror event clear and cleanup in exact order`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )

            assertTrue(coordinator.confirmSafe(request))

            assertEquals(
                listOf("source", "mirror", "event", "overlay", "cooldown"),
                harness.safeConfirmationOperations,
            )
        }
    }

    @Test
    fun `current Event and eventless current Session subjects are accepted`() = runTest {
        withStopped(startWithInterruptEvent()) { eventCoordinator ->
            val eventRequest = requireNotNull(
                eventCoordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertTrue(eventRequest.subject is SafeConfirmationSubject.Event)
            assertTrue(eventCoordinator.confirmSafe(eventRequest))
        }

        val sessionHarness = CoordinatorTestHarness()
        val sessionCoordinator = with(sessionHarness) { start() }
        try {
            sessionHarness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            assertNull(sessionHarness.eventSink.currentEvent)

            val sessionRequest = requireNotNull(
                sessionCoordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )
            assertTrue(sessionRequest.subject is SafeConfirmationSubject.Session)
            assertTrue(sessionCoordinator.confirmSafe(sessionRequest))
        } finally {
            sessionCoordinator.stop()
        }
    }

    @Test
    fun `stale Event Session and epoch are rejected without mutation`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val current = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val epochBefore = harness.sessionTracker.userResetEpoch

            assertFalse(
                coordinator.confirmSafe(
                    current.copy(subject = SafeConfirmationSubject.Event("stale-event")),
                ),
            )
            assertFalse(
                coordinator.confirmSafe(
                    current.copy(subject = SafeConfirmationSubject.Session("stale-session")),
                ),
            )
            assertFalse(
                coordinator.confirmSafe(current.copy(expectedResetEpoch = epochBefore - 1)),
            )

            assertEquals(epochBefore, harness.sessionTracker.userResetEpoch)
            assertNotNull(harness.eventSink.currentEvent)
            assertTrue(harness.safeConfirmationOperations.isEmpty())
        }
    }

    @Test
    fun `same subject and epoch double consume advances reset once`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val epochBefore = harness.sessionTracker.userResetEpoch
            val eventClearsBefore = harness.eventSink.clearCurrentCount

            assertTrue(coordinator.confirmSafe(request))
            assertFalse(coordinator.confirmSafe(request))

            assertEquals(epochBefore + 1, harness.sessionTracker.userResetEpoch)
            assertEquals(1, harness.callMonitor.clearTelebankingAnchorCallCount)
            assertEquals(eventClearsBefore + 1, harness.eventSink.clearCurrentCount)
        }
    }

    @Test
    fun `simultaneous double tap consumes the same subject and epoch exactly once`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val readyCount = AtomicInteger(0)
            val bothReady = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val taps = List(2) {
                async(Dispatchers.Default) {
                    if (readyCount.incrementAndGet() == 2) bothReady.complete(Unit)
                    release.await()
                    coordinator.confirmSafe(request)
                }
            }

            bothReady.await()
            release.complete(Unit)

            assertEquals(listOf(false, true), taps.awaitAll().sorted())
            assertEquals(1, harness.callMonitor.clearTelebankingAnchorCallCount)
        }
    }

    @Test
    fun `old request cannot clear a newer event while its publication is suspended`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val oldEventId = (oldRequest.subject as SafeConfirmationSubject.Event).eventId
            val secondPushEntered = CompletableDeferred<Unit>()
            val releaseSecondPush = CompletableDeferred<Unit>()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
                if (event.id != oldEventId) {
                    secondPushEntered.complete(Unit)
                    releaseSecondPush.await()
                }
            }

            try {
                harness.appUsageMonitor.appSignals.value = listOf(
                    RiskSignal.REMOTE_CONTROL_APP_OPENED,
                    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                )
                secondPushEntered.await()
                val newerEventId = requireNotNull(harness.eventSink.currentEvent).id
                assertTrue(newerEventId != oldEventId)

                assertFalse(coordinator.confirmSafe(oldRequest))
                assertEquals(newerEventId, harness.eventSink.currentEvent?.id)

                releaseSecondPush.complete(Unit)
                runCurrent()

                val newerRequest = requireNotNull(
                    coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
                )
                assertEquals(SafeConfirmationSubject.Event(newerEventId), newerRequest.subject)
                assertTrue(coordinator.confirmSafe(newerRequest))
            } finally {
                releaseSecondPush.complete(Unit)
                harness.eventSink.afterCurrentEventSetBeforePushReturns = null
                runCurrent()
            }
        }
    }

    @Test
    fun `stale legacy Event context falls back to a current eventless Session`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldEvent = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val oldEpoch = oldEvent.expectedResetEpoch

            harness.sessionTracker.resetAfterUserConfirmedSafe()
            harness.eventSink.clearCurrentRiskEvent()
            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()

            val sessionRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertTrue(sessionRequest.subject is SafeConfirmationSubject.Session)
            assertTrue(sessionRequest.expectedResetEpoch > oldEpoch)
            assertNull(harness.eventSink.currentEvent)
            assertTrue(coordinator.confirmSafe(sessionRequest))
        }
    }

    @Test
    fun `stale pending Event context does not hide a newer current Session`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldEventId = (
                requireNotNull(
                    coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
                ).subject as SafeConfirmationSubject.Event
            ).eventId
            val secondPushEntered = CompletableDeferred<Unit>()
            val releaseSecondPush = CompletableDeferred<Unit>()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
                if (event.id != oldEventId) {
                    secondPushEntered.complete(Unit)
                    releaseSecondPush.await()
                }
            }

            try {
                harness.appUsageMonitor.appSignals.value = listOf(
                    RiskSignal.REMOTE_CONTROL_APP_OPENED,
                    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                )
                secondPushEntered.await()

                harness.sessionTracker.resetAfterUserConfirmedSafe()
                val newSession = requireNotNull(
                    harness.sessionTracker.update(
                        callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
                        appSignals = emptyList(),
                    ),
                )

                val request = requireNotNull(
                    coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
                )
                assertEquals(SafeConfirmationSubject.Session(newSession.id), request.subject)
            } finally {
                releaseSecondPush.complete(Unit)
                harness.eventSink.afterCurrentEventSetBeforePushReturns = null
                runCurrent()
            }
        }
    }

    @Test
    fun `post-mutation push cancellation never re-exposes prior Event provenance`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val oldEventId = (oldRequest.subject as SafeConfirmationSubject.Event).eventId
            val secondPushEntered = CompletableDeferred<Unit>()
            val releaseSecondPush = CompletableDeferred<Unit>()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
                if (event.id != oldEventId) {
                    secondPushEntered.complete(Unit)
                    releaseSecondPush.await()
                    throw CancellationException("injected cancellation after sink mutation")
                }
            }

            try {
                harness.appUsageMonitor.appSignals.value = listOf(
                    RiskSignal.REMOTE_CONTROL_APP_OPENED,
                    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                )
                secondPushEntered.await()
                val newerEventId = requireNotNull(harness.eventSink.currentEvent).id
                assertTrue(newerEventId != oldEventId)

                releaseSecondPush.complete(Unit)
                runCurrent()

                assertFalse(coordinator.confirmSafe(oldRequest))
                assertEquals(newerEventId, harness.eventSink.currentEvent?.id)
                assertNull(
                    coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
                )
            } finally {
                releaseSecondPush.complete(Unit)
                harness.eventSink.afterCurrentEventSetBeforePushReturns = null
                runCurrent()
            }
        }
    }

    @Test
    fun `pre-mutation push cancellation fails closed when mutation boundary is unknowable`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val oldEventId = (oldRequest.subject as SafeConfirmationSubject.Event).eventId
            assertEquals(oldEventId, harness.eventSink.currentEvent?.id)
            harness.eventSink.cancelNextPushBeforeCurrentEventSet = true

            harness.appUsageMonitor.appSignals.value = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            )
            runCurrent()

            assertEquals(oldEventId, harness.eventSink.currentEvent?.id)
            assertFalse(coordinator.confirmSafe(oldRequest))
            assertEquals(oldEventId, harness.eventSink.currentEvent?.id)
            assertNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
        }
    }

    @Test
    fun `consumed confirmation keys are pruned after reset epoch advances`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val firstRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )

            assertTrue(coordinator.confirmSafe(firstRequest))
            assertEquals(1, consumedConfirmationKeyCount(coordinator))

            val nextSession = requireNotNull(
                harness.sessionTracker.update(
                    callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
                    appSignals = emptyList(),
                ),
            )
            val nextRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )

            assertEquals(SafeConfirmationSubject.Session(nextSession.id), nextRequest.subject)
            assertEquals(0, consumedConfirmationKeyCount(coordinator))
            assertTrue(coordinator.confirmSafe(nextRequest))
            assertEquals(1, consumedConfirmationKeyCount(coordinator))

            harness.sessionTracker.update(
                callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
                appSignals = emptyList(),
            )
            assertNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(0, consumedConfirmationKeyCount(coordinator))
        }
    }

    @Test
    fun `event and mirror observer never sees null event with hot mirror`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val observed = mutableListOf<Pair<Boolean, Boolean>>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                combine(harness.eventSink.currentEventState, coordinator.anchorHotState) { event, hot ->
                    (event != null) to hot
                }.collect { observed += it }
            }
            try {
                assertTrue(coordinator.confirmSafe(request))
                runCurrent()

                assertFalse(observed.any { (hasEvent, hot) -> !hasEvent && hot })
            } finally {
                observer.cancel()
            }
        }
    }

    @Test
    fun `source failure retains event and prevents cleanup and success`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val expectedSafeEpoch = harness.sessionTracker.userResetEpoch + 1
            harness.callMonitor.failClearTelebankingAnchor = true

            assertFalse(coordinator.confirmSafe(request))

            verify(exactly = 1) { harness.overlayManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            verify(exactly = 1) { harness.cooldownManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            assertNotNull(harness.eventSink.currentEvent)
            assertFalse("event clear must not run", "event" in harness.safeConfirmationOperations)
            assertFalse("overlay cleanup must not run", "overlay" in harness.safeConfirmationOperations)
            assertFalse("cooldown cleanup must not run", "cooldown" in harness.safeConfirmationOperations)

            // A failed command has already reset the session. A later neutral source emission must
            // not reinterpret that null session as ordinary expiry and erase the fail-closed warning.
            harness.appUsageMonitor.appSignals.value = emptyList()
            runCurrent()

            assertNotNull("neutral source emission must retain the fail-closed event", harness.eventSink.currentEvent)
            assertFalse("generic inactive cleanup must remain blocked", "event" in harness.safeConfirmationOperations)

            // Retry resumes the same logical one-shot after the failed phase. It must not reset twice.
            val resetEpochAfterFailure = harness.sessionTracker.userResetEpoch
            val retry = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(request, retry)
            harness.callMonitor.failClearTelebankingAnchor = false

            assertTrue(coordinator.confirmSafe(retry))
            assertEquals(resetEpochAfterFailure, harness.sessionTracker.userResetEpoch)
            assertNull(harness.eventSink.currentEvent)
        }
    }

    @Test
    fun `inactive cleanup rechecks a concurrently installed fail-closed retry before clearing`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val eventId = (request.subject as SafeConfirmationSubject.Event).eventId
            val cleanupCheckedRetry = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            coordinator.beforeInactiveSessionCleanupCommit = {
                cleanupCheckedRetry.complete(Unit)
                releaseCleanup.await()
            }
            val cleanup = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.runInactiveSessionCleanupForTest()
            }
            try {
                cleanupCheckedRetry.await()
                harness.callMonitor.failClearTelebankingAnchor = true
                assertFalse(coordinator.confirmSafe(request))

                releaseCleanup.complete(Unit)
                cleanup.join()

                assertEquals(eventId, harness.eventSink.currentEvent?.id)
            } finally {
                releaseCleanup.complete(Unit)
                cleanup.cancel()
                harness.callMonitor.failClearTelebankingAnchor = false
                coordinator.beforeInactiveSessionCleanupCommit = {}
            }
        }
    }

    @Test
    fun `inactive cleanup preserves a newer Event published while cleanup is suspended`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val cleanupReady = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val newer = RiskEvent(
                id = "newer-during-inactive-cleanup",
                title = "test",
                description = "test",
                occurredAtMillis = 2L,
                level = RiskLevel.CRITICAL,
                signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            )
            coordinator.beforeInactiveSessionCleanupCommit = {
                cleanupReady.complete(Unit)
                releaseCleanup.await()
            }
            val cleanup = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.runInactiveSessionCleanupForTest()
            }
            try {
                cleanupReady.await()
                coordinator.publishAndShowDebugOverlay(newer)
                assertEquals(newer.id, harness.eventSink.currentEvent?.id)

                releaseCleanup.complete(Unit)
                cleanup.join()

                assertEquals(newer.id, harness.eventSink.currentEvent?.id)
            } finally {
                releaseCleanup.complete(Unit)
                cleanup.cancel()
                coordinator.beforeInactiveSessionCleanupCommit = {}
            }
        }
    }

    @Test
    fun `inactive cleanup preserves a same-sequence Event that becomes published after cutoff`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val pendingPush = CompletableDeferred<Unit>()
            val releasePush = CompletableDeferred<Unit>()
            val cleanupReady = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val publicationCompleted = CompletableDeferred<Unit>()
            val holdDebugEffects = CompletableDeferred<Unit>()
            val newer = RiskEvent(
                id = "same-sequence-pending-to-published",
                title = "test",
                description = "test",
                occurredAtMillis = 3L,
                level = RiskLevel.CRITICAL,
                signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            )
            harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
                if (event.id == newer.id) {
                    pendingPush.complete(Unit)
                    releasePush.await()
                }
            }
            coordinator.beforeInactiveSessionCleanupCommit = {
                cleanupReady.complete(Unit)
                releaseCleanup.await()
            }
            coordinator.afterDebugPublicationBeforeEffects = { event ->
                if (event.id == newer.id) {
                    publicationCompleted.complete(Unit)
                    holdDebugEffects.await()
                }
            }
            val publisher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.publishAndShowDebugOverlay(newer)
            }
            pendingPush.await()
            val cleanup = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.runInactiveSessionCleanupForTest()
            }
            try {
                cleanupReady.await()
                releasePush.complete(Unit)
                publicationCompleted.await()
                publisher.cancel()
                publisher.join()
                assertEquals(newer.id, harness.eventSink.currentEvent?.id)

                releaseCleanup.complete(Unit)
                cleanup.join()

                assertEquals(newer.id, harness.eventSink.currentEvent?.id)
            } finally {
                releasePush.complete(Unit)
                releaseCleanup.complete(Unit)
                holdDebugEffects.complete(Unit)
                publisher.cancel()
                cleanup.cancel()
                harness.eventSink.afterCurrentEventSetBeforePushReturns = null
                coordinator.beforeInactiveSessionCleanupCommit = {}
                coordinator.afterDebugPublicationBeforeEffects = {}
            }
        }
    }

    @Test
    fun `fresh post-reset Session supersedes an older failed confirmation retry`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(coordinator.confirmSafe(oldRequest))
            harness.callMonitor.failClearTelebankingAnchor = false

            harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()
            val freshSession = requireNotNull(harness.sessionTracker.sessionState.value)

            assertFalse("the old retry must not consume a fresh Session", coordinator.confirmSafe(oldRequest))
            val freshRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(SafeConfirmationSubject.Session(freshSession.id), freshRequest.subject)
        }
    }

    @Test
    fun `active pre-PENDING publisher blocks retry and cancellation restores it`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(coordinator.confirmSafe(oldRequest))
            harness.callMonitor.failClearTelebankingAnchor = false
            val resetEpochAfterFailure = harness.sessionTracker.userResetEpoch

            val mutex = DefaultRiskDetectionCoordinator::class.java
                .getDeclaredField("eventPublicationMutex")
                .apply { isAccessible = true }
                .get(coordinator) as kotlinx.coroutines.sync.Mutex
            mutex.lock()
            val publisher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.publishAndShowDebugOverlay(
                    RiskEvent(
                        id = "registered-before-pending",
                        title = "test",
                        description = "test",
                        occurredAtMillis = 1L,
                        level = RiskLevel.CRITICAL,
                        signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
                    ),
                )
            }
            try {
                runCurrent()
                val activeIntents = DefaultRiskDetectionCoordinator::class.java
                    .getDeclaredField("activeEventPublicationIntents")
                    .apply { isAccessible = true }
                    .get(coordinator) as Map<*, *>
                assertEquals(1, activeIntents.size)
                assertNull(coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME))
                assertFalse(coordinator.confirmSafe(oldRequest))

                publisher.cancel()
                publisher.join()
                mutex.unlock()

                val retry = requireNotNull(
                    coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
                )
                assertEquals(oldRequest, retry)
                assertTrue(coordinator.confirmSafe(retry))
                assertEquals(resetEpochAfterFailure, harness.sessionTracker.userResetEpoch)
            } finally {
                publisher.cancel()
                if (mutex.isLocked) mutex.unlock()
            }
        }
    }

    @Test
    fun `fresh PENDING publication supersedes an older failed confirmation retry`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val oldRequest = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val oldEventId = (oldRequest.subject as SafeConfirmationSubject.Event).eventId
            harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(coordinator.confirmSafe(oldRequest))

            val freshEvent = RiskEvent(
                id = "fresh-pending-after-failed-safe-confirm",
                title = "test",
                description = "test",
                occurredAtMillis = 1L,
                level = RiskLevel.CRITICAL,
                signals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            )
            harness.eventSink.cancelNextPushBeforeCurrentEventSet = true
            val cancelledPublisher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.publishAndShowDebugOverlay(freshEvent)
            }
            cancelledPublisher.join()
            assertTrue(cancelledPublisher.isCancelled)

            harness.callMonitor.failClearTelebankingAnchor = false
            assertFalse("old retry must not clear fresh PENDING provenance", coordinator.confirmSafe(oldRequest))
            assertEquals(oldEventId, harness.eventSink.currentEvent?.id)
        }
    }

    @Test
    fun `mirror failure retains event and prevents cleanup and success`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )
            val expectedSafeEpoch = harness.sessionTracker.userResetEpoch + 1
            harness.callMonitor.failAnchorRead = true

            assertFalse(coordinator.confirmSafe(request))

            verify(exactly = 1) { harness.overlayManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            verify(exactly = 1) { harness.cooldownManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            assertFalse(harness.callMonitor.anchorHot)
            assertNotNull(harness.eventSink.currentEvent)
            assertFalse("event clear must not run", "event" in harness.safeConfirmationOperations)
            assertFalse("overlay cleanup must not run", "overlay" in harness.safeConfirmationOperations)
            assertFalse("cooldown cleanup must not run", "cooldown" in harness.safeConfirmationOperations)
        }
    }

    @Test
    fun `event clear failure keeps cleared source and mirror without cleanup or success`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )
            val expectedSafeEpoch = harness.sessionTracker.userResetEpoch + 1
            harness.eventSink.failClearCurrentRiskEvent = true

            assertFalse(coordinator.confirmSafe(request))

            verify(exactly = 1) { harness.overlayManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            verify(exactly = 1) { harness.cooldownManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            assertFalse(harness.callMonitor.anchorHot)
            assertFalse(coordinator.anchorHotState.value)
            assertNotNull(harness.eventSink.currentEvent)
            assertFalse("overlay cleanup must not run", "overlay" in harness.safeConfirmationOperations)
            assertFalse("cooldown cleanup must not run", "cooldown" in harness.safeConfirmationOperations)
        }
    }

    @Test
    fun `dismiss failure keeps completed state effects and never rolls generation barrier back`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.WARNING),
            )
            val expectedSafeEpoch = harness.sessionTracker.userResetEpoch + 1
            every { harness.overlayManager.dismissBeforeEpoch(expectedSafeEpoch) } answers {
                harness.safeConfirmationOperations += "overlay"
                error("injected overlay dismiss failure")
            }

            assertFalse(coordinator.confirmSafe(request))

            verify(exactly = 1) { harness.overlayManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            verify(exactly = 1) { harness.cooldownManager.invalidateBeforeEpoch(expectedSafeEpoch) }
            verify(exactly = 0) { harness.cooldownManager.dismissBeforeEpoch(any()) }
            assertFalse(harness.callMonitor.anchorHot)
            assertFalse(coordinator.anchorHotState.value)
            assertNull(harness.eventSink.currentEvent)
            assertEquals(
                listOf("source", "mirror", "event", "overlay"),
                harness.safeConfirmationOperations,
            )
        }
    }

    @Test
    fun `captured Event keeps publication epoch while later capture ignores stale context`() = runTest {
        withStopped(startWithInterruptEvent()) { coordinator ->
            val published = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val publicationEpoch = published.expectedResetEpoch

            harness.sessionTracker.resetAfterUserConfirmedSafe()

            assertEquals(publicationEpoch, published.expectedResetEpoch)
            assertTrue(harness.sessionTracker.userResetEpoch > published.expectedResetEpoch)
            assertFalse(coordinator.confirmSafe(published))
            assertNull(coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME))
            assertNotNull(harness.eventSink.currentEvent)
        }
    }

    @Test
    fun `request model defines Event Session and Debug subjects without nullable sentinel`() {
        val request = SafeConfirmationRequest(
            origin = SafeConfirmationOrigin.DEBUG,
            subject = SafeConfirmationSubject.Debug("debug-token"),
            expectedResetEpoch = 7L,
            liveCallId = null,
            signals = emptySet(),
        )

        assertEquals(SafeConfirmationSubject.Debug("debug-token"), request.subject)
    }

    private fun TestScope.startWithInterruptEvent(): DefaultRiskDetectionCoordinator {
        every { harness.overlayManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "overlay"
        }
        every { harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "cooldown"
        }
        harness.callMonitor.anchorHot = true
        val coordinator = with(harness) { start() }
        var setupCompleted = false
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()
            assertNotNull(harness.eventSink.currentEvent)
            assertTrue(coordinator.anchorHotState.value)
            harness.safeConfirmationOperations.clear()
            harness.callMonitor.logAnchorReads = true
            setupCompleted = true
            return coordinator
        } finally {
            if (!setupCompleted) coordinator.stop()
        }
    }

    private inline fun <T> withStopped(
        coordinator: DefaultRiskDetectionCoordinator,
        block: (DefaultRiskDetectionCoordinator) -> T,
    ): T = try {
        block(coordinator)
    } finally {
        coordinator.stop()
    }

    private fun consumedConfirmationKeyCount(
        coordinator: DefaultRiskDetectionCoordinator,
    ): Int {
        val field = DefaultRiskDetectionCoordinator::class.java
            .getDeclaredField("consumedSafeConfirmations")
            .apply { isAccessible = true }
        return (field.get(coordinator) as Set<*>).size
    }
}
