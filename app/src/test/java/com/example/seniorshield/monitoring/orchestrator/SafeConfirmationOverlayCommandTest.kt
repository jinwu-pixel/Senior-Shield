package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.event.RiskEventFactory
import com.example.seniorshield.testutil.CoordinatorTestHarness
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
class SafeConfirmationOverlayCommandTest {

    @Test
    fun `Coordinator show supplies exact Event identity and epoch binding`() = runTest {
        val fixture = startWithPublishedEvent(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        try {
            val subject = fixture.binding.subject as SafeConfirmationSubject.Event

            assertEquals(fixture.event.id, subject.eventId)
            assertEquals(fixture.event.id, fixture.binding.expectedEventId)
            assertEquals(fixture.epoch, fixture.binding.expectedResetEpoch)
            assertEquals(fixture.event.signals.toSet(), fixture.binding.expectedSignals)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `exact idle binding resets then mirrors clears event and cleans surfaces`() = runTest {
        val fixture = startWithPublishedEvent(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        try {
            fixture.harness.callMonitor.callId = null

            assertTrue(fixture.binding.confirm(liveCallId = null, signals = fixture.event.signals.toSet()))

            assertEquals(fixture.epoch + 1, fixture.harness.sessionTracker.userResetEpoch)
            assertEquals(
                listOf("mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
            assertFalse(fixture.harness.sessionTracker.isSnoozeActive())
            assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `live call positive allowlist resets snoozes then marks clears mirrors event and cleanup`() = runTest {
        val callId = 222L
        val fixture = startWithPublishedEvent(
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
            callId = callId,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)
            fixture.harness.callMonitor.onCurrentCallIdRead = { readIndex, _ ->
                if (readIndex == 2) {
                    assertEquals(fixture.epoch + 1, fixture.harness.sessionTracker.userResetEpoch)
                    assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
                }
            }

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))

            assertEquals(
                listOf("mark:$callId", "source", "mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
            assertEquals(listOf(callId), fixture.harness.callMonitor.markedSafeCallIds)
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `same Overlay one-shot resumes stored live plan after call becomes idle`() = runTest {
        val callId = 223L
        val signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)
        val fixture = startWithPublishedEvent(signals = signals, callId = callId)
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)
            fixture.harness.callMonitor.failClearTelebankingAnchor = true

            assertFalse(fixture.binding.confirm(callId, signals))
            val resetEpochAfterFailure = fixture.harness.sessionTracker.userResetEpoch
            assertNotNull(fixture.harness.eventSink.currentEvent)

            fixture.harness.callMonitor.failClearTelebankingAnchor = false
            fixture.harness.callMonitor.callId = null

            assertTrue(
                "the stable binding one-shot must resume even though click-time call state changed",
                fixture.binding.confirm(liveCallId = null, signals = signals),
            )
            assertEquals(resetEpochAfterFailure, fixture.harness.sessionTracker.userResetEpoch)
            assertEquals(1, fixture.harness.callMonitor.clearTelebankingAnchorCallCount)
            assertNull(fixture.harness.eventSink.currentEvent)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `Home can recover the stored Overlay retry after the original surface disappears`() = runTest {
        val callId = 224L
        val signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)
        val fixture = startWithPublishedEvent(signals = signals, callId = callId)
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)
            fixture.harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(fixture.binding.confirm(callId, signals))
            val resetEpochAfterFailure = fixture.harness.sessionTracker.userResetEpoch

            fixture.harness.callMonitor.failClearTelebankingAnchor = false
            fixture.harness.callMonitor.callId = null
            val recovered = requireNotNull(
                fixture.coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(SafeConfirmationOrigin.OVERLAY_LIVE_CALL, recovered.origin)
            assertTrue(fixture.coordinator.confirmSafe(recovered))
            assertEquals(resetEpochAfterFailure, fixture.harness.sessionTracker.userResetEpoch)
            assertNull(fixture.harness.eventSink.currentEvent)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `second current call recheck mismatch performs neither mark nor clear but completes dynamically denied lane`() = runTest {
        val callId = 333L
        val fixture = startWithPublishedEvent(
            signals = setOf(RiskSignal.UNKNOWN_CALLER),
            callId = callId,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, 999L)

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))

            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
            assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
            assertEquals(0, fixture.harness.callMonitor.clearTelebankingAnchorCallCount)
            assertEquals(
                listOf("mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `live call mismatch is rejected before reset and leaves overlay state untouched`() = runTest {
        val fixture = startWithPublishedEvent(
            signals = setOf(RiskSignal.UNKNOWN_CALLER),
            callId = 444L,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(999L)

            assertFalse(fixture.binding.confirm(444L, fixture.event.signals.toSet()))

            assertEquals(fixture.epoch, fixture.harness.sessionTracker.userResetEpoch)
            assertNotNull(fixture.harness.eventSink.currentEvent)
            assertTrue(fixture.harness.safeConfirmationOperations.isEmpty())
            assertFalse(fixture.harness.sessionTracker.isSnoozeActive())
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `app mixed telebanking and empty Event signals snooze but never mark or clear anchor`() = runTest {
        val deniedSignalSets = listOf(
            setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.REMOTE_CONTROL_APP_OPENED),
            setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.TELEBANKING_AFTER_SUSPICIOUS),
            emptySet(),
        )

        deniedSignalSets.forEachIndexed { index, signals ->
            val callId = 500L + index
            val fixture = startWithPublishedEvent(signals = signals, callId = callId)
            try {
                fixture.harness.callMonitor.scriptCurrentCallIds(callId)

                assertTrue("signals=$signals", fixture.binding.confirm(callId, signals))
                assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
                assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
                assertEquals(0, fixture.harness.callMonitor.clearTelebankingAnchorCallCount)
            } finally {
                fixture.coordinator.stop()
            }
        }
    }

    @Test
    fun `mixed live denial still mirrors clears event and cleans surfaces in exact order`() = runTest {
        val callId = 604L
        val signals = setOf(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
        )
        val fixture = startWithPublishedEvent(signals = signals, callId = callId)
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId)

            assertTrue(fixture.binding.confirm(callId, signals))

            assertEquals(fixture.epoch + 1, fixture.harness.sessionTracker.userResetEpoch)
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
            assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
            assertEquals(0, fixture.harness.callMonitor.clearTelebankingAnchorCallCount)
            assertEquals(
                listOf("mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `live confirmation snooze prevents same call no-upgrade respawn and overlay`() = runTest {
        val callId = 605L
        val signals = setOf(RiskSignal.UNKNOWN_CALLER)
        val fixture = startWithPublishedEvent(signals = signals, callId = callId)
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)
            verify(exactly = 1) { fixture.harness.overlayManager.show(any(), any(), any()) }

            assertTrue(fixture.binding.confirm(callId, signals))
            assertNull(fixture.harness.sessionTracker.sessionState.value)
            assertNull(fixture.harness.eventSink.currentEvent)
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))

            fixture.harness.callMonitor.callSignals.value = listOf(RiskSignal.UNKNOWN_CALLER)
            runCurrent()

            assertNull(fixture.harness.sessionTracker.sessionState.value)
            assertNull(fixture.harness.eventSink.currentEvent)
            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
            verify(exactly = 1) { fixture.harness.overlayManager.show(any(), any(), any()) }
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `wrong Event signals stale epoch and wrong Event identity are rejected`() = runTest {
        val fixture = startWithPublishedEvent(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        try {
            val originalSubject = fixture.binding.subject as SafeConfirmationSubject.Event

            assertFalse(fixture.binding.confirm(null, setOf(RiskSignal.SUSPICIOUS_APP_INSTALLED)))
            assertFalse(
                fixture.binding.copy(
                    subject = SafeConfirmationSubject.Event("wrong-${originalSubject.eventId}"),
                ).confirm(null, fixture.event.signals.toSet()),
            )
            assertFalse(
                fixture.binding.copy(expectedResetEpoch = fixture.epoch - 1)
                    .confirm(null, fixture.event.signals.toSet()),
            )

            assertEquals(fixture.epoch, fixture.harness.sessionTracker.userResetEpoch)
            assertNotNull(fixture.harness.eventSink.currentEvent)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `PENDING Event request is rejected and no overlay binding is issued`() = runTest {
        val harness = CoordinatorTestHarness()
        val event = riskEvent("pending-event", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val factory = mockFactory(event)
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            pushEntered.complete(Unit)
            releasePush.await()
        }
        harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val coordinator = with(harness) { start(eventFactoryOverride = factory) }
        try {
            pushEntered.await()
            val epoch = harness.sessionTracker.userResetEpoch
            val request = SafeConfirmationRequest(
                origin = SafeConfirmationOrigin.OVERLAY_IDLE,
                subject = SafeConfirmationSubject.Event(event.id),
                expectedResetEpoch = epoch,
                liveCallId = null,
                signals = event.signals.toSet(),
                expectedEventId = event.id,
            )

            assertFalse(coordinator.confirmSafe(request))
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(epoch, harness.sessionTracker.userResetEpoch)
        } finally {
            releasePush.complete(Unit)
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `qualified anchor clear never exposes null event with hot mirror`() = runTest {
        val callId = 777L
        val fixture = startWithPublishedEvent(
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
            callId = callId,
        )
        val observed = mutableListOf<Pair<Boolean, Boolean>>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(
                fixture.harness.eventSink.currentEventState,
                fixture.coordinator.anchorHotState,
            ) { event, hot -> (event != null) to hot }.collect { observed += it }
        }
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))
            runCurrent()

            assertFalse(observed.any { (hasEvent, hot) -> !hasEvent && hot })
        } finally {
            observer.cancel()
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `Debug binding uses nonblank token rejects stale token and epoch and consumes once`() = runTest {
        val harness = CoordinatorTestHarness()
        every { harness.overlayManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "overlay"
        }
        every { harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "cooldown"
        }
        val coordinator = with(harness) { start() }
        try {
            val firstEvent = riskEvent("debug-first", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
            coordinator.showDebugOverlay(firstEvent)
            val first = captureLastBinding(harness)
            val firstSubject = first.subject as SafeConfirmationSubject.Debug
            assertTrue(firstSubject.token.isNotBlank())
            assertEquals(harness.sessionTracker.userResetEpoch, first.expectedResetEpoch)
            assertTrue("show-only Debug issuer must not publish", harness.eventSink.pushed.isEmpty())
            assertNull("show-only Debug issuer must not mutate currentEvent", harness.eventSink.currentEvent)

            val secondEvent = riskEvent("debug-second", setOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
            coordinator.showDebugOverlay(secondEvent)
            val second = captureLastBinding(harness)
            assertFalse(first.confirm(null, firstEvent.signals.toSet()))
            assertFalse(
                second.copy(subject = SafeConfirmationSubject.Debug("stale-token"))
                    .confirm(null, secondEvent.signals.toSet()),
            )
            assertFalse(
                second.copy(expectedEventId = "wrong-${secondEvent.id}")
                    .confirm(null, secondEvent.signals.toSet()),
            )

            assertTrue(second.confirm(null, secondEvent.signals.toSet()))
            assertFalse(second.confirm(null, secondEvent.signals.toSet()))

            val staleEpochEvent = riskEvent("debug-third", emptySet())
            coordinator.showDebugOverlay(staleEpochEvent)
            val staleEpoch = captureLastBinding(harness)
            harness.sessionTracker.reset()
            assertFalse(staleEpoch.confirm(null, staleEpochEvent.signals.toSet()))
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `Debug live binding reuses positive call-safe snooze mark clear plan`() = runTest {
        val callId = 880L
        val fixture = startDebugFixture(
            riskEvent(
                "debug-live-call",
                setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
            ),
            callId,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))

            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
            assertEquals(
                listOf("mark:$callId", "source", "mirror", "event", "overlay", "cooldown"),
                fixture.harness.safeConfirmationOperations,
            )
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `Debug live denied signals still snooze without mark or anchor clear`() = runTest {
        val callId = 881L
        val fixture = startDebugFixture(
            riskEvent("debug-live-app", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)),
            callId,
        )
        try {
            fixture.harness.callMonitor.scriptCurrentCallIds(callId)

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))

            assertTrue(fixture.harness.sessionTracker.isSnoozedForCall(callId))
            assertTrue(fixture.harness.callMonitor.markedSafeCallIds.isEmpty())
            assertEquals(0, fixture.harness.callMonitor.clearTelebankingAnchorCallCount)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `Debug publish completes before binding and show are issued`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val event = riskEvent("debug-publish-boundary", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            pushEntered.complete(Unit)
            releasePush.await()
        }
        val publishing: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(event)
        }
        try {
            pushEntered.await()
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }

            releasePush.complete(Unit)
            publishing.join()

            verify(exactly = 1) { harness.overlayManager.show(event, null, any()) }
            assertTrue(captureLastBinding(harness).subject is SafeConfirmationSubject.Debug)
        } finally {
            releasePush.complete(Unit)
            publishing.cancel()
            coordinator.stop()
        }
    }

    @Test
    fun `reset during Debug publication clears late sink mutation and issues no binding`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val event = riskEvent("debug-reset-race", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            pushEntered.complete(Unit)
            releasePush.await()
        }
        val publishing: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(event)
        }
        try {
            pushEntered.await()
            harness.sessionTracker.resetAfterUserConfirmedSafe()
            releasePush.complete(Unit)
            publishing.join()

            assertNull(harness.eventSink.currentEvent)
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            releasePush.complete(Unit)
            publishing.cancel()
            coordinator.stop()
        }
    }

    @Test
    fun `production Event publication invalidates an older Debug token`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        try {
            val debugEvent = riskEvent("debug-before-production", emptySet())
            coordinator.showDebugOverlay(debugEvent)
            val debugBinding = captureLastBinding(harness)

            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()

            assertFalse(debugBinding.confirm(null, debugEvent.signals.toSet()))
            verify(exactly = 2) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `same-epoch production replacement before Debug binding prevents stale Debug show`() = runTest {
        val harness = CoordinatorTestHarness()
        val debugEvent = riskEvent("debug-replaced-before-binding", emptySet())
        val productionEvent = riskEvent(
            "production-after-debug-intent",
            setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        val coordinator = with(harness) { start(eventFactoryOverride = mockFactory(productionEvent)) }
        val debugPushEntered = CompletableDeferred<Unit>()
        val releaseDebugPush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
            if (event.id == debugEvent.id) {
                debugPushEntered.complete(Unit)
                releaseDebugPush.await()
            }
        }
        val publishing: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(debugEvent)
        }
        try {
            debugPushEntered.await()

            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            // The test scheduler drains production B through intent registration, where it
            // suspends behind Debug A's publication Mutex. Sink mutation remains serialized.
            runCurrent()
            assertEquals(debugEvent.id, harness.eventSink.currentEvent?.id)

            releaseDebugPush.complete(Unit)
            publishing.join()
            runCurrent()

            assertEquals(productionEvent.id, harness.eventSink.currentEvent?.id)
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            val surviving = captureLastBinding(harness)
            assertEquals(SafeConfirmationSubject.Event(productionEvent.id), surviving.subject)
        } finally {
            releaseDebugPush.complete(Unit)
            publishing.cancel()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            coordinator.stop()
        }
    }

    @Test
    fun `show-only Debug issuer fails closed while production Event is PENDING`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val productionPushEntered = CompletableDeferred<Unit>()
        val releaseProductionPush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            productionPushEntered.complete(Unit)
            releaseProductionPush.await()
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            productionPushEntered.await()

            coordinator.showDebugOverlay(riskEvent("debug-during-pending", emptySet()))
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }

            releaseProductionPush.complete(Unit)
            runCurrent()
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            assertTrue(captureLastBinding(harness).subject is SafeConfirmationSubject.Event)
        } finally {
            releaseProductionPush.complete(Unit)
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `pre-PENDING publisher cancellation restores an existing show-only Debug capability`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val debugEvent = riskEvent("debug-survives-cancelled-intent", emptySet())
        coordinator.showDebugOverlay(debugEvent)
        val debugBinding = captureLastBinding(harness)
        val mutex = DefaultRiskDetectionCoordinator::class.java
            .getDeclaredField("eventPublicationMutex")
            .apply { isAccessible = true }
            .get(coordinator) as Mutex
        mutex.lock()
        val publisher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(
                riskEvent("cancelled-before-pending", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)),
            )
        }
        try {
            runCurrent()
            val activeIntents = DefaultRiskDetectionCoordinator::class.java
                .getDeclaredField("activeEventPublicationIntents")
                .apply { isAccessible = true }
                .get(coordinator) as Map<*, *>
            assertEquals(1, activeIntents.size)
            assertFalse(debugBinding.confirm(liveCallId = null, signals = emptySet()))

            publisher.cancel()
            publisher.join()
            mutex.unlock()

            assertTrue(
                "an intent cancelled before PENDING must not destroy the existing Debug CTA",
                debugBinding.confirm(liveCallId = null, signals = emptySet()),
            )
        } finally {
            publisher.cancel()
            if (mutex.isLocked) mutex.unlock()
            coordinator.stop()
        }
    }

    @Test
    fun `show-only Debug binding and show are atomic against production publication`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val event = riskEvent("atomic-show-only-debug", emptySet())
        val lockStates = mutableListOf<Boolean>()
        every { harness.overlayManager.show(event, null, any()) } answers {
            lockStates += Thread.holdsLock(coordinator)
        }
        try {
            coordinator.showDebugOverlay(event)

            assertEquals(listOf(true), lockStates)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `show-only Debug cannot discard a fail-closed safe-confirm retry before replacement`() = runTest {
        val fixture = startWithPublishedEvent(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        try {
            val request = requireNotNull(
                fixture.coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            fixture.harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(fixture.coordinator.confirmSafe(request))
            val resetEpochAfterFailure = fixture.harness.sessionTracker.userResetEpoch

            val rejectedDebug = riskEvent("debug-must-not-replace-retry", emptySet())
            fixture.coordinator.showDebugOverlay(rejectedDebug)

            verify(exactly = 0) {
                fixture.harness.overlayManager.show(rejectedDebug, null, any())
            }
            val retry = requireNotNull(
                fixture.coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(request, retry)
            fixture.harness.callMonitor.failClearTelebankingAnchor = false
            assertTrue(fixture.coordinator.confirmSafe(retry))
            assertEquals(resetEpochAfterFailure, fixture.harness.sessionTracker.userResetEpoch)
        } finally {
            fixture.coordinator.stop()
        }
    }

    @Test
    fun `concurrent Debug publishers serialize sink mutation and finish with the newer binding`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val firstEvent = riskEvent("debug-publisher-a", setOf(RiskSignal.UNKNOWN_CALLER))
        val secondEvent = riskEvent("debug-publisher-b", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val firstBeforeMutation = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        harness.eventSink.beforeCurrentEventSet = { event ->
            if (event.id == firstEvent.id) {
                firstBeforeMutation.complete(Unit)
                releaseFirst.await()
            }
        }
        val firstJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(firstEvent)
        }
        firstBeforeMutation.await()
        val secondJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(secondEvent)
        }
        try {
            runCurrent()
            releaseFirst.complete(Unit)
            firstJob.join()
            secondJob.join()

            assertEquals(secondEvent.id, harness.eventSink.currentEvent?.id)
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            val finalBinding = captureLastBinding(harness)
            assertEquals(secondEvent.id, finalBinding.expectedEventId)
            assertTrue(finalBinding.subject is SafeConfirmationSubject.Debug)
        } finally {
            releaseFirst.complete(Unit)
            firstJob.cancel()
            secondJob.cancel()
            harness.eventSink.beforeCurrentEventSet = null
            coordinator.stop()
        }
    }

    @Test
    fun `cancelling queued newer publisher lets completed older publication recover`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val firstEvent = riskEvent("debug-publisher-survivor", setOf(RiskSignal.UNKNOWN_CALLER))
        val cancelledEvent = riskEvent("debug-publisher-cancelled", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val firstPushEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
            if (event.id == firstEvent.id) {
                firstPushEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        val firstJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(firstEvent)
        }
        firstPushEntered.await()
        val cancelledJob: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(cancelledEvent)
        }
        try {
            // The newer intent reaches the locked lane, then is cancelled before sink mutation.
            runCurrent()
            cancelledJob.cancel()
            cancelledJob.join()
            releaseFirst.complete(Unit)
            firstJob.join()

            assertEquals(listOf(firstEvent.id), harness.eventSink.pushed.map { it.id })
            assertEquals(firstEvent.id, harness.eventSink.currentEvent?.id)
            val recovered = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(SafeConfirmationSubject.Event(firstEvent.id), recovered.subject)
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            val surviving = captureLastBinding(harness)
            assertEquals(firstEvent.id, surviving.expectedEventId)
            assertTrue(surviving.subject is SafeConfirmationSubject.Debug)
        } finally {
            releaseFirst.complete(Unit)
            firstJob.cancel()
            cancelledJob.cancel()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            coordinator.stop()
        }
    }

    @Test
    fun `older registered publisher reaches PENDING before a newer pre-PENDING cancellation`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val older = riskEvent("older-registered-first", setOf(RiskSignal.UNKNOWN_CALLER))
        val newer = riskEvent("newer-cancelled-before-pending", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val firstSinkEntry = CompletableDeferred<RiskEvent>()
        val releaseFirstSinkEntry = CompletableDeferred<Unit>()
        harness.eventSink.beforeCurrentEventSet = { event ->
            if (firstSinkEntry.complete(event)) {
                releaseFirstSinkEntry.await()
            }
        }
        val mutex = DefaultRiskDetectionCoordinator::class.java
            .getDeclaredField("eventPublicationMutex")
            .apply { isAccessible = true }
            .get(coordinator) as Mutex
        mutex.lock()
        val olderJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(older)
        }
        val newerJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(newer)
        }
        try {
            runCurrent()
            val activeIntents = DefaultRiskDetectionCoordinator::class.java
                .getDeclaredField("activeEventPublicationIntents")
                .apply { isAccessible = true }
                .get(coordinator) as Map<*, *>
            assertEquals(2, activeIntents.size)
            mutex.unlock()

            val first = firstSinkEntry.await()
            assertEquals("a reversible newer registration must not drop older work", older.id, first.id)
            newerJob.cancel()
            newerJob.join()
            releaseFirstSinkEntry.complete(Unit)
            olderJob.join()

            assertEquals(older.id, harness.eventSink.currentEvent?.id)
            verify(exactly = 1) { harness.overlayManager.show(older, null, any()) }
        } finally {
            newerJob.cancel()
            olderJob.cancel()
            releaseFirstSinkEntry.complete(Unit)
            if (mutex.isLocked) mutex.unlock()
            harness.eventSink.beforeCurrentEventSet = null
            coordinator.stop()
        }
    }

    @Test
    fun `older published warning recovers navigation and overlay after queued newer intent cancels`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val firstEvent = riskEvent("published-before-cancelled-intent", setOf(RiskSignal.UNKNOWN_CALLER))
        val cancelledEvent = riskEvent("queued-intent-cancelled-late", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val firstPublished = CompletableDeferred<Unit>()
        val releaseFirstEffects = CompletableDeferred<Unit>()
        val navigation = mutableListOf<WarningNavigationPayload>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.warningNavigationEvents.collect { navigation += it }
        }
        coordinator.afterDebugPublicationBeforeEffects = { event ->
            if (event.id == firstEvent.id) {
                firstPublished.complete(Unit)
                releaseFirstEffects.await()
            }
        }
        val firstJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(firstEvent)
        }
        firstPublished.await()

        val mutex = DefaultRiskDetectionCoordinator::class.java
            .getDeclaredField("eventPublicationMutex")
            .apply { isAccessible = true }
            .get(coordinator) as Mutex
        mutex.lock()
        val cancelledJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(cancelledEvent)
        }
        try {
            runCurrent()
            val activeIntents = DefaultRiskDetectionCoordinator::class.java
                .getDeclaredField("activeEventPublicationIntents")
                .apply { isAccessible = true }
                .get(coordinator) as Map<*, *>
            assertEquals(1, activeIntents.size)

            releaseFirstEffects.complete(Unit)
            runCurrent()

            cancelledJob.cancel()
            cancelledJob.join()
            firstJob.join()

            assertEquals(listOf(firstEvent.id), navigation.map { it.eventId })
            verify(exactly = 1) { harness.overlayManager.show(firstEvent, null, any()) }
            assertEquals(firstEvent.id, captureLastBinding(harness).expectedEventId)
        } finally {
            releaseFirstEffects.complete(Unit)
            cancelledJob.cancel()
            firstJob.cancel()
            mutex.unlock()
            coordinator.afterDebugPublicationBeforeEffects = {}
            coordinator.stop()
        }
    }

