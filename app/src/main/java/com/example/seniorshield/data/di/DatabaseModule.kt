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
        ).build()

    @Provides
    @Singleton
    fun provideRiskEventDao(database: SeniorShieldDatabase): RiskEventDao =
        database.riskEventDao()
}
