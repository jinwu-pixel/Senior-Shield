package com.example.seniorshield.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
abstract class StorageFailureTestBase {
    @get:Rule val temporaryFolder = TemporaryFolder()
    protected val dispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseScopes() {
        scopes.forEach { it.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    protected fun <T : ViewModel> T.owned(): T = also { scopes += it.viewModelScope }

    protected fun newStore(corrupt: Boolean = false): Pair<DataStore<Preferences>, File> {
        val file = temporaryFolder.newFile("store-${scopes.size}.preferences_pb")
        // Length-delimited field declares two bytes, but only one byte follows.
        if (corrupt) file.writeBytes(byteArrayOf(0x0a, 0x02, 0x0a))
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }) to file
    }
}

internal suspend inline fun <reified T : Throwable> assertSuspendThrows(
    crossinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.name}, got ${error.javaClass.name}", error)
    }
    throw AssertionError("Expected ${T::class.java.name}")
}
