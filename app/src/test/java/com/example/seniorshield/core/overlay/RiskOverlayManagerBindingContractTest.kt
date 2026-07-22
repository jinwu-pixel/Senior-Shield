package com.example.seniorshield.core.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskOverlayManagerBindingContractTest {

    @Test
    fun `Overlay contains no direct safe-confirm state mutations`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )
        val forbiddenCalls = listOf(
            "sessionTracker.resetAfterUserConfirmedSafe(",
            "eventSink.clearCurrentRiskEvent(",
            "sessionTracker.snoozeForCall(",
            "callRiskMonitor.clearTelebankingAnchor(",
            "callRiskMonitor.markCurrentCallConfirmedSafe(",
        )

        forbiddenCalls.forEach { call ->
            assertFalse("Overlay must delegate instead of calling $call", source.contains(call))
        }
    }

    @Test
    fun `show requires a non-null Coordinator-issued binding with no default route`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )

        assertTrue(source.contains("safeConfirmation: SafeConfirmationOverlayBinding"))
        assertFalse(source.contains("safeConfirmation: SafeConfirmationOverlayBinding?"))
        assertFalse(source.contains("safeConfirmation: SafeConfirmationOverlayBinding ="))
        val showStart = source.indexOf("fun show(")
        val dismissStart = source.indexOf("fun dismiss()", startIndex = showStart)
        val showBody = source.substring(showStart, dismissStart)
        val identityGuard = showBody.indexOf("event.id != safeConfirmation.expectedEventId")
        val guardedPost = showBody.indexOf("postIfCurrent(presentationToken)")
        assertTrue(identityGuard >= 0)
        assertTrue("identity mismatch must fail before guarded Handler post", identityGuard < guardedPost)
    }

    @Test
    fun `Overlay show binds business epoch and local generation before posting`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )
        val showBody = source.substring(
            source.indexOf("fun show("),
            source.indexOf("fun dismiss()"),
        )

        assertTrue(source.contains("private val resetEpochProvider: ResetEpochProvider"))
        assertTrue(showBody.contains("safeConfirmation.expectedResetEpoch != resetEpochProvider.userResetEpoch"))
        assertTrue(showBody.contains("presentationGeneration.reserve("))
        assertTrue(showBody.contains("safeConfirmation.expectedResetEpoch"))
        assertTrue(showBody.contains("postIfCurrent(presentationToken)"))
        assertFalse(showBody.contains("mainHandler.post {"))
    }

    @Test
    fun `Cooldown production trigger requires an epoch and queues through the generation gate`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/BankingCooldownManager.kt",
        )
        val publicTrigger = source.substring(
            source.indexOf("fun triggerIfNotActive("),
            source.indexOf("private fun trigger("),
        )
        val privateTrigger = source.substring(
            source.indexOf("private fun trigger("),
            source.indexOf("private fun startCooldown("),
        )

        assertTrue(source.contains("private val resetEpochProvider: ResetEpochProvider"))
        assertTrue(publicTrigger.contains("expectedResetEpoch: Long,"))
        assertFalse(publicTrigger.contains("expectedResetEpoch: Long ="))
        assertTrue(privateTrigger.contains("expectedResetEpoch != resetEpochProvider.userResetEpoch"))
        assertTrue(privateTrigger.contains("presentationGeneration.reserve(expectedResetEpoch)"))
        assertTrue(privateTrigger.contains("postIfCurrent(token)"))
        assertTrue(source.contains("expectedResetEpoch = resetEpochProvider.userResetEpoch"))
    }

    @Test
    fun `Coordinator passes the tick epoch to both production cooldown triggers`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/monitoring/orchestrator/DefaultRiskDetectionCoordinator.kt",
        )

        assertTrue(source.countOccurrences("expectedResetEpoch = epochAtTickStart") == 2)
    }

    @Test
    fun `delayed presentation work is token targeted and pending cooldown dismiss has no view guard`() {
        val overlaySource = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )
        val cooldownSource = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/BankingCooldownManager.kt",
        )
        val dismissIfShowing = cooldownSource.substring(
            cooldownSource.indexOf("fun dismissIfShowing()"),
            cooldownSource.indexOf("internal fun invalidateBeforeEpoch"),
        )

        assertTrue(overlaySource.contains("{ dismiss(presentationToken) }"))
        assertTrue(cooldownSource.contains("{ dismiss(presentationToken) }"))
        assertTrue(cooldownSource.contains("postIfCurrent(presentationToken)"))
        assertFalse(overlaySource.contains("postDelayed({ dismiss()"))
        assertFalse(cooldownSource.contains("postDelayed({ dismiss()"))
        assertFalse(cooldownSource.contains("mainHandler.post { startCooldown("))
        assertTrue(dismissIfShowing.contains("presentationGeneration.invalidateThroughLatest("))
        assertFalse(dismissIfShowing.contains("if (!isShowing())"))
    }

    @Test
    fun `safe cleanup runs inline on main before route postprocess and posts only off main`() {
        val mainOperations = mutableListOf<String>()
        val mainQueue = mutableListOf<Runnable>()
        dispatchSafeCleanup(
            isMainThread = true,
            post = { mainQueue += it },
        ) { mainOperations += "cleanup" }
        mainOperations += "route-postprocess"

        assertEquals(listOf("cleanup", "route-postprocess"), mainOperations)
        assertTrue(mainQueue.isEmpty())

        val backgroundOperations = mutableListOf<String>()
        val backgroundQueue = mutableListOf<Runnable>()
        dispatchSafeCleanup(
            isMainThread = false,
            post = { backgroundQueue += it },
        ) { backgroundOperations += "cleanup" }
        backgroundOperations += "return"

        assertEquals(listOf("return"), backgroundOperations)
        assertEquals(1, backgroundQueue.size)
        backgroundQueue.single().run()
        assertEquals(listOf("return", "cleanup"), backgroundOperations)
    }

    @Test
    fun `both safe dismiss routes use main-inline cleanup dispatch`() {
        val overlaySource = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )
        val cooldownSource = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/BankingCooldownManager.kt",
        )
        val overlayDismiss = overlaySource.substring(
            overlaySource.indexOf("internal fun dismissBeforeEpoch"),
            overlaySource.indexOf("private fun dismiss(", overlaySource.indexOf("internal fun dismissBeforeEpoch")),
        )
        val cooldownDismiss = cooldownSource.substring(
            cooldownSource.indexOf("internal fun dismissBeforeEpoch"),
            cooldownSource.indexOf("fun triggerPreview", cooldownSource.indexOf("internal fun dismissBeforeEpoch")),
        )

        assertTrue(overlayDismiss.contains("dispatchSafeCleanup("))
        assertFalse(overlayDismiss.contains("mainHandler.post {"))
        assertTrue(cooldownDismiss.contains("dispatchSafeCleanup("))
        assertFalse(cooldownDismiss.contains("mainHandler.post {"))
    }

    @Test
    fun `rejected callback path does not dismiss the current Overlay`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/core/overlay/RiskOverlayManager.kt",
        )
        val callbackStart = source.indexOf("val confirmed = safeConfirmation.confirm(")
        val focusListener = source.indexOf("setOnFocusChangeListener", startIndex = callbackStart)
        val callbackBody = source.substring(callbackStart, focusListener)

        assertTrue(callbackBody.contains("if (confirmed)"))
        assertFalse("callback false must keep the Overlay visible", callbackBody.contains("dismiss()"))
    }

    @Test
    fun `DebugViewModel has no direct Overlay dependency or show route`() {
        val source = readSource(
            "app/src/main/java/com/example/seniorshield/feature/settings/DebugViewModel.kt",
        )

        assertFalse(source.contains("RiskOverlayManager"))
        assertFalse(source.contains("overlayManager.show("))
        assertTrue(source.contains("coordinator.showDebugOverlay("))
        assertTrue(source.contains("coordinator.publishAndShowDebugOverlay("))
    }

    private fun readSource(relativePath: String): String {
        var root = File(".").canonicalFile
        while (!File(root, "settings.gradle.kts").exists()) {
            root = root.parentFile ?: error("project root not found")
        }
        return File(root, relativePath).readText(Charsets.UTF_8)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, step = 1).count { it == needle }
}
