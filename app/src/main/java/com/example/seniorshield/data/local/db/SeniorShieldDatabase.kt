package com.example.seniorshield.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RiskEventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SeniorShieldDatabase : RoomDatabase() {
    abstract fun riskEventDao(): RiskEventDao
}
