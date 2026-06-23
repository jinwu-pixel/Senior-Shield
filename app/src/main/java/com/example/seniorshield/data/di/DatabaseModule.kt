package com.example.seniorshield.data.di

import android.content.Context
import androidx.room.Room
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.SeniorShieldDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SeniorShieldDatabase =
        Room.databaseBuilder(
            context,
            SeniorShieldDatabase::class.java,
            "senior_shield.db",
        )
            // 미정의 마이그레이션 경로 최후 안전망. version bump 시 Migration 미제공이면
            // open-time 크래시(IllegalStateException) 대신 테이블 재생성(데이터 폐기)한다.
            // risk_events는 재생성 가능한 비핵심 로컬 캐시라 허용. 보존가치 데이터가 추가되면
            // fallbackToDestructiveMigrationFrom으로 범위를 좁히고 명시 Migration을 등록할 것.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideRiskEventDao(database: SeniorShieldDatabase): RiskEventDao =
        database.riskEventDao()
}
