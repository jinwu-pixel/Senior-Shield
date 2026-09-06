package com.example.seniorshield.domain

import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractCompatibilityTest {
    @Test
    fun guardian_preserves_data_class_defaults_components_constant_and_immutability() {
        val guardian = Guardian(
            id = "guardian-id",
            name = "Guardian Name",
            phoneNumber = "01012345678",
        )

        assertEquals("com.example.seniorshield.domain.model.Guardian", Guardian::class.java.name)
        assertEquals("guardian-id", guardian.id)
        assertEquals("Guardian Name", guardian.name)
        assertEquals("01012345678", guardian.phoneNumber)
        assertEquals("", guardian.relationship)
        assertEquals("guardian-id", guardian.component1())
        assertEquals("Guardian Name", guardian.component2())
        assertEquals("01012345678", guardian.component3())
        assertEquals("", guardian.component4())
        assertEquals(3, Guardian.MAX_COUNT)

        val propertyNames = setOf("id", "name", "phoneNumber", "relationship")
        val propertyFields: List<Field> = Guardian::class.java.declaredFields.filter { field: Field ->
            field.name in propertyNames
        }
        assertEquals(propertyNames, propertyFields.mapTo(mutableSetOf()) { it.name })
        assertTrue(propertyFields.all { field: Field -> Modifier.isFinal(field.modifiers) })
        assertFalse(
            Guardian::class.java.declaredMethods.any { method: Method ->
                method.name in setOf("setId", "setName", "setPhoneNumber", "setRelationship")
            },
        )
    }

    @Test
    fun risk_repository_preserves_implementation_and_consumer_signatures() = runBlocking {
        val repository: RiskRepository = ExactRiskRepositoryFake()
        val recentEvents: Flow<List<RiskEvent>> = repository.getRecentRiskEvents()
        val currentEvent: Flow<RiskEvent?> = repository.getCurrentRiskEvent()
        val count: Int = repository.countEventsSince(123L)

        assertEquals("com.example.seniorshield.domain.repository.RiskRepository", RiskRepository::class.java.name)
        assertNotNull(recentEvents)
        assertNotNull(currentEvent)
        assertEquals(0, count)
    }

    @Test
    fun risk_event_sink_and_settings_repository_preserve_exact_signatures() {
        val sink: RiskEventSink = ExactRiskEventSinkFake()
        sink.clearCurrentRiskEvent()

        runBlocking {
            val event = riskEvent()
            val pushed: Unit = sink.pushRiskEvent(event)
            val recorded: Unit = sink.recordRiskEvent(event)
            val updated: Unit = sink.updateCurrentRiskEvent(event)
            val cleared: Unit = sink.clearAll()
            assertEquals(Unit, pushed)
            assertEquals(Unit, recorded)
            assertEquals(Unit, updated)
            assertEquals(Unit, cleared)
        }

        val settings: SettingsRepository = ExactSettingsRepositoryFake()
        val onboarding: Flow<Boolean> = settings.observeOnboardingCompleted()
        val smsAlert: Flow<Boolean> = settings.observeSmsAlertEnabled()
        val testMode: Flow<Boolean> = settings.observeTestModeEnabled()
        val smsMenu: Flow<Boolean> = settings.observeSmsMenuEnabled()
        assertEquals("com.example.seniorshield.domain.repository.RiskEventSink", RiskEventSink::class.java.name)
        assertEquals("com.example.seniorshield.domain.repository.SettingsRepository", SettingsRepository::class.java.name)
        assertNotNull(onboarding)
        assertNotNull(smsAlert)
        assertNotNull(testMode)
        assertNotNull(smsMenu)

        runBlocking {
            val onboardingSet: Unit = settings.setOnboardingCompleted(true)
            val smsAlertSet: Unit = settings.setSmsAlertEnabled(false)
            val testModeSet: Unit = settings.setTestModeEnabled(true)
            val smsMenuSet: Unit = settings.setSmsMenuEnabled(false)
            assertEquals(Unit, onboardingSet)
            assertEquals(Unit, smsAlertSet)
            assertEquals(Unit, testModeSet)
            assertEquals(Unit, smsMenuSet)
        }
    }

    @Test
    fun guardian_repository_preserves_implementation_and_consumer_signatures() = runBlocking {
        val repository: GuardianRepository = ExactGuardianRepositoryFake()
        val guardians: Flow<List<Guardian>> = repository.observeGuardians()
        val added: Boolean = repository.addGuardian(
            Guardian(
                id = "guardian-id",
                name = "Guardian Name",
                phoneNumber = "01012345678",
                relationship = "family",
            ),
        )
        val removed: Unit = repository.removeGuardian("guardian-id")
        val snapshot: List<Guardian> = repository.getGuardians()

        assertEquals("com.example.seniorshield.domain.repository.GuardianRepository", GuardianRepository::class.java.name)
        assertNotNull(guardians)
        assertFalse(added)
        assertEquals(Unit, removed)
        assertTrue(snapshot.isEmpty())
    }
}

private class ExactRiskRepositoryFake : RiskRepository {
    override fun getRecentRiskEvents(): Flow<List<RiskEvent>> = flowOf(emptyList())

    override fun getCurrentRiskEvent(): Flow<RiskEvent?> = flowOf(null)

    override suspend fun countEventsSince(sinceMillis: Long): Int = 0
}

private class ExactRiskEventSinkFake : RiskEventSink {
    override suspend fun pushRiskEvent(event: RiskEvent): Unit = Unit

    override suspend fun recordRiskEvent(event: RiskEvent): Unit = Unit

    override suspend fun updateCurrentRiskEvent(event: RiskEvent): Unit = Unit

    override fun clearCurrentRiskEvent(): Unit = Unit

    override suspend fun clearAll(): Unit = Unit
}

private class ExactSettingsRepositoryFake : SettingsRepository {
    override fun observeOnboardingCompleted(): Flow<Boolean> = flowOf(false)

    override suspend fun setOnboardingCompleted(completed: Boolean): Unit = Unit

    override fun observeSmsAlertEnabled(): Flow<Boolean> = flowOf(false)

    override suspend fun setSmsAlertEnabled(enabled: Boolean): Unit = Unit

    override fun observeTestModeEnabled(): Flow<Boolean> = flowOf(false)

    override suspend fun setTestModeEnabled(enabled: Boolean): Unit = Unit

    override fun observeSmsMenuEnabled(): Flow<Boolean> = flowOf(false)

    override suspend fun setSmsMenuEnabled(enabled: Boolean): Unit = Unit
}

private class ExactGuardianRepositoryFake : GuardianRepository {
    override fun observeGuardians(): Flow<List<Guardian>> = flowOf(emptyList())

    override suspend fun addGuardian(guardian: Guardian): Boolean = false

    override suspend fun removeGuardian(id: String): Unit = Unit

    override suspend fun getGuardians(): List<Guardian> = emptyList()
}

private fun riskEvent() = RiskEvent(
    id = "risk-id",
    title = "title",
    description = "description",
    occurredAtMillis = 1L,
    level = RiskLevel.LOW,
    signals = emptyList(),
)
