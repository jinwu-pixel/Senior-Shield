package com.example.seniorshield.monitoring.di

import com.example.seniorshield.monitoring.appinstall.AppInstallRiskMonitor
import com.example.seniorshield.monitoring.appinstall.FakeAppInstallRiskMonitor
import com.example.seniorshield.monitoring.appinstall.RealAppInstallRiskMonitor
import com.example.seniorshield.monitoring.appusage.AppUsageRiskMonitor
import com.example.seniorshield.monitoring.appusage.FakeAppUsageRiskMonitor
import com.example.seniorshield.monitoring.appusage.RealAppUsageRiskMonitor
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.call.FakeCallRiskMonitor
import com.example.seniorshield.monitoring.call.RealCallRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.DeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.FakeDeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.deviceenv.RealDeviceEnvironmentRiskMonitor
import com.example.seniorshield.monitoring.evaluator.FakeRiskEvaluator
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

    // ── CallRiskMonitor 활성 바인딩 ───────────────────────────────
    //
    // [검증 단계 활성] RealCallRiskMonitor
    // 구독 시 TelephonyCallback 자동 등록, 구독 종료 시 자동 해제
    @Binds @Singleton
    abstract fun bindCallRiskMonitor(impl: RealCallRiskMonitor): CallRiskMonitor

    // [Fake 복원 시] 위 @Binds 를 제거하고 아래 주석을 해제한다.
    // 두 @Binds 를 동시에 활성화하면 Hilt 중복 바인딩 오류 발생.
    // @Binds @Singleton
    // abstract fun bindCallRiskMonitor(impl: FakeCallRiskMonitor): CallRiskMonitor
    //
    // ─────────────────────────────────────────────────────────────

    // ── AppUsageRiskMonitor 활성 바인딩 ──────────────────────────────
    //
    // [활성] RealAppUsageRiskMonitor — PACKAGE_USAGE_STATS 권한 필요
    @Binds @Singleton
    abstract fun bindAppUsageRiskMonitor(impl: RealAppUsageRiskMonitor): AppUsageRiskMonitor

    // [Fake 복원 시] 위 @Binds 를 제거하고 아래 주석을 해제한다.
    // @Binds @Singleton
    // abstract fun bindAppUsageRiskMonitor(impl: FakeAppUsageRiskMonitor): AppUsageRiskMonitor
    //
    // ─────────────────────────────────────────────────────────────

    // ── AppInstallRiskMonitor 활성 바인딩 ─────────────────────────────
    //
    // [활성] RealAppInstallRiskMonitor — ACTION_PACKAGE_ADDED 감지
    @Binds @Singleton
    abstract fun bindAppInstallRiskMonitor(impl: RealAppInstallRiskMonitor): AppInstallRiskMonitor

    // [Fake 복원 시] 위 @Binds 를 제거하고 아래 주석을 해제한다.
    // @Binds @Singleton
    // abstract fun bindAppInstallRiskMonitor(impl: FakeAppInstallRiskMonitor): AppInstallRiskMonitor
    //
    // ─────────────────────────────────────────────────────────────

    // ── DeviceEnvironmentRiskMonitor 활성 바인딩 ────────────────────
    //
    // [활성] RealDeviceEnvironmentRiskMonitor — 루팅/test-keys 탐지
    @Binds @Singleton
    abstract fun bindDeviceEnvironmentRiskMonitor(impl: RealDeviceEnvironmentRiskMonitor): DeviceEnvironmentRiskMonitor

    // [Fake 복원 시] 위 @Binds 를 제거하고 아래 주석을 해제한다.
    // @Binds @Singleton
    // abstract fun bindDeviceEnvironmentRiskMonitor(impl: FakeDeviceEnvironmentRiskMonitor): DeviceEnvironmentRiskMonitor
    //
    // ─────────────────────────────────────────────────────────────

    // ── RiskEvaluator 활성 바인딩 ─────────────────────────────────────
    //
    // [활성] RiskEvaluatorImpl — 가중치 기반 위험 평가
    @Binds @Singleton
    abstract fun bindRiskEvaluator(impl: RiskEvaluatorImpl): RiskEvaluator

    // [Fake 복원 시] 위 @Binds 를 제거하고 아래 주석을 해제한다.
    // @Binds @Singleton
    // abstract fun bindRiskEvaluator(impl: FakeRiskEvaluator): RiskEvaluator
    //
    // ─────────────────────────────────────────────────────────────

    @Binds @Singleton
    abstract fun bindRiskDetectionCoordinator(impl: DefaultRiskDetectionCoordinator): RiskDetectionCoordinator
}
