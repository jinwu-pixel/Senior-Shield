package com.example.seniorshield.feature.warning

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningScreenSafeConfirmationTest {

    @Test
    fun `screen backs only after successful safe confirmation`() {
        var backCount = 0

        completeSafeConfirmation(
            confirmSafe = { true },
            onBack = { backCount += 1 },
        )

        assertEquals(1, backCount)
    }

    @Test
    fun `screen stays when safe confirmation fails`() {
        var backCount = 0

        completeSafeConfirmation(
            confirmSafe = { false },
            onBack = { backCount += 1 },
        )

        assertEquals(0, backCount)
    }
}
