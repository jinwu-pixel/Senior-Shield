package com.example.seniorshield.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.data.local.RoomRiskEventStore
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.data.local.db.SeniorShieldDatabase
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRiskEventStoreStorageTest {
    // The production store owns an eager process-lifetime scope with no disposal API.
    // Keep one real DB/store pair alive for this instrumentation process as well.
    private object Fixture {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SeniorShieldDatabase::class.java,
        ).build()
        val dao = database.riskEventDao()
        val store = RoomRiskEventStore(dao, Dispatchers.IO).also {
            assertNull("A newly created store has no current event", it.currentEvent.value)
        }
    }

    @Before
    fun resetStore() = storageTest {
        Fixture.store.clearAll()
        Fixture.store.recentEvents.first { it.isEmpty() }
    }

    @Test
    fun highAndUnknownCallerRoundTripThroughSqliteEnumNames() = storageTest {
        val event = event("roundtrip", 10)
        Fixture.store.pushRiskEvent(event)

        val persisted = Fixture.dao.observeRecent().first().single()
        assertEquals("HIGH", persisted.level)
        assertEquals("UNKNOWN_CALLER", persisted.signalsCsv)
        assertEquals(listOf(event), Fixture.store.recentEvents.first { it.isNotEmpty() })
        assertEquals(event, Fixture.store.currentEvent.value)
    }

    @Test
    fun invalidStoredEnumsFallBackOrAreSkippedAndBlankSignalsRemainEmpty() = storageTest {
        Fixture.dao.insert(
            RiskEventEntity("invalid", "invalid values", "description", 20,
                "FUTURE_LEVEL", "UNKNOWN_CALLER, FUTURE_SIGNAL, LONG_CALL_DURATION"),
        )
        Fixture.dao.insert(
            RiskEventEntity("empty", "empty signals", "description", 10, "LOW", ""),
        )

        val events = Fixture.store.recentEvents.first { it.size == 2 }
        assertEquals(listOf("invalid", "empty"), events.map { it.id })
        assertEquals(RiskLevel.LOW, events[0].level)
        assertEquals(listOf(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION), events[0].signals)
        assertEquals(emptyList<RiskSignal>(), events[1].signals)
        assertNull(Fixture.store.currentEvent.value)
    }

    @Test
    fun pushRecordUpdateAndClearKeepTheirDistinctPersistenceAndCurrentSemantics() = storageTest {
        val store = Fixture.store
        val dao = Fixture.dao
        val a = event("A", 10)
        val b = event("B", 20)
        val c = event("C", 30)

        store.pushRiskEvent(a)
        assertEquals(a, store.currentEvent.value)
        assertEquals(listOf("A"), dao.observeRecent().first().map { it.id })
        assertEquals(listOf(a), store.recentEvents.first { it.size == 1 })

        store.recordRiskEvent(b)
        assertEquals(a, store.currentEvent.value)
        assertEquals(listOf("B", "A"), dao.observeRecent().first().map { it.id })
        assertEquals(listOf(b, a), store.recentEvents.first { it.size == 2 })

        store.updateCurrentRiskEvent(c)
        assertEquals(c, store.currentEvent.value)
        assertEquals(listOf("B", "A"), dao.observeRecent().first().map { it.id })

        store.clearCurrentRiskEvent()
        assertNull(store.currentEvent.value)
        assertEquals(2, dao.countEventsSince(0))
        assertEquals(listOf(b, a), store.recentEvents.value)

        store.updateCurrentRiskEvent(c)
        store.clearAll()
        assertNull(store.currentEvent.value)
        assertEquals(0, dao.countEventsSince(0))
        assertEquals(emptyList<RiskEvent>(), store.recentEvents.first { it.isEmpty() })
    }

    private fun event(id: String, time: Long) = RiskEvent(
        id, "title-$id", "description-$id", time,
        RiskLevel.HIGH, listOf(RiskSignal.UNKNOWN_CALLER),
    )
}
