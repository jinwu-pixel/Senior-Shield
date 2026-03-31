package com.example.seniorshield.monitoring.deviceenv

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.seniorshield.domain.model.RiskSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-DeviceEnv"

/**
 * 기기 환경(루팅, test-keys 빌드 등)을 1회 점검하고
 * [RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT] 신호를 방출한다.
 *
 * 루팅 상태는 런타임 중 변하지 않으므로 앱 시작 시 1회만 평가하며
 * 결과를 [MutableStateFlow]로 고정 방출한다.
 *
 * USB 디버깅/개발자 옵션은 일반 개발 환경에서도 활성화되므로
 * 단독 탐지 시 신호를 방출하지 않는다 (오탐 방지).
 */
@Singleton
class RealDeviceEnvironmentRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceEnvironmentRiskMonitor {

    private val signals = MutableStateFlow<List<RiskSignal>>(emptyList())

    init {
        try {
            val isHighRisk = checkRootIndicators()
            if (isHighRisk) {
                signals.value = listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
                Log.w(TAG, "HIGH_RISK_DEVICE_ENVIRONMENT detected")
            } else {
                Log.d(TAG, "device environment: normal")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "device environment check failed — fallback to safe", e)
            signals.value = emptyList()
        }
    }

    override fun observeDeviceEnvironmentSignals(): Flow<List<RiskSignal>> = signals

    private fun checkRootIndicators(): Boolean {
        val hasSuBinary = checkSuBinary()
        val hasSuperuserApk = checkSuperuserApk()
        val hasTestKeys = checkTestKeys()
        val hasRootPackages = checkRootPackages()

        Log.d(TAG, "su=$hasSuBinary, superuser=$hasSuperuserApk, testKeys=$hasTestKeys, rootPkg=$hasRootPackages")

        return hasSuBinary || hasSuperuserApk || hasTestKeys || hasRootPackages
    }

    private fun checkSuBinary(): Boolean = SU_PATHS.any { path ->
        try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun checkSuperuserApk(): Boolean = try {
        File("/system/app/Superuser.apk").exists()
    } catch (_: Exception) {
        false
    }

    private fun checkTestKeys(): Boolean =
        Build.TAGS?.contains("test-keys") == true

    private fun checkRootPackages(): Boolean = ROOT_PACKAGES.any { pkg ->
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
        )

        private val ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "me.phh.superuser",
        )
    }
}
