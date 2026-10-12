package com.example.seniorshield.data

import com.example.seniorshield.data.local.LiveRiskEventStore
import com.example.seniorshield.data.local.RoomRiskEventStore
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.data.repository.RiskRepositoryImpl
import com.example.seniorshield.domain.model.RiskEvent
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomStorageFailureTest : StorageFailureTestBase() {
    @Test
    fun `recent events read failure completes empty without an uncaught exception`() = runTest(dispatcher) {
        val dao = FailingRiskEventDao()
        val uncaught = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
        try {
            val store = RoomRiskEventStore(dao, dispatcher)
            runCurrent()
            assertEquals(1, dao.collections)
            assertEquals(emptyList<RiskEvent>(), store.recentEvents.value)
            assertEquals(emptyList<Throwable>(), uncaught)
            // runTest also reports exceptions captured by coroutines-test from the store's own scope.
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `count failure yields zero`() = runTest(dispatcher) {
        val dao = FailingRiskEventDao()
        val repository = RiskRepositoryImpl(mockk<LiveRiskEventStore>(), dao)
        assertEquals(0, repository.countEventsSince(123L))
        assertEquals(listOf(123L), dao.countArguments)
    }

    @Test
    fun `count propagates explicit cancellation`() = runTest(dispatcher) {
        val cancelled = CancellationException("count fixture")
        val repository = RiskRepositoryImpl(mockk(), FailingRiskEventDao(cancelled))
        assertSame(cancelled, assertSuspendThrows<CancellationException> { repository.countEventsSince(123L) })
    }

    @Test
    fun `count does not return fallback when an ordinary failure races cancellation`() = runTest(dispatcher) {
        val dao = object : RiskEventDao by FailingRiskEventDao() {
            override suspend fun countEventsSince(sinceMillis: Long): Int {
                currentCoroutineContext().cancel()
                throw IllegalStateException("cancelled count fixture")
            }
        }
        val repository = RiskRepositoryImpl(mockk(), dao)
        var returned = false
        val pending = async {
            repository.countEventsSince(123L)
            returned = true
        }
        assertSuspendThrows<CancellationException> { pending.await() }
        assertTrue(pending.isCancelled)
        assertEquals(false, returned)
    }
}

private open class FailingRiskEventDao(
    private val countFailure: Exception = IllegalStateException("count fixture"),
) : RiskEventDao {
    var collections = 0
        private set
    val countArguments = mutableListOf<Long>()
    override fun observeRecent(): Flow<List<RiskEventEntity>> = flow {
        collections++
        throw IllegalStateException("recent fixture")
    }
    override suspend fun countEventsSince(sinceMillis: Long): Int {
        countArguments += sinceMillis
        throw countFailure
    }
    override suspend fun insert(event: RiskEventEntity) = error("not used")
    override suspend fun deleteAll() = error("not used")
}
