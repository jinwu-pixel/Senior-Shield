package com.example.seniorshield.monitoring.appinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.seniorshield.domain.model.RiskSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-AppInstall"

/**
 * 새 앱 설치를 감지하고, 사이드로딩(Play Store 외 출처)이면
 * [RiskSignal.SUSPICIOUS_APP_INSTALLED] 신호를 방출한다.
 *
 * 보이스피싱의 핵심 단계인 "이 앱을 설치하세요" 유도를 차단하기 위한 모니터.
 */
@Singleton
class RealAppInstallRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppInstallRiskMonitor {

    override fun observeInstallSignals(): Flow<List<RiskSignal>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

                val packageName = intent.data?.schemeSpecificPart ?: return
                Log.d(TAG, "새 앱 설치 감지: $packageName")

                if (isSideloaded(packageName)) {
                    Log.w(TAG, "사이드로딩 앱 감지: $packageName")
                    trySend(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
                } else if (isKnownRemoteControlApp(packageName)) {
                    Log.w(TAG, "원격제어 앱 설치 감지: $packageName")
                    trySend(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED))
                } else {
                    Log.d(TAG, "Play Store 설치 앱 — 무시: $packageName")
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        Log.d(TAG, "앱 설치 모니터 시작")

        // 초기값: 신호 없음
        trySend(emptyList())

        awaitClose {
            context.unregisterReceiver(receiver)
            Log.d(TAG, "앱 설치 모니터 중지")
        }
    }.distinctUntilChanged()

    private fun isSideloaded(packageName: String): Boolean {
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
            Log.d(TAG, "$packageName installer: $installer")
            installer !in TRUSTED_INSTALLERS
        } catch (e: Exception) {
            Log.w(TAG, "설치 출처 확인 실패: ${e.message}")
            true // 출처 불명 → 의심
        }
    }

    private fun isKnownRemoteControlApp(packageName: String): Boolean {
        if (REMOTE_CONTROL_PACKAGES.contains(packageName)) return true
        return REMOTE_CONTROL_PREFIXES.any { packageName.startsWith(it) }
    }

    companion object {
        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",         // Google Play Store
            "com.google.android.packageinstaller",
            "com.samsung.android.scloud",  // Samsung Galaxy Store
            "com.sec.android.app.samsungapps",
            "com.skt.skaf.A000Z00040",     // T Store
            "com.kt.olleh.storefront",     // KT
            "com.lguplus.appstore",        // LG U+
        )

        private val REMOTE_CONTROL_PREFIXES = setOf(
            "com.teamviewer.",
            "net.anydesk.",
            "com.anydesk.",
        )

        private val REMOTE_CONTROL_PACKAGES = setOf(
            "com.rsupport.rs.activity.rsupport",
            "com.rsupport.rs.activity.rsupport.sec",
            "com.rsupport.remotecall.rtc.host",
            "com.logmein.rescuemobile",
            "com.splashtop.remote.pad.v2",
            "com.realvnc.viewer.android",
        )
    }
}
