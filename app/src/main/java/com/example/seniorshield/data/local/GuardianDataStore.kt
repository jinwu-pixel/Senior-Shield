package com.example.seniorshield.data.local

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.guardianDataStore by preferencesDataStore(name = "guardian_store")

object GuardianKeys {
    val GuardiansJson = stringPreferencesKey("guardians_json")
}
