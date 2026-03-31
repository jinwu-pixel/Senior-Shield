package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.RiskEvent
import kotlinx.coroutines.flow.Flow

interface RiskRepository {
    fun getRecentRiskEvents(): Flow<List<RiskEvent>>
    fun getCurrentRiskEvent(): Flow<RiskEvent?>
    suspend fun countEventsSince(sinceMillis: Long): Int
}