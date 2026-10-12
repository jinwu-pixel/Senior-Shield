package com.example.seniorshield.data

import android.app.Application
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.example.seniorshield.data.local.GuardianKeys
import com.example.seniorshield.data.repository.GuardianRepositoryImpl
import com.example.seniorshield.domain.model.Guardian
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The repository's org.json parser/serializer must be real, not Android's JVM stubs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class GuardianRepositoryStorageFailureTest : StorageFailureTestBase() {
    private val one = Guardian("one", "First", "01011111111")
    private val two = Guardian("two", "Second", "01022222222")
    private val three = Guardian("three", "Third", "01033333333")
    private val four = Guardian("four", "Fourth", "01044444444")

    @Test
    fun `corrupt guardians read empty but writes fail without changing bytes`() = runTest(dispatcher) {
        val (store, file) = newStore(corrupt = true)
        assertSuspendThrows<CorruptionException> { store.data.first() }
        val original = file.readBytes()
        val repository = GuardianRepositoryImpl(store)
        assertEquals(listOf(emptyList<Guardian>()), repository.observeGuardians().toList())
        assertEquals(emptyList<Guardian>(), repository.getGuardians())
        assertSuspendThrows<IOException> { repository.addGuardian(one) }
        assertArrayEquals(original, file.readBytes())
        assertSuspendThrows<IOException> { repository.removeGuardian("one") }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `add preserves two stored guardians when the public read flow fails`() = runTest(dispatcher) {
        val store = SplitGuardianStore(TWO_GUARDIANS)
        val repository = GuardianRepositoryImpl(store)
        assertTrue(repository.addGuardian(three))
        assertEquals(listOf(one, two, three), storedGuardians(store))
        assertEquals(1, store.writeCount)
        assertEquals(1, store.updateCount)
        assertEquals(0, store.readCount)
    }

    @Test
    fun `remove preserves the other stored guardian when the public read flow fails`() = runTest(dispatcher) {
        val store = SplitGuardianStore(TWO_GUARDIANS)
        GuardianRepositoryImpl(store).removeGuardian("one")
        assertEquals(listOf(two), storedGuardians(store))
        assertEquals(1, store.writeCount)
        assertEquals(1, store.updateCount)
        assertEquals(0, store.readCount)
    }

    @Test
    fun `full stored list rejects add without a write despite failed public reads`() = runTest(dispatcher) {
        val store = SplitGuardianStore(THREE_GUARDIANS)
        val original = store.current
        assertFalse(GuardianRepositoryImpl(store).addGuardian(four))
        assertEquals(original, store.current)
        assertEquals(listOf(one, two, three), storedGuardians(store))
        assertEquals(0, store.writeCount)
        assertEquals(1, store.updateCount)
        assertEquals(0, store.readCount)
    }

    @Test
    fun `guardian reads propagate non IO failures unchanged`() = runTest(dispatcher) {
        val failure = IllegalStateException("read fixture")
        val repository = GuardianRepositoryImpl(SplitGuardianStore(TWO_GUARDIANS, failure))
        assertSame(failure, assertSuspendThrows<IllegalStateException> { repository.observeGuardians().first() })
        assertSame(failure, assertSuspendThrows<IllegalStateException> { repository.getGuardians() })
    }

    @Test
    fun `healthy guardians allow three reject fourth and remove only the requested id`() = runTest(dispatcher) {
        val (store, file) = newStore()
        val repository = GuardianRepositoryImpl(store)
        assertEquals(emptyList<Guardian>(), repository.getGuardians())
        listOf(one, two, three).forEach { assertTrue(repository.addGuardian(it)) }
        val fullBytes = file.readBytes()
        assertFalse(repository.addGuardian(four))
        assertArrayEquals(fullBytes, file.readBytes())
        assertEquals(listOf(one, two, three), repository.observeGuardians().first())
        repository.removeGuardian("two")
        assertEquals(listOf(one, three), repository.getGuardians())
        assertTrue(repository.addGuardian(four))
        repository.removeGuardian("missing")
        assertEquals(listOf(one, three, four), repository.getGuardians())
    }

    private suspend fun storedGuardians(store: SplitGuardianStore): List<Guardian> {
        // A separate read-only view reveals the committed prefs; never read through the failing flow.
        val snapshot = object : DataStore<Preferences> {
            override val data = flowOf(store.current)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("read-only snapshot")
        }
        return GuardianRepositoryImpl(snapshot).getGuardians()
    }

    companion object {
        private const val TWO_GUARDIANS = """[{"id":"one","name":"First","phoneNumber":"01011111111","relationship":""},{"id":"two","name":"Second","phoneNumber":"01022222222","relationship":""}]"""
        private const val THREE_GUARDIANS = """[{"id":"one","name":"First","phoneNumber":"01011111111","relationship":""},{"id":"two","name":"Second","phoneNumber":"01022222222","relationship":""},{"id":"three","name":"Third","phoneNumber":"01033333333","relationship":""}]"""
    }
}

private class SplitGuardianStore(json: String, private val readFailure: Exception = IOException("read fixture")) : DataStore<Preferences> {
    var current: Preferences = preferencesOf(GuardianKeys.GuardiansJson to json)
        private set
    var readCount = 0
        private set
    var updateCount = 0
        private set
    var writeCount = 0
        private set
    override val data: Flow<Preferences> = flow {
        readCount++
        throw readFailure
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        updateCount++
        val next = transform(current)
        if (next != current) {
            current = next
            writeCount++
        }
        return current
    }
}
