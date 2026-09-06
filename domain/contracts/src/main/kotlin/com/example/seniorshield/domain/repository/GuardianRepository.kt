package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.Guardian
import kotlinx.coroutines.flow.Flow

interface GuardianRepository {
    fun observeGuardians(): Flow<List<Guardian>>
    suspend fun addGuardian(guardian: Guardian): Boolean
    suspend fun removeGuardian(id: String)
    suspend fun getGuardians(): List<Guardian>
}
