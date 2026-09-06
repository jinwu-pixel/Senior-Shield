package com.example.seniorshield.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.seniorshield.data.repository.GuardianRepositoryImpl
import com.example.seniorshield.domain.model.Guardian
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GuardianStorageTest {
    @Before
    fun resetPreferences() = storageTest { SuiteDataStores.reset() }

    @Test
    fun missingKeyIsEmptyThreeAreAllowedFourthRejectedAndRemovalFreesASlot() = storageTest {
        val repository = GuardianRepositoryImpl(SuiteDataStores.context)
        val one = Guardian("one", "First", "01011111111")
        val two = Guardian("two", "Second", "01022222222")
        val three = Guardian("three", "Third", "01033333333")
        val four = Guardian("four", "Fourth", "01044444444")

        assertTrue(SuiteDataStores.guardians.data.first().asMap().isEmpty())
        assertEquals(emptyList<Guardian>(), repository.getGuardians())
        assertEquals(emptyList<Guardian>(), repository.observeGuardians().first())
        assertTrue(repository.addGuardian(one))
        assertTrue(repository.addGuardian(two))
        assertTrue(repository.addGuardian(three))
        assertFalse(repository.addGuardian(four))
        assertEquals(listOf(one, two, three), repository.getGuardians())

        repository.removeGuardian("two")
        assertEquals(listOf(one, three), repository.observeGuardians().first())
        assertTrue(repository.addGuardian(four))
        repository.removeGuardian("not-present")
        assertEquals(listOf(one, three, four), repository.getGuardians())
    }

    @Test
    fun savedJsonKeepsFourFieldsAndAnotherRepositoryReadsTheSameFile() = storageTest {
        val writer = GuardianRepositoryImpl(SuiteDataStores.context)
        val reader = GuardianRepositoryImpl(SuiteDataStores.context)
        val guardian = Guardian("id-1", "Name with \"quotes\"", "+82 10-1234-5678", "family")

        assertTrue(writer.addGuardian(guardian))
        val preferences = SuiteDataStores.guardians.data.first()
        assertEquals(setOf("guardians_json"), preferences.asMap().keys.map { it.name }.toSet())
        val array = JSONArray(preferences[stringPreferencesKey("guardians_json")]!!)
        assertEquals(1, array.length())
        val json = array.getJSONObject(0)
        assertEquals(setOf("id", "name", "phoneNumber", "relationship"), json.keys().asSequence().toSet())
        assertEquals("id-1", json.getString("id"))
        assertEquals("Name with \"quotes\"", json.getString("name"))
        assertEquals("+82 10-1234-5678", json.getString("phoneNumber"))
        assertEquals("family", json.getString("relationship"))
        assertEquals(listOf(guardian), reader.getGuardians())
        assertEquals(listOf(guardian), reader.observeGuardians().first())
        assertTrue(File(SuiteDataStores.context.filesDir, "datastore/guardian_store.preferences_pb").isFile)
    }

    @Test
    fun malformedJsonAndNonArrayRootProduceAnEmptyList() = storageTest {
        val repository = GuardianRepositoryImpl(SuiteDataStores.context)
        for (json in listOf("not-json", "{\"id\":\"object-not-array\"}")) {
            writeJson(json)
            assertEquals(emptyList<Guardian>(), repository.getGuardians())
            assertEquals(emptyList<Guardian>(), repository.observeGuardians().first())
        }
    }

    @Test
    fun invalidEntriesAreSkippedAndMissingRelationshipDefaultsToEmpty() = storageTest {
        writeJson(
            """[
                {"id":"valid","name":"Valid","phoneNumber":"01012345678"},
                {"id":"","name":"No id","phoneNumber":"01011111111"},
                {"id":"no-name","name":"   ","phoneNumber":"01011111111"},
                {"id":"no-phone","name":"No phone"},
                "not-an-object",
                null,
                {"id":"last","name":"Last","phoneNumber":"01087654321","relationship":"friend"}
            ]""".trimIndent(),
        )
        val expected = listOf(
            Guardian("valid", "Valid", "01012345678", ""),
            Guardian("last", "Last", "01087654321", "friend"),
        )
        val repository = GuardianRepositoryImpl(SuiteDataStores.context)
        assertEquals(expected, repository.getGuardians())
        assertEquals(expected, repository.observeGuardians().first())
    }

    private suspend fun writeJson(json: String) {
        SuiteDataStores.guardians.edit { it[stringPreferencesKey("guardians_json")] = json }
    }
}
