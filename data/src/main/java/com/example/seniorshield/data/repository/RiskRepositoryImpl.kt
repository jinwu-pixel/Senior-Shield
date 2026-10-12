package com.example.seniorshield.data.repository

import android.util.Log
import com.example.seniorshield.data.local.LiveRiskEventStore
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.repository.RiskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-RiskRepo"

@Singleton
class RiskRepositoryImpl @Inject constructor(
    private val store: LiveRiskEventStore,
    private val dao: RiskEventDao,
) : RiskRepository {

    override fun getRecentRiskEvents(): Flow<List<RiskEvent>> = store.recentEvents

    override fun getCurrentRiskEvent(): Flow<RiskEvent?> = store.currentEvent

    override suspend fun countEventsSince(sinceMillis: Long): Int =
        try {
            dao.countEventsSince(sinceMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "risk count read failed — zero used", e)
            0
        }
}
