package com.example.seniorshield.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

internal fun Flow<Preferences>.withReadFallback(tag: String, store: String): Flow<Preferences> =
    catch { e ->
        if (e is IOException) {
            Log.w(tag, "$store read failed — defaults used", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }
