package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.testutil.CoordinatorTestHarness
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
class PopupGuardianSmsToggleTest {
    private val guardian = Guardian(
        id = "guardian-1",
        name = "Test Guardian",
        phoneNumber = "01000000000",
    )

    @Test
    fun escalationSmsMenuOffWithGuardianShowsNullGuardian() = runTest {
        assertPopupGuardian(
            smsMenuEnabled = false,
            guardians = listOf(guardian),
            newTrigger = false,
            expectedGuardian = null,
        )
    }

    @Test
    fun escalationSmsMenuOnWithGuardianShowsGuardian() = runTest {
        assertPopupGuardian(
            smsMenuEnabled = true,
            guardians = listOf(guardian),
            newTrigger = false,
            expectedGuardian = guardian,
        )
    }

    @Test
    fun escalationSmsMenuOnWithoutGuardianShowsNullGuardian() = runTest {
        assertPopupGuardian(
            smsMenuEnabled = true,
            guardians = emptyList(),
            newTrigger = false,
            expectedGuardian = null,
        )
    }

    @Test
    fun newTriggerSmsMenuOffWithGuardianShowsNullGuardian() = runTest {
        assertPopupGuardian(
            smsMenuEnabled = false,
            guardians = listOf(guardian),
            newTrigger = true,
            expectedGuardian = null,
        )
    }

    @Test
    fun newTriggerSmsMenuOnWithGuardianShowsGuardian() = runTest {
        assertPopupGuardian(
            smsMenuEnabled = true,
            guardians = listOf(guardian),
            newTrigger = true,
            expectedGuardian = guardian,
        )
    }

    @Test
    fun escalationSettingFailureHidesGuardianAndNextTickShowsGuardian() = runTest {
        assertSettingFailure(newTrigger = false, emptyFlow = false)
    }

    @Test
    fun newTriggerSettingFailureHidesGuardianAndNextTickShowsGuardian() = runTest {
        assertSettingFailure(newTrigger = true, emptyFlow = false)
    }

    @Test
    fun escalationEmptySettingFlowShowsNullGuardian() = runTest {
        assertSettingFailure(newTrigger = false, emptyFlow = true)
    }

    @Test
    fun newTriggerEmptySettingFlowShowsNullGuardian() = runTest {
        assertSettingFailure(newTrigger = true, emptyFlow = true)
    }

    @Test
    fun escalationStopDuringSettingReadAbortsPopupAndTickAssignment() = runTest {
        assertStopDuringSettingRead(newTrigger = false)
    }

    @Test
    fun newTriggerStopDuringSettingReadAbortsPopupAndTickAssignment() = runTest {
        assertStopDuringSettingRead(newTrigger = true)
    }

    @Test
    fun escalationStopThenSettingIOExceptionAbortsPopupAndTickAssignment() = runTest {
        assertStopDuringSettingRead(newTrigger = false, stopThenIOException = true)
    }

    @Test
    fun newTriggerStopThenSettingIOExceptionAbortsPopupAndTickAssignment() = runTest {
        assertStopDuringSettingRead(newTrigger = true, stopThenIOException = true)
    }

    @Test
    fun escalationResetDuringSettingReadAbortsAtPopupRevalidation() = runTest {
        assertResetDuringSettingRead(newTrigger = false)
    }

    @Test
    fun newTriggerResetDuringSettingReadAbortsAtPopupRevalidation() = runTest {
        assertResetDuringSettingRead(newTrigger = true)
    }

    @Test
    fun escalationGuardianReadFailureHidesGuardianAndNextTickShowsGuardian() = runTest {
        assertGuardianReadFailure(newTrigger = false, emptyFlow = false)
    }

    @Test
    fun newTriggerGuardianReadFailureHidesGuardianAndNextTickShowsGuardian() = runTest {
        assertGuardianReadFailure(newTrigger = true, emptyFlow = false)
    }

    @Test
    fun escalationEmptyGuardianFlowShowsNullGuardian() = runTest {
        assertGuardianReadFailure(newTrigger = false, emptyFlow = true)
    }

    @Test
    fun newTriggerEmptyGuardianFlowShowsNullGuardian() = runTest {
        assertGuardianReadFailure(newTrigger = true, emptyFlow = true)
    }

    @Test
    fun escalationStopThenGuardianIOExceptionAbortsPopupAndTickAssignment() = runTest {
        assertStopThenGuardianIOException(newTrigger = false)
    }

