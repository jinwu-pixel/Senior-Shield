package com.example.seniorshield.data

import android.content.Context
import androidx.room.Room
import com.example.seniorshield.data.di.DatabaseModule
import com.example.seniorshield.data.local.db.RiskEventDao
import com.example.seniorshield.data.local.db.SeniorShieldDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Production DataModule and the app dispatcher provider remain installed unchanged. */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object M3DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SeniorShieldDatabase =
        Room.inMemoryDatabaseBuilder(context, SeniorShieldDatabase::class.java).build()

    @Provides
    @Singleton
    fun provideRiskEventDao(database: SeniorShieldDatabase): RiskEventDao = database.riskEventDao()
}
