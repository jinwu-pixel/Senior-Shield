package com.example.seniorshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.data.local.db.RiskEventEntity
import com.example.seniorshield.data.local.db.SeniorShieldDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomDaoStorageTest {
    private lateinit var database: SeniorShieldDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, SeniorShieldDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun duplicateIdReplacesThePersistedRow() = storageTest {
        val dao = database.riskEventDao()
        dao.insert(row("same-id", 10, "old"))
        dao.insert(row("same-id", 20, "new"))

        assertEquals(1, dao.countEventsSince(0))
        assertEquals(listOf(row("same-id", 20, "new")), dao.observeRecent().first())
    }

    @Test
    fun recentQueryKeepsOnlyLatestFiftyInDescendingTimeOrder() = storageTest {
        val dao = database.riskEventDao()
        for (timestamp in 1L..51L) dao.insert(row("event-$timestamp", timestamp))

        val recent = dao.observeRecent().first()
        assertEquals(50, recent.size)
        assertEquals((51L downTo 2L).toList(), recent.map { it.occurredAtMillis })
        assertEquals(51, dao.countEventsSince(0))
    }

    @Test
    fun countIncludesTheExactSinceTimestamp() = storageTest {
        val dao = database.riskEventDao()
        for (timestamp in 1L..51L) dao.insert(row("event-$timestamp", timestamp))

        assertEquals(2, dao.countEventsSince(50))
        assertEquals(1, dao.countEventsSince(51))
        assertEquals(0, dao.countEventsSince(52))
    }

    @Test
    fun isolatedFileDatabaseReopensWithTheSameRowAndSchemaIdentity() = storageTest {
        val name = "m3-${UUID.randomUUID()}.db"
        val expected = row("persisted", 42, "survives close")
        try {
            val first = Room.databaseBuilder(context, SeniorShieldDatabase::class.java, name).build()
            val before: String
            try {
                first.riskEventDao().insert(expected)
                before = identityHash(first)
                assertEquals("bec47e2ef0393e24083a677a42dcbf74", before)
            } finally {
                first.close()
            }

            assertTrue(context.getDatabasePath(name).isFile)
            val reopened = Room.databaseBuilder(context, SeniorShieldDatabase::class.java, name).build()
            try {
                assertEquals(listOf(expected), reopened.riskEventDao().observeRecent().first())
                assertEquals(before, identityHash(reopened))
                assertEquals(1, reopened.riskEventDao().countEventsSince(42))
            } finally {
                reopened.close()
            }
        } finally {
            // Only this test's UUID database is eligible for deletion.
            check(name.startsWith("m3-") && name.endsWith(".db"))
            context.deleteDatabase(name)
        }
    }

    private suspend fun identityHash(db: SeniorShieldDatabase): String = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Room identity row missing" }
            cursor.getString(0)
        }
    }

    private fun row(id: String, time: Long, title: String = "event") = RiskEventEntity(
        id = id,
        title = title,
        description = "stored description",
        occurredAtMillis = time,
        level = "HIGH",
        signalsCsv = "UNKNOWN_CALLER",
    )
}
