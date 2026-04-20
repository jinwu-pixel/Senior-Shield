package com.example.seniorshield.monitoring.di

import com.example.seniorshield.monitoring.appinstall.AppInstallRiskMonitor
import com.example.seniorshield.monitoring.appinstall.RealAppInstallRiskMonitor
import com.example.seniorshield.monitoring.appusage.AppUsageRiskMonitor
import com.example.seniorshield.monitoring.appusage.RealAppUsageRiskMonitor
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.call.RealCallRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.DeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.RealDeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.evaluator.RiskEvaluator
import com.example.seniorshield.monitoring.evaluator.RiskEvaluatorImpl
import com.example.seniorshield.monitoring.orchestrator.DefaultRiskDetectionCoordinator
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MonitoringModule {

    @Binds @Singleton
    abstract fun bindCallRiskMonitor(impl: RealCallRiskMonitor): CallRiskMonitor

    @Binds @Singleton
    abstract fun bindAppUsageRiskMonitor(impl: RealAppUsageRiskMonitor): AppUsageRiskMonitor

    @Binds @Singleton
    abstract fun bindAppInstallRiskMonitor(impl: RealAppInstallRiskMonitor): AppInstallRiskMonitor

    @Binds @Singleton
    abstract fun bindDeviceEnvironmentRiskMonitor(impl: RealDeviceEnvironmentRiskMonitor): DeviceEnvironmentRiskMonitor

    @Binds @Singleton
    abstract fun bindRiskEvaluator(impl: RiskEvaluatorImpl): RiskEvaluator

    @Binds @Singleton
    abstract fun bindRiskDetectionCoordinator(impl: DefaultRiskDetectionCoordinator): RiskDetectionCoordinator
}
