package com.example.seniorshield.data.di

import com.example.seniorshield.data.local.LiveRiskEventStore
import com.example.seniorshield.data.local.RoomRiskEventStore
import com.example.seniorshield.data.repository.GuardianRepositoryImpl
import com.example.seniorshield.data.repository.RiskRepositoryImpl
import com.example.seniorshield.data.repository.SettingsRepositoryImpl
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.domain.repository.RiskRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds @Singleton
    abstract fun bindLiveRiskEventStore(impl: RoomRiskEventStore): LiveRiskEventStore

    @Binds @Singleton
    abstract fun bindRiskEventSink(impl: RoomRiskEventStore): RiskEventSink

    @Binds @Singleton
    abstract fun bindRiskRepository(impl: RiskRepositoryImpl): RiskRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindGuardianRepository(impl: GuardianRepositoryImpl): GuardianRepository
}