    @Test
    fun newTriggerStopThenGuardianIOExceptionAbortsPopupAndTickAssignment() = runTest {
        assertStopThenGuardianIOException(newTrigger = true)
    }

    private fun TestScope.assertGuardianReadFailure(newTrigger: Boolean, emptyFlow: Boolean) {
        val harness = CoordinatorTestHarness()
        harness.settingsRepository.smsMenuEnabled = true
        harness.guardianRepository.guardians = listOf(guardian)
        harness.guardianRepository.emptyGuardianFlow = emptyFlow
        if (!emptyFlow) {
            harness.guardianRepository.guardianFailure = java.io.IOException("guardian datastore read failed")
        }
        var settingReads = 0
        var guardianReads = 0
        harness.settingsRepository.beforeSmsMenuEmission = { settingReads += 1 }
        harness.guardianRepository.beforeFirstEmission = { guardianReads += 1 }
        val shownGuardians = mutableListOf<Guardian?>()
        every { harness.overlayManager.show(any(), any(), any()) } answers {
            shownGuardians.add(secondArg<Guardian?>())
            Unit
        }
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        try {
            prepareRemoteTrigger(harness, newTrigger)
            runCurrent()

            assertEquals(1, settingReads)
            assertEquals(1, guardianReads)
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(listOf<Guardian?>(null), shownGuardians)
            assertEquals(1, harness.eventSink.pushed.size)

            harness.guardianRepository.guardianFailure = null
            harness.guardianRepository.emptyGuardianFlow = false
            // A new upgrade trigger escapes S2 without advancing maintenance time.
            harness.appUsageMonitor.appSignals.value = listOf(
                RiskSignal.REMOTE_CONTROL_APP_OPENED,
                RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            )
            runCurrent()

            assertEquals(2, settingReads)
            assertEquals(2, guardianReads)
            verify(exactly = 2) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(listOf(null, guardian), shownGuardians)
            assertEquals(2, harness.eventSink.pushed.size)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private suspend fun TestScope.assertStopThenGuardianIOException(newTrigger: Boolean) {
        val harness = CoordinatorTestHarness()
        val clock = FakeClock(now = 1_000_000L)
        harness.sessionTracker.clock = clock.provider
        harness.settingsRepository.smsMenuEnabled = true
        harness.guardianRepository.guardians = listOf(guardian)
        val entered = CompletableDeferred<Job>()
        val hookExited = CompletableDeferred<Unit>()
        val releaseGuardian = CompletableDeferred<Unit>()
        lateinit var coordinator: DefaultRiskDetectionCoordinator
        var guardianReads = 0
        harness.guardianRepository.beforeFirstEmission = {
            guardianReads += 1
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            try {
                // Wait only to install the accounting probe and observe the pre-stop state.
                releaseGuardian.await()
                // No suspension between cancellation and the non-cancellation exception.
                coordinator.stop()
                throw java.io.IOException("guardian read failed after synchronous stop")
            } finally {
                hookExited.complete(Unit)
            }
        }
        // Suspend the initial signal tick while its effective banking value differs
        // from previousBankingForeground; swallowing cancellation would assign true.
        harness.appUsageMonitor.bankingForeground.value = true
        prepareRemoteTrigger(harness, newTrigger)
        coordinator = with(harness) { start(clock) }
        var popupAccountingCalls = 0
        coordinator.beforePublicationPopupAccountingCommit = { popupAccountingCalls += 1 }
        try {
            assertTrue("guardian read must suspend before stop", entered.isCompleted)
            val tickJob = entered.await()
            assertFalse(previousBanking(coordinator))
            assertEquals(1, guardianReads)
            assertEquals(1, harness.eventSink.pushed.size)
            verify(exactly = 1) { harness.notificationManager.notify(any()) }

            releaseGuardian.complete(Unit)
            runCurrent()
            tickJob.join()

            assertTrue(hookExited.isCompleted)
            assertTrue(tickJob.isCancelled)
            assertTrue(tickJob.isCompleted)
            assertFalse(previousBanking(coordinator))
            assertEquals(1, guardianReads)
            assertEquals(0, popupAccountingCalls)
            assertEquals(1, harness.eventSink.pushed.size)
            assertTrue(requireNotNull(harness.sessionTracker.sessionState.value).notifiedActiveThreats.isEmpty())
            verify(exactly = 1) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { harness.cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private fun TestScope.assertSettingFailure(newTrigger: Boolean, emptyFlow: Boolean) {
        val harness = CoordinatorTestHarness()
        harness.guardianRepository.guardians = listOf(guardian)
        harness.settingsRepository.emptySmsMenuFlow = emptyFlow
        if (!emptyFlow) {
            harness.settingsRepository.smsMenuFailure = java.io.IOException("settings datastore read failed")
        }
        var settingReads = 0
        var guardianReads = 0
        harness.settingsRepository.beforeSmsMenuEmission = { settingReads += 1 }
        harness.guardianRepository.beforeFirstEmission = { guardianReads += 1 }
        val shownGuardians = mutableListOf<Guardian?>()
        every { harness.overlayManager.show(any(), any(), any()) } answers {
            shownGuardians.add(secondArg<Guardian?>())
            Unit
        }
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        try {
            prepareRemoteTrigger(harness, newTrigger)
            runCurrent()

            assertEquals(1, settingReads)
            assertEquals(0, guardianReads)
            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(listOf<Guardian?>(null), shownGuardians)

            if (!emptyFlow) {
                harness.settingsRepository.smsMenuFailure = null
                harness.settingsRepository.smsMenuEnabled = true
                // A new upgrade trigger escapes S2 without advancing maintenance time.
                harness.appUsageMonitor.appSignals.value = listOf(
                    RiskSignal.REMOTE_CONTROL_APP_OPENED,
                    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
                )
                runCurrent()

                assertEquals(2, settingReads)
                assertEquals(1, guardianReads)
                verify(exactly = 2) { harness.overlayManager.show(any(), any(), any()) }
                assertEquals(listOf(null, guardian), shownGuardians)
                assertEquals(2, harness.eventSink.pushed.size)
            }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private suspend fun TestScope.assertStopDuringSettingRead(
        newTrigger: Boolean,
        stopThenIOException: Boolean = false,
    ) {
        val harness = CoordinatorTestHarness()
        val clock = FakeClock(now = 1_000_000L)
        harness.sessionTracker.clock = clock.provider
        harness.guardianRepository.guardians = listOf(guardian)
        val entered = CompletableDeferred<Job>()
        val hookExited = CompletableDeferred<Unit>()
        val releaseSetting = CompletableDeferred<Unit>()
        lateinit var coordinator: DefaultRiskDetectionCoordinator
        harness.settingsRepository.beforeSmsMenuEmission = {
            entered.complete(requireNotNull(currentCoroutineContext()[Job]))
            try {
                if (stopThenIOException) {
                    // Wait only to install the accounting probe and observe the pre-stop state.
                    releaseSetting.await()
                    // No suspension between cancellation and the non-cancellation exception.
                    coordinator.stop()
                    throw java.io.IOException("settings read failed after synchronous stop")
                }
                awaitCancellation()
            } finally {
                hookExited.complete(Unit)
            }
        }
        var guardianReads = 0
        harness.guardianRepository.beforeFirstEmission = { guardianReads += 1 }
        // Suspend the initial signal tick while its effective banking value differs
        // from previousBankingForeground; swallowing cancellation would assign true.
        harness.appUsageMonitor.bankingForeground.value = true
        prepareRemoteTrigger(harness, newTrigger)
        coordinator = with(harness) { start(clock) }
        var popupAccountingCalls = 0
        coordinator.beforePublicationPopupAccountingCommit = { popupAccountingCalls += 1 }
        try {
            assertTrue("setting read must suspend before stop", entered.isCompleted)
            val tickJob = entered.await()
            assertFalse(previousBanking(coordinator))
            assertEquals(1, harness.eventSink.pushed.size)
            verify(exactly = 1) { harness.notificationManager.notify(any()) }

            if (stopThenIOException) {
                releaseSetting.complete(Unit)
            } else {
                coordinator.stop()
            }
            runCurrent()
            tickJob.join()

            assertTrue(hookExited.isCompleted)
            assertTrue(tickJob.isCancelled)
            assertTrue(tickJob.isCompleted)
            assertFalse(previousBanking(coordinator))
            assertEquals(0, guardianReads)
            assertEquals(0, popupAccountingCalls)
            assertEquals(1, harness.eventSink.pushed.size)
            assertTrue(requireNotNull(harness.sessionTracker.sessionState.value).notifiedActiveThreats.isEmpty())
            verify(exactly = 1) { harness.notificationManager.notify(any()) }
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
            verify(exactly = 0) { harness.cooldownManager.triggerIfNotActive(any(), any(), any(), any()) }
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }

    private fun TestScope.assertResetDuringSettingRead(newTrigger: Boolean) {
        val harness = CoordinatorTestHarness()
        harness.guardianRepository.guardians = listOf(guardian)
        val entered = CompletableDeferred<Unit>()
        val releaseSetting = CompletableDeferred<Unit>()
        var settingResumed = false
        harness.settingsRepository.beforeSmsMenuEmission = {
            entered.complete(Unit)
            releaseSetting.await()
            settingResumed = true
        }
        var guardianReads = 0
        harness.guardianRepository.beforeFirstEmission = { guardianReads += 1 }
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        var popupAccountingCalls = 0
        coordinator.beforePublicationPopupAccountingCommit = { popupAccountingCalls += 1 }
        try {
            prepareRemoteTrigger(harness, newTrigger)
            runCurrent()
            assertTrue("setting read must suspend before reset", entered.isCompleted)
            assertFalse(settingResumed)
            assertEquals(0, guardianReads)
            assertEquals(1, harness.eventSink.pushed.size)
            assertEquals(harness.eventSink.pushed.single(), harness.eventSink.currentEvent)

            val epochBefore = harness.sessionTracker.userResetEpoch
            // Reuse the direct tracker reset from the existing boundary tests.
            // Leave the published Event for the post-read popup revalidation to clear.
            harness.sessionTracker.resetAfterUserConfirmedSafe()
            assertEquals(epochBefore + 1, harness.sessionTracker.userResetEpoch)
            releaseSetting.complete(Unit)
            runCurrent()

            assertTrue("the setting read must resume before asserting suppression", settingResumed)
            assertEquals(1, guardianReads)
            assertNull(harness.sessionTracker.sessionState.value)
            assertNull(harness.eventSink.currentEvent)
            assertEquals(0, popupAccountingCalls)
            verify(exactly = 0) { harness.overlayManager.show(any(), any(), any()) }
        } finally {
            coordinator.stop()
            releaseSetting.complete(Unit)
            runCurrent()
        }
    }

    private fun prepareRemoteTrigger(harness: CoordinatorTestHarness, newTrigger: Boolean) {
        val remote = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        if (newTrigger) {
            val session = requireNotNull(harness.sessionTracker.update(emptyList(), remote))
            harness.sessionTracker.markAlertStateNotified(harness.alertStateResolver.resolve(session))
            harness.sessionTracker.markNotified(
                harness.evaluator.evaluate(session.accumulatedSignals.toList()).level,
            )
            assertTrue(session.notifiedActiveThreats.isEmpty())
        }
        harness.appUsageMonitor.appSignals.value = remote
    }

    private fun previousBanking(coordinator: DefaultRiskDetectionCoordinator): Boolean =
        DefaultRiskDetectionCoordinator::class.java.getDeclaredField("previousBankingForeground")
            .apply { isAccessible = true }
            .getBoolean(coordinator)

    private fun TestScope.assertPopupGuardian(
        smsMenuEnabled: Boolean,
        guardians: List<Guardian>,
        newTrigger: Boolean,
        expectedGuardian: Guardian?,
    ) {
        val harness = CoordinatorTestHarness()
        harness.settingsRepository.smsMenuEnabled = smsMenuEnabled
        harness.guardianRepository.guardians = guardians
        var shownGuardian: Guardian? = null
        every { harness.overlayManager.show(any(), any(), any()) } answers {
            shownGuardian = secondArg<Guardian?>()
        }
        val coordinator = with(harness) { start(FakeClock(now = 1_000_000L)) }
        try {
            val remote = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
            if (newTrigger) {
                val session = requireNotNull(harness.sessionTracker.update(emptyList(), remote))
                // Suppress only escalation, leaving the remote trigger unnotified.
                harness.sessionTracker.markAlertStateNotified(harness.alertStateResolver.resolve(session))
                harness.sessionTracker.markNotified(
                    harness.evaluator.evaluate(session.accumulatedSignals.toList()).level,
                )
                assertTrue(session.notifiedActiveThreats.isEmpty())
            }

            harness.appUsageMonitor.appSignals.value = remote
            runCurrent()

            verify(exactly = 1) { harness.overlayManager.show(any(), any(), any()) }
            assertEquals(expectedGuardian, shownGuardian)
        } finally {
            coordinator.stop()
            runCurrent()
        }
    }
}
