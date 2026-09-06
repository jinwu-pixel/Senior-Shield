package com.example.seniorshield.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.example.seniorshield.data.local.GuardianKeys
import com.example.seniorshield.data.local.guardianDataStore
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.repository.GuardianRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GuardianRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GuardianRepository {

    override fun observeGuardians(): Flow<List<Guardian>> =
        context.guardianDataStore.data.map { prefs ->
            val json = prefs[GuardianKeys.GuardiansJson] ?: "[]"
            parseGuardians(json)
        }

    override suspend fun addGuardian(guardian: Guardian): Boolean {
        val current = getGuardians()
        if (current.size >= Guardian.MAX_COUNT) return false
        saveGuardians(current + guardian)
        return true
    }

    override suspend fun removeGuardian(id: String) {
        val current = getGuardians()
        saveGuardians(current.filter { it.id != id })
    }

    override suspend fun getGuardians(): List<Guardian> {
        val prefs = context.guardianDataStore.data.first()
        val json = prefs[GuardianKeys.GuardiansJson] ?: "[]"
        return parseGuardians(json)
    }

    private suspend fun saveGuardians(list: List<Guardian>) {
        context.guardianDataStore.edit { prefs ->
            prefs[GuardianKeys.GuardiansJson] = toJson(list)
        }
    }

    private fun parseGuardians(json: String): List<Guardian> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val phoneNumber = obj.optString("phoneNumber").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Guardian(
                        id = id,
                        name = name,
                        phoneNumber = phoneNumber,
                        relationship = obj.optString("relationship", ""),
                    )
                } catch (e: Exception) {
                    null // 개별 항목 파싱 실패 시 skip
                }
            }
        } catch (e: Exception) {
            emptyList() // JSON 전체 파싱 실패 시 빈 목록 반환
        }
    }

    private fun toJson(list: List<Guardian>): String {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("phoneNumber", g.phoneNumber)
                put("relationship", g.relationship)
            })
        }
        return arr.toString()
    }
}
