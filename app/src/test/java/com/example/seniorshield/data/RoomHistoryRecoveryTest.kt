package com.example.seniorshield.data

import com.example.seniorshield.data.local.RoomRiskEventStore
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomHistoryRecoveryTest : StorageFailureTestBase() {
    private val stores = mutableListOf<RoomRiskEventStore>()

    @After
    fun stopStoreScopes() {
        // Production owns a process-lifetime scope. Keep its lifecycle seam test-only.
        stores.forEach { store ->
            val field = RoomRiskEventStore::class.java.getDeclaredField("scope")
            field.isAccessible = true
            (field.get(store) as CoroutineScope).cancel()
        }
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `transient failure recovers in the same store and subscription after five seconds`() = runTest(dispatcher) {
        val dao = ScriptedDao { attempt ->
            if (attempt == 1) error("temporary read failure")
            emit(listOf(entity("recovered")))
            awaitCancellation()
        }
        val store = newStore(dao)
        subscribe(store)
        runCurrent()
        assertEquals(1, dao.collections)
        assertEquals(emptyList<RiskEvent>(), store.recentEvents.value)
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, dao.collections)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, dao.collections)
        assertEquals(listOf("recovered"), store.recentEvents.value.map { it.id })
    }

    @Test
    fun `failure after a successful emission clears history then restores mapped records without changing warning`() = runTest(dispatcher) {
        val fail = CompletableDeferred<Unit>()
        val dao = ScriptedDao { attempt ->
            emit(listOf(entity(if (attempt == 1) "old" else "new")))
            if (attempt == 1) {
                fail.await()
                error("query invalidation failed")
            }
            awaitCancellation()
        }
        val store = newStore(dao)
        val warning = RiskEvent("warning", "title", "description", 1L, RiskLevel.HIGH, emptyList())
        store.updateCurrentRiskEvent(warning)
        subscribe(store)
        runCurrent()
        assertEquals(listOf("old"), store.recentEvents.value.map { it.id })
        fail.complete(Unit)
        runCurrent()
        assertEquals(emptyList<RiskEvent>(), store.recentEvents.value)
        assertSame(warning, store.currentEvent.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(RiskEvent("new", "title", "description", 1L, RiskLevel.HIGH, emptyList())), store.recentEvents.value)
        assertSame(warning, store.currentEvent.value)
    }

    @Test
    fun `persistent failure is paced and stops retrying after the last subscriber leaves`() = runTest(dispatcher) {
        val dao = ScriptedDao { error("persistent failure") }
        val store = newStore(dao)
        val observer = subscribe(store)
        runCurrent()
        repeat(3) { index ->
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(index + 1, dao.collections)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, dao.collections)
        }
        observer.cancel()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(4, dao.collections)
    }

    @Test
    fun `a store with no subscribers does not query the database`() = runTest(dispatcher) {
        val dao = ScriptedDao { awaitCancellation() }
        val store = newStore(dao)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, dao.collections)
        subscribe(store)
        runCurrent()
        assertEquals(1, dao.collections)
    }

    @Test
    fun `leaving retains replay and reentering starts a new query`() = runTest(dispatcher) {
        val dao = ScriptedDao { attempt ->
            emit(listOf(entity("record-$attempt")))
            awaitCancellation()
        }
        val store = newStore(dao)
        val observer = subscribe(store)
        runCurrent()
        assertEquals(listOf("record-1"), store.recentEvents.value.map { it.id })
        observer.cancel()
        runCurrent()
        assertEquals(1, dao.cancellations)
        assertEquals(listOf("record-1"), store.recentEvents.value.map { it.id })
        subscribe(store)
        runCurrent()
        assertEquals(2, dao.collections)
        assertEquals(listOf("record-2"), store.recentEvents.value.map { it.id })
    }

    @Test
    fun `two consumers share one query and removing one does not stop the other`() = runTest(dispatcher) {
        val dao = ScriptedDao { emit(listOf(entity("shared"))); awaitCancellation() }
        val store = newStore(dao)
        val first = subscribe(store)
        val second = subscribe(store)
        runCurrent()
        assertEquals(1, dao.collections)
        first.cancel()
        runCurrent()
        assertEquals(0, dao.cancellations)
        assertEquals(listOf("shared"), store.recentEvents.value.map { it.id })
        second.cancel()
        runCurrent()
        assertEquals(1, dao.cancellations)
    }

    @Test
    fun `explicit upstream cancellation does not emit an empty fallback or retry`() = runTest(dispatcher) {
        val dao = ScriptedDao { attempt ->
            if (attempt == 1) {
                emit(listOf(entity("before-cancel")))
                throw CancellationException("query cancelled")
            }
            emit(listOf(entity("healthy-after-cancel")))
            awaitCancellation()
        }
        val store = newStore(dao)
        subscribe(store)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, dao.collections)
        assertEquals(listOf("before-cancel"), store.recentEvents.value.map { it.id })
    }

    @Test
    fun `an ordinary error racing cancellation does not emit fallback or retry`() = runTest(dispatcher) {
        val dao = ScriptedDao { attempt ->
            if (attempt == 1) {
                emit(listOf(entity("before-race")))
                currentCoroutineContext().cancel()
                error("query failed after cancellation")
            }
            emit(listOf(entity("healthy-after-race")))
            awaitCancellation()
        }
        val store = newStore(dao)
        subscribe(store)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, dao.collections)
        assertEquals(listOf("before-race"), store.recentEvents.value.map { it.id })
    }

    private fun newStore(dao: RiskEventDao) = RoomRiskEventStore(dao, dispatcher).also { stores += it }

    private fun TestScope.subscribe(store: RoomRiskEventStore): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.recentEvents.collect() }

    private fun entity(id: String) = RiskEventEntity(id, "title", "description", 1L, "HIGH", "")

    private class ScriptedDao(
        private val read: suspend FlowCollector<List<RiskEventEntity>>.(Int) -> Unit,
    ) : RiskEventDao {
        var collections = 0
        var cancellations = 0
        override fun observeRecent(): Flow<List<RiskEventEntity>> = flow {
            collections++
            try {
                read(collections)
            } finally {
                cancellations++
            }
        }
        // Any accidental write/count during recovery fails the test immediately.
        override suspend fun insert(event: RiskEventEntity): Unit = error("unexpected insert")
        override suspend fun deleteAll(): Unit = error("unexpected delete")
        override suspend fun countEventsSince(sinceMillis: Long): Int = error("unexpected count")
    }
}
