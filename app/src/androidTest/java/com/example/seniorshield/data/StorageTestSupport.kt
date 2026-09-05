package com.example.seniorshield.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.seniorshield.data.local.guardianDataStore
import com.example.seniorshield.data.local.settingsDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

internal fun storageTest(block: suspend () -> Unit) {
    runBlocking { withTimeout(10_000) { block() } }
}

/**
 * Both production delegates cache a process-wide DataStore. Their first access must use
 * this single wrapper, whose applicationContext preserves its isolated filesDir.
 * Keep the wrapper and files alive until instrumentation exits; never delete live stores.
 * A second repository below shares an active instance, not a simulated process restart.
 */
internal object SuiteDataStores {
    val context: Context = object : ContextWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
    ) {
        private val isolatedFiles = File(baseContext.filesDir, "m3-datastore-${UUID.randomUUID()}")
            .apply { check(mkdirs()) { "Could not create isolated DataStore directory" } }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
    }

    val settings = context.settingsDataStore
    val guardians = context.guardianDataStore

    suspend fun reset() {
        settings.edit { it.clear() }
        guardians.edit { it.clear() }
    }
}