    @Test
    fun `newer successful publisher prevents an older validated Debug overlay from showing late`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val older = riskEvent("validated-overlay-older", setOf(RiskSignal.UNKNOWN_CALLER))
        val newer = riskEvent("validated-overlay-newer", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val olderReachedEffect = CompletableDeferred<Unit>()
        val releaseOlderEffect = CompletableDeferred<Unit>()
        coordinator.beforeDebugOverlayEffect = { event ->
            if (event.id == older.id) {
                olderReachedEffect.complete(Unit)
                releaseOlderEffect.await()
            }
        }
        val olderJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(older)
        }
        olderReachedEffect.await()
        try {
            coordinator.publishAndShowDebugOverlay(newer)
            verify(exactly = 1) { harness.overlayManager.show(newer, null, any()) }

            releaseOlderEffect.complete(Unit)
            olderJob.join()

            verify(exactly = 0) { harness.overlayManager.show(older, null, any()) }
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            releaseOlderEffect.complete(Unit)
            olderJob.cancel()
            coordinator.beforeDebugOverlayEffect = {}
            coordinator.stop()
        }
    }

    @Test
    fun `newer PENDING publisher prevents an older validated Debug overlay from showing`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val older = riskEvent("validated-before-pending", setOf(RiskSignal.UNKNOWN_CALLER))
        val newer = riskEvent("pending-after-validation", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val olderReachedEffect = CompletableDeferred<Unit>()
        val releaseOlderEffect = CompletableDeferred<Unit>()
        val newerReachedPending = CompletableDeferred<Unit>()
        val holdNewerPending = CompletableDeferred<Unit>()
        coordinator.beforeDebugOverlayEffect = { event ->
            if (event.id == older.id) {
                olderReachedEffect.complete(Unit)
                releaseOlderEffect.await()
            }
        }
        harness.eventSink.afterCurrentEventSetBeforePushReturns = { event ->
            if (event.id == newer.id) {
                newerReachedPending.complete(Unit)
                holdNewerPending.await()
            }
        }
        val olderJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(older)
        }
        olderReachedEffect.await()
        val newerJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(newer)
        }
        newerReachedPending.await()
        try {
            releaseOlderEffect.complete(Unit)
            runCurrent()

            verify(exactly = 0) { harness.overlayManager.show(older, null, any()) }
            verify(exactly = 0) { harness.overlayManager.show(newer, null, any()) }
        } finally {
            newerJob.cancel()
            holdNewerPending.complete(Unit)
            releaseOlderEffect.complete(Unit)
            olderJob.cancel()
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            coordinator.beforeDebugOverlayEffect = {}
            coordinator.stop()
        }
    }

    @Test
    fun `post-guardian old reset cleanup preserves the exact fail-closed retry Event`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val guardianEntered = CompletableDeferred<Unit>()
        val releaseGuardian = CompletableDeferred<Unit>()
        harness.guardianRepository.beforeFirstEmission = {
            guardianEntered.complete(Unit)
            releaseGuardian.await()
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            guardianEntered.await()

            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            val eventId = (request.subject as SafeConfirmationSubject.Event).eventId
            harness.callMonitor.failClearTelebankingAnchor = true
            assertFalse(coordinator.confirmSafe(request))
            assertEquals(eventId, harness.eventSink.currentEvent?.id)

            releaseGuardian.complete(Unit)
            runCurrent()

            assertEquals(
                "the suspended publication cleanup must not erase the Event retained for retry",
                eventId,
                harness.eventSink.currentEvent?.id,
            )
        } finally {
            releaseGuardian.complete(Unit)
            harness.callMonitor.failClearTelebankingAnchor = false
            harness.guardianRepository.beforeFirstEmission = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `post-guardian cleanup preserves production Event during failed Debug confirmation`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val guardianEntered = CompletableDeferred<Unit>()
        val releaseGuardian = CompletableDeferred<Unit>()
        harness.guardianRepository.beforeFirstEmission = {
            guardianEntered.complete(Unit)
            releaseGuardian.await()
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            guardianEntered.await()
            val productionEventId = requireNotNull(harness.eventSink.currentEvent).id

            val debugEvent = riskEvent("debug-fails-over-production-event", emptySet())
            coordinator.showDebugOverlay(debugEvent)
            val debugBinding = captureLastBinding(harness)
            harness.callMonitor.failAnchorRead = true
            assertFalse(debugBinding.confirm(liveCallId = null, signals = emptySet()))

            releaseGuardian.complete(Unit)
            runCurrent()

            assertEquals(productionEventId, harness.eventSink.currentEvent?.id)
        } finally {
            releaseGuardian.complete(Unit)
            harness.callMonitor.failAnchorRead = false
            harness.guardianRepository.beforeFirstEmission = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `old post-guardian reset cleanup cannot clear a newer Debug Event`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val guardianEntered = CompletableDeferred<Unit>()
        val releaseGuardian = CompletableDeferred<Unit>()
        harness.guardianRepository.beforeFirstEmission = {
            guardianEntered.complete(Unit)
            releaseGuardian.await()
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            guardianEntered.await()

            harness.sessionTracker.resetAfterUserConfirmedSafe()
            val newer = riskEvent("debug-after-reset", setOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
            coordinator.publishAndShowDebugOverlay(newer)
            assertEquals(newer.id, harness.eventSink.currentEvent?.id)

            releaseGuardian.complete(Unit)
            runCurrent()

            assertEquals(newer.id, harness.eventSink.currentEvent?.id)
            val binding = captureLastBinding(harness)
            assertEquals(newer.id, binding.expectedEventId)
        } finally {
            releaseGuardian.complete(Unit)
            harness.guardianRepository.beforeFirstEmission = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `published Debug binding issuer rejects reset between publication and issuance`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        try {
            val event = riskEvent("debug-issued-before-reset", emptySet())
            coordinator.publishAndShowDebugOverlay(event)
            val published = captureLastBinding(harness)
            val oldEpoch = published.expectedResetEpoch
            val eventContextField = DefaultRiskDetectionCoordinator::class.java
                .getDeclaredField("currentSafeConfirmationEvent")
                .apply { isAccessible = true }
            val eventContext = requireNotNull(eventContextField.get(coordinator))
            val publicationSequence = eventContext.javaClass
                .getDeclaredField("publicationIntentSequence")
                .apply { isAccessible = true }
                .getLong(eventContext)
            harness.sessionTracker.resetAfterUserConfirmedSafe()

            val issueMethod = DefaultRiskDetectionCoordinator::class.java.declaredMethods
                .single { it.name == "issueDebugOverlayBinding" }
                .apply { isAccessible = true }
            val reissued = issueMethod.invoke(coordinator, event, oldEpoch, publicationSequence)

            assertNull(reissued)
        } finally {
            coordinator.stop()
        }
    }

    @Test
    fun `old epoch PENDING publication does not block show-only Debug preview`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val productionPushEntered = CompletableDeferred<Unit>()
        val releaseProductionPush = CompletableDeferred<Unit>()
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            productionPushEntered.complete(Unit)
            releaseProductionPush.await()
        }
        val debugEvent = riskEvent("debug-after-pending-reset", emptySet())
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            productionPushEntered.await()

            harness.sessionTracker.reset()
            harness.eventSink.clearAll()
            coordinator.showDebugOverlay(debugEvent)

            verify(exactly = 1) { harness.overlayManager.show(debugEvent, null, any()) }
            val binding = captureLastBinding(harness)
            assertTrue(binding.subject is SafeConfirmationSubject.Debug)

            releaseProductionPush.complete(Unit)
            runCurrent()

            assertTrue(binding.confirm(null, debugEvent.signals.toSet()))
        } finally {
            releaseProductionPush.complete(Unit)
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `late old epoch intent cannot erase a fresh show-only Debug binding`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val oldEpochCaptured = CompletableDeferred<Unit>()
        val releaseOldPublisher = CompletableDeferred<Unit>()
        coordinator.afterDebugEpochCapturedBeforePublish = {
            oldEpochCaptured.complete(Unit)
            releaseOldPublisher.await()
        }
        val oldEvent = riskEvent("old-debug-publisher", setOf(RiskSignal.UNKNOWN_CALLER))
        val oldJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.publishAndShowDebugOverlay(oldEvent)
        }
        try {
            oldEpochCaptured.await()

            harness.sessionTracker.reset()
            harness.eventSink.clearAll()
            val freshEvent = riskEvent("fresh-show-only-debug", emptySet())
            coordinator.showDebugOverlay(freshEvent)
            val freshBinding = captureLastBinding(harness)
            assertTrue(freshBinding.subject is SafeConfirmationSubject.Debug)

            releaseOldPublisher.complete(Unit)
            oldJob.join()

            assertTrue(freshBinding.confirm(null, freshEvent.signals.toSet()))
        } finally {
            releaseOldPublisher.complete(Unit)
            oldJob.cancel()
            coordinator.afterDebugEpochCapturedBeforePublish = {}
            coordinator.stop()
        }
    }

    @Test
    fun `show-only Debug after reset survives old post-guardian Event cleanup`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val guardianEntered = CompletableDeferred<Unit>()
        val releaseGuardian = CompletableDeferred<Unit>()
        harness.guardianRepository.beforeFirstEmission = {
            guardianEntered.complete(Unit)
            releaseGuardian.await()
        }
        val debugEvent = riskEvent(
            "debug-preview-after-guardian-reset",
            setOf(RiskSignal.SUSPICIOUS_APP_INSTALLED),
        )
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            guardianEntered.await()

            harness.sessionTracker.reset()
            harness.eventSink.clearAll()
            coordinator.showDebugOverlay(debugEvent)
            val binding = captureLastBinding(harness)
            assertTrue(binding.subject is SafeConfirmationSubject.Debug)

            releaseGuardian.complete(Unit)
            runCurrent()

            assertTrue(binding.confirm(null, debugEvent.signals.toSet()))
        } finally {
            releaseGuardian.complete(Unit)
            harness.guardianRepository.beforeFirstEmission = null
            runCurrent()
            coordinator.stop()
        }
    }

    @Test
    fun `ordinary push failure keeps coordinator alive for a later threat`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        var failFirstPush = true
        harness.eventSink.afterCurrentEventSetBeforePushReturns = {
            if (failFirstPush) {
                failFirstPush = false
                error("injected ordinary push failure")
            }
        }
        try {
            harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            runCurrent()
            assertEquals(1, harness.eventSink.pushed.size)

            harness.appUsageMonitor.appSignals.value = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            )
            runCurrent()

            assertEquals(2, harness.eventSink.pushed.size)
            val currentEvent = requireNotNull(harness.eventSink.currentEvent)
            val request = requireNotNull(
                coordinator.captureSafeConfirmationRequest(SafeConfirmationOrigin.HOME),
            )
            assertEquals(SafeConfirmationSubject.Event(currentEvent.id), request.subject)
        } finally {
            harness.eventSink.afterCurrentEventSetBeforePushReturns = null
            coordinator.stop()
        }
    }

    @Test
    fun `publication cancellation is propagated instead of isolated as an ordinary failure`() = runTest {
        val harness = CoordinatorTestHarness()
        val coordinator = with(harness) { start() }
        val event = riskEvent("debug-cancel-propagates", emptySet())
        harness.eventSink.cancelNextPushBeforeCurrentEventSet = true
        var propagated = false
        try {
            coordinator.publishAndShowDebugOverlay(event)
        } catch (_: CancellationException) {
            propagated = true
        } finally {
            coordinator.stop()
        }

        assertTrue(propagated)
        assertNull(harness.eventSink.currentEvent)
        verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
    }

    @Test
    fun `safe confirmation invalidates presentation generations after reset and dismisses prior epoch last`() = runTest {
        val callId = 991L
        val fixture = startWithPublishedEvent(
            signals = setOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION),
            callId = callId,
        )
        val expectedEpoch = fixture.epoch + 1
        try {
            every { fixture.harness.overlayManager.invalidateBeforeEpoch(any()) } answers {
                fixture.harness.safeConfirmationOperations += "overlay-invalidate:${firstArg<Long>()}"
            }
            every { fixture.harness.cooldownManager.invalidateBeforeEpoch(any()) } answers {
                fixture.harness.safeConfirmationOperations += "cooldown-invalidate:${firstArg<Long>()}"
            }
            every { fixture.harness.overlayManager.dismissBeforeEpoch(any()) } answers {
                fixture.harness.safeConfirmationOperations += "overlay-dismiss:${firstArg<Long>()}"
            }
            every { fixture.harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
                fixture.harness.safeConfirmationOperations += "cooldown-dismiss:${firstArg<Long>()}"
            }
            fixture.harness.callMonitor.scriptCurrentCallIds(callId, callId)

            assertTrue(fixture.binding.confirm(callId, fixture.event.signals.toSet()))

            assertEquals(
                listOf(
                    "overlay-invalidate:$expectedEpoch",
                    "cooldown-invalidate:$expectedEpoch",
                    "mark:$callId",
                    "source",
                    "mirror",
                    "event",
                    "overlay-dismiss:$expectedEpoch",
                    "cooldown-dismiss:$expectedEpoch",
                ),
                fixture.harness.safeConfirmationOperations,
            )
        } finally {
            fixture.coordinator.stop()
        }
    }

    private fun TestScope.startWithPublishedEvent(
        signals: Set<RiskSignal>,
        callId: Long? = null,
    ): Fixture {
        val harness = CoordinatorTestHarness()
        val event = riskEvent("event-${signals.hashCode()}-${callId ?: "idle"}", signals)
        val factory = mockFactory(event)
        every { harness.overlayManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "overlay"
        }
        every { harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "cooldown"
        }
        harness.callMonitor.anchorHot = true
        harness.callMonitor.callId = callId
        harness.appUsageMonitor.appSignals.value = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val coordinator = with(harness) { start(eventFactoryOverride = factory) }
        val binding = captureLastBinding(harness)
        assertEquals(event.id, harness.eventSink.currentEvent?.id)
        assertTrue(coordinator.anchorHotState.value)
        harness.safeConfirmationOperations.clear()
        harness.callMonitor.logAnchorReads = true
        return Fixture(
            harness = harness,
            coordinator = coordinator,
            event = event,
            binding = binding,
            epoch = harness.sessionTracker.userResetEpoch,
        )
    }

    private fun captureLastBinding(harness: CoordinatorTestHarness): SafeConfirmationOverlayBinding {
        val captured = mutableListOf<SafeConfirmationOverlayBinding>()
        verify(atLeast = 1) {
            harness.overlayManager.show(any(), any(), capture(captured))
        }
        return captured.last()
    }

    private fun TestScope.startDebugFixture(event: RiskEvent, callId: Long): Fixture {
        val harness = CoordinatorTestHarness()
        every { harness.overlayManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "overlay"
        }
        every { harness.cooldownManager.dismissBeforeEpoch(any()) } answers {
            harness.safeConfirmationOperations += "cooldown"
        }
        harness.callMonitor.callId = callId
        harness.callMonitor.anchorHot = true
        val coordinator = with(harness) { start() }
        coordinator.showDebugOverlay(event)
        val binding = captureLastBinding(harness)
        harness.safeConfirmationOperations.clear()
        harness.callMonitor.logAnchorReads = true
        return Fixture(
            harness = harness,
            coordinator = coordinator,
            event = event,
            binding = binding,
            epoch = harness.sessionTracker.userResetEpoch,
        )
    }

    private fun mockFactory(event: RiskEvent): RiskEventFactory =
        mockk<RiskEventFactory>().also { factory ->
            every { factory.create(any(), any()) } returns event
        }

    private fun riskEvent(id: String, signals: Set<RiskSignal>): RiskEvent = RiskEvent(
        id = id,
        title = "test",
        description = "test",
        occurredAtMillis = 1L,
        level = RiskLevel.CRITICAL,
        signals = signals.toList(),
    )

    private data class Fixture(
        val harness: CoordinatorTestHarness,
        val coordinator: DefaultRiskDetectionCoordinator,
        val event: RiskEvent,
        val binding: SafeConfirmationOverlayBinding,
        val epoch: Long,
    )
}
