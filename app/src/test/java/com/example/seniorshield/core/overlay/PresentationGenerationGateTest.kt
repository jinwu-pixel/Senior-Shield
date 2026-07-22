package com.example.seniorshield.core.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationGenerationGateTest {

    @Test
    fun `queued overlay and cooldown starts from before reset never execute`() {
        var liveEpoch = 0L
        val overlayGate = PresentationGenerationGate()
        val cooldownGate = PresentationGenerationGate()
        val overlayToken = requireNotNull(overlayGate.reserve(liveEpoch))
        val cooldownToken = requireNotNull(cooldownGate.reserve(liveEpoch))
        var overlayAdds = 0
        var cooldownAdds = 0
        val queuedTasks = listOf<() -> Unit>(
            { overlayGate.runIfCurrent(overlayToken, liveEpoch) { overlayAdds += 1 } },
            { cooldownGate.runIfCurrent(cooldownToken, liveEpoch) { cooldownAdds += 1 } },
        )

        liveEpoch += 1
        overlayGate.invalidateBeforeEpoch(liveEpoch)
        cooldownGate.invalidateBeforeEpoch(liveEpoch)
        queuedTasks.forEach { it() }

        assertTrue(overlayAdds == 0)
        assertTrue(cooldownAdds == 0)
    }

    @Test
    fun `fresh token reserved after reset but before invalidation remains executable`() {
        val gate = PresentationGenerationGate()
        val liveEpoch = 1L
        val fresh = requireNotNull(gate.reserve(liveEpoch))
        var executions = 0

        gate.invalidateBeforeEpoch(liveEpoch)

        assertTrue(gate.runIfCurrent(fresh, liveEpoch) { executions += 1 })
        assertTrue(executions == 1)
    }

    @Test
    fun `fresh token survives a later old-epoch reservation before invalidation`() {
        val gate = PresentationGenerationGate()
        val fresh = requireNotNull(gate.reserve(1L))
        val lateOld = requireNotNull(gate.reserve(0L))

        gate.invalidateBeforeEpoch(1L)
        val lateOldDismiss = gate.invalidateThrough(lateOld)

        assertFalse(gate.runIfCurrent(lateOld, 0L) {})
        assertFalse(gate.shouldRemoveThrough(fresh, lateOldDismiss))
        assertTrue(gate.runIfCurrent(fresh, 1L) {})
    }

    @Test
    fun `old delayed dismiss cannot remove a newer same-epoch surface`() {
        val gate = PresentationGenerationGate()
        val old = requireNotNull(gate.reserve(7L))
        val oldDismissCutoff = gate.invalidateThrough(old)
        val fresh = requireNotNull(gate.reserve(7L))

        assertTrue(gate.shouldRemoveThrough(old, oldDismissCutoff))
        assertFalse(gate.shouldRemoveThrough(fresh, oldDismissCutoff))
        assertTrue(gate.runIfCurrent(fresh, 7L) {})
    }

    @Test
    fun `normal dismiss invalidates pending current work but not a later same-epoch threat`() {
        val gate = PresentationGenerationGate()
        val pending = requireNotNull(gate.reserve(3L))

        val dismissCutoff = requireNotNull(gate.invalidateThroughLatest(3L))
        val fresh = requireNotNull(gate.reserve(3L))

        assertFalse(gate.runIfCurrent(pending, 3L) {})
        assertFalse(gate.shouldRemoveThrough(fresh, dismissCutoff))
        assertTrue(gate.runIfCurrent(fresh, 3L) {})
    }

    @Test
    fun `safe cleanup selects only surfaces older than the reset epoch`() {
        val gate = PresentationGenerationGate()
        val old = requireNotNull(gate.reserve(10L))
        val fresh = requireNotNull(gate.reserve(11L))

        val cleanup = gate.invalidateBeforeEpoch(11L)

        assertTrue(gate.shouldRemove(old, cleanup))
        assertFalse(gate.shouldRemove(fresh, cleanup))
        assertTrue(gate.runIfCurrent(fresh, 11L) {})
    }

    @Test
    fun `overlay invalidation cannot advance the independent cooldown gate`() {
        val overlayGate = PresentationGenerationGate()
        val cooldownGate = PresentationGenerationGate()
        val overlayOld = requireNotNull(overlayGate.reserve(4L))
        val cooldownOld = requireNotNull(cooldownGate.reserve(4L))

        overlayGate.invalidateBeforeEpoch(5L)

        assertFalse(overlayGate.runIfCurrent(overlayOld, 4L) {})
        assertTrue(cooldownGate.runIfCurrent(cooldownOld, 4L) {})
    }

    @Test
    fun `cleanup failure never rolls back the epoch floor`() {
        val gate = PresentationGenerationGate()
        val old = requireNotNull(gate.reserve(20L))
        val cleanup = gate.invalidateBeforeEpoch(21L)

        runCatching {
            check(gate.shouldRemove(old, cleanup))
            error("injected remove failure")
        }

        val fresh = requireNotNull(gate.reserve(21L))
        assertFalse(gate.runIfCurrent(old, 20L) {})
        assertTrue(gate.runIfCurrent(fresh, 21L) {})
    }
}
