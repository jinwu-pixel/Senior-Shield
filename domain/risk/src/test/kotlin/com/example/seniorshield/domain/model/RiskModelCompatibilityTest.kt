package com.example.seniorshield.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskModelCompatibilityTest {
    @Test
    fun `FQCN drift is rejected for the six moved risk models`() {
        assertEquals(
            listOf(
                "com.example.seniorshield.domain.model.AlertState",
                "com.example.seniorshield.domain.model.RiskEvent",
                "com.example.seniorshield.domain.model.RiskLevel",
                "com.example.seniorshield.domain.model.RiskScore",
                "com.example.seniorshield.domain.model.RiskSignal",
                "com.example.seniorshield.domain.model.SignalCategory",
            ),
            listOf(
                AlertState::class.java.name,
                RiskEvent::class.java.name,
                RiskLevel::class.java.name,
                RiskScore::class.java.name,
                RiskSignal::class.java.name,
                SignalCategory::class.java.name,
            ),
        )
    }

    @Test
    fun `enum rename or reorder is rejected for alert state risk level and signal category`() {
        assertEquals(
            listOf("OBSERVE", "GUARDED", "INTERRUPT", "CRITICAL"),
            AlertState.values().map { it.name },
        )
        assertEquals(
            listOf("LOW", "MEDIUM", "HIGH", "CRITICAL"),
            RiskLevel.values().map { it.name },
        )
        assertEquals(
            listOf("PASSIVE", "AMPLIFIER", "TRIGGER"),
            SignalCategory.values().map { it.name },
        )
    }

    @Test
    fun `risk signal enum rename reorder or category drift is rejected`() {
        assertEquals(
            listOf(
                "UNKNOWN_CALLER" to "PASSIVE",
                "LONG_CALL_DURATION" to "PASSIVE",
                "UNVERIFIED_CALLER" to "PASSIVE",
                "REMOTE_CONTROL_APP_OPENED" to "TRIGGER",
                "BANKING_APP_OPENED_AFTER_REMOTE_APP" to "TRIGGER",
                "HIGH_RISK_DEVICE_ENVIRONMENT" to "PASSIVE",
                "SUSPICIOUS_APP_INSTALLED" to "TRIGGER",
                "REPEATED_UNKNOWN_CALLER" to "PASSIVE",
                "REPEATED_CALL_THEN_LONG_TALK" to "AMPLIFIER",
                "TELEBANKING_AFTER_SUSPICIOUS" to "TRIGGER",
            ),
            RiskSignal.values().map { it.name to it.category.name },
        )
    }

    @Test
    fun `risk event constructor and component order API drift is rejected`() {
        val event = RiskEvent(
            id = "event-id",
            title = "title",
            description = "description",
            occurredAtMillis = 1_726_000_000_000L,
            level = RiskLevel.HIGH,
            signals = listOf(RiskSignal.UNKNOWN_CALLER),
        )

        val (id, title, description, occurredAtMillis, level, signals) = event
        assertEquals(
            listOf(
                "event-id",
                "title",
                "description",
                1_726_000_000_000L,
                RiskLevel.HIGH,
                listOf(RiskSignal.UNKNOWN_CALLER),
            ),
            listOf(id, title, description, occurredAtMillis, level, signals),
        )
    }

    @Test
    fun `risk score constructor and component order API drift is rejected`() {
        val score = RiskScore(
            total = 75,
            level = RiskLevel.HIGH,
            signals = listOf(RiskSignal.REPEATED_UNKNOWN_CALLER),
        )

        val (total, level, signals) = score
        assertEquals(
            listOf(
                75,
                RiskLevel.HIGH,
                listOf(RiskSignal.REPEATED_UNKNOWN_CALLER),
            ),
            listOf(total, level, signals),
        )
    }
}
