package com.example.seniorshield.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.seniorshield.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsRepositoryStorageFailureTest : StorageFailureTestBase() {
    @Test
    fun `corrupt settings emit false and complete for all four observations`() = runTest(dispatcher) {
        val (store, _) = newStore(corrupt = true)
        assertSuspendThrows<CorruptionException> { store.data.first() }
        observations(SettingsRepositoryImpl(store)).forEach { values ->
            assertEquals(listOf(false), values.toList())
        }
    }

    @Test
    fun `all four settings writes absorb corruption without changing bytes`() = runTest(dispatcher) {
        val (store, file) = newStore(corrupt = true)
        assertSuspendThrows<CorruptionException> { store.data.first() }
        val original = file.readBytes()
        setters(SettingsRepositoryImpl(store)).forEach { write ->
            write(true)
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test
    fun `all four observations propagate non IO failures unchanged`() = runTest(dispatcher) {
        val failure = IllegalStateException("read fixture")
        observations(SettingsRepositoryImpl(FailingSettingsStore(failure))).forEach { values ->
            assertSame(failure, assertSuspendThrows<IllegalStateException> { values.first() })
        }
    }

    @Test
    fun `all four writes propagate non IO failures unchanged`() = runTest(dispatcher) {
        val failure = IllegalStateException("write fixture")
        setters(SettingsRepositoryImpl(FailingSettingsStore(failure))).forEach { write ->
            assertSame(failure, assertSuspendThrows<IllegalStateException> { write(true) })
        }
    }

    @Test
    fun `healthy settings round trip all four keys without changing other keys`() = runTest(dispatcher) {
        val (store, _) = newStore()
        val repository = SettingsRepositoryImpl(store)
        val expected = MutableList(4) { false }
        val values = observations(repository)
        assertEquals(expected, values.map { it.first() })
        setters(repository).forEachIndexed { index, write ->
            for (enabled in listOf(true, false)) {
                write(enabled)
                expected[index] = enabled
                assertEquals(expected, values.map { it.first() })
            }
        }
    }

    private fun observations(repository: SettingsRepositoryImpl): List<Flow<Boolean>> = listOf(
        repository.observeOnboardingCompleted(), repository.observeSmsAlertEnabled(),
        repository.observeTestModeEnabled(), repository.observeSmsMenuEnabled(),
    )

    private fun setters(repository: SettingsRepositoryImpl): List<suspend (Boolean) -> Unit> = listOf(
        repository::setOnboardingCompleted, repository::setSmsAlertEnabled,
        repository::setTestModeEnabled, repository::setSmsMenuEnabled,
    )
}

private class FailingSettingsStore(private val failure: Exception) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw failure }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw failure
}
