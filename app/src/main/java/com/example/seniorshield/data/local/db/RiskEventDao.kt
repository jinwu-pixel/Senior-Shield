package com.example.seniorshield.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RiskEventDao {

    /** 신규 이벤트 삽입. 동일 id가 이미 있으면 교체한다. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: RiskEventEntity)

    /**
     * 최신 50건을 발생 시각 내림차순으로 구독한다.
     * 데이터가 변경될 때마다 자동으로 재방출한다.
     */
    @Query("SELECT * FROM risk_events ORDER BY occurredAtMillis DESC LIMIT 50")
    fun observeRecent(): Flow<List<RiskEventEntity>>

    /** [sinceMillis] 이후 발생한 이벤트를 최신순으로 조회한다. */
    @Query("SELECT * FROM risk_events WHERE occurredAtMillis >= :sinceMillis ORDER BY occurredAtMillis DESC")
    suspend fun getEventsSince(sinceMillis: Long): List<RiskEventEntity>

    /** [sinceMillis] 이후 이벤트 수를 반환한다. */
    @Query("SELECT COUNT(*) FROM risk_events WHERE occurredAtMillis >= :sinceMillis")
    suspend fun countEventsSince(sinceMillis: Long): Int

    /** 모든 이벤트를 삭제한다 (테스트/디버그 전용). */
    @Query("DELETE FROM risk_events")
    suspend fun deleteAll()
}
