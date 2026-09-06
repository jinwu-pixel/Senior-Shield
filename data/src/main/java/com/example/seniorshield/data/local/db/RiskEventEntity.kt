package com.example.seniorshield.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room 엔티티 — RiskEvent 도메인 모델의 영속화 표현.
 *
 * - [level]: RiskLevel.name 문자열로 저장
 * - [signalsCsv]: RiskSignal.name 값을 쉼표로 구분해 저장 (예: "UNKNOWN_CALLER,LONG_CALL_DURATION")
 */
@Entity(tableName = "risk_events")
data class RiskEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val occurredAtMillis: Long,
    val level: String,
    val signalsCsv: String,
)
