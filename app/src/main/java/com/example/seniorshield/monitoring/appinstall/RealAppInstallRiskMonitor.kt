package com.example.seniorshield.monitoring.appinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.registry.RemoteControlAppRegistry
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-AppInstall"

/** 신호 방출 후 리셋까지 유지 시간. combine이 즉시 수신하므로 5초면 충분. */
private const val SIGNAL_HOLD_MS = 5_000L

/**
 * 새 앱 설치를 감지하고, 사이드로딩(Play Store 외 출처)이면
 * [RiskSignal.SUSPICIOUS_APP_INSTALLED] 신호를 방출한다.
 *
 * 보이스피싱의 핵심 단계인 "이 앱을 설치하세요" 유도를 차단하기 위한 모니터.
 *
 * ## 생산 provenance (A안, 2026-07-16)
 * epoch는 onReceive 진입부 — 설치 출처 **조회·분류 시작 전** — 에 1회 캡처되어 두 분기
 * (사이드로딩/원격제어)가 공유 승계한다. PackageManager 조회 도중 사용자 reset이 끼어도
 * 방출은 pre-reset epoch를 유지해 보수적으로 필터된다. seed와 hold 만료 클리어는 값이
 * empty(세션 생성 불가)이므로 방출 시점 epoch를 새로 읽는다 — reset 이후의 fresh 클리어가
 * source 배제를 자연 해소한다.
 *
 * ## distinctUntilChanged 예외 (라운드 9-11)
 * INSTALL은 상태가 아니라 **사건**이므로 [Produced] 기본 동등성((value, epoch))으로 dedupe한다 —
 * reset 후 hold 창 내 실제 신규 설치는 같은 신호 값이어도 새 epoch로 통과하고,
 * 같은 epoch의 중복 설치는 기존처럼 합쳐진다.
 */
@Singleton
class RealAppInstallRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteControlRegistry: RemoteControlAppRegistry,
    private val resetEpochProvider: ResetEpochProvider,
) : AppInstallRiskMonitor {

    override fun observeInstallSignals(): Flow<Produced<List<RiskSignal>>> = callbackFlow {
        var resetJob: Job? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

                val packageName = intent.data?.schemeSpecificPart ?: return
                // 계약: 조회·분류(설치 출처/원격제어 판정) **시작 전** 단일 캡처 — 두 분기가 공유.
                val producedAtEpoch = resetEpochProvider.userResetEpoch
                Log.d(TAG, "새 앱 설치 감지: $packageName (epoch=$producedAtEpoch)")

                if (isSideloaded(packageName)) {
                    Log.w(TAG, "사이드로딩 앱 감지: $packageName")
                    trySend(Produced(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED), producedAtEpoch))
                    Log.d(TAG, "emit(SUSPICIOUS_APP_INSTALLED) at ${System.currentTimeMillis()}")
                } else if (isKnownRemoteControlApp(packageName)) {
                    Log.w(TAG, "원격제어 앱 설치 감지: $packageName")
                    trySend(Produced(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED), producedAtEpoch))
                    Log.d(TAG, "emit(SUSPICIOUS_APP_INSTALLED) at ${System.currentTimeMillis()}")
                } else {
                    Log.d(TAG, "Play Store 설치 앱 — 무시: $packageName")
                    return
                }

                // 신호 방출 후 일정 시간 뒤 리셋 — stale 신호가 새 세션을 생성하는 것을 방지
                resetJob?.let {
                    it.cancel()
                    Log.d(TAG, "resetJob cancel at ${System.currentTimeMillis()} (연속 설치 — 타이머 갱신)")
                }
                resetJob = launch {
                    delay(SIGNAL_HOLD_MS)
                    // 클리어는 방출 시점 epoch — empty는 세션을 되살릴 수 없고 fresh 클리어가
                    // reset 후 source 배제를 자연 해소한다.
                    trySend(Produced(emptyList(), resetEpochProvider.userResetEpoch))
                    Log.d(TAG, "emit(empty) — signal reset at ${System.currentTimeMillis()}")
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

        // 초기값: 신호 없음 (방출 시점 epoch — combine 충전용 중립값)
        trySend(Produced(emptyList(), resetEpochProvider.userResetEpoch))

        awaitClose {
            resetJob?.cancel()
            resetJob = null
            context.unregisterReceiver(receiver)
            Log.d(TAG, "앱 설치 모니터 중지 — resetJob cancelled")
        }
    }.distinctUntilChanged() // (value, epoch) 기본 동등성 — 사건 flow 예외 (KDoc 참조)

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

    private fun isKnownRemoteControlApp(packageName: String): Boolean =
        remoteControlRegistry.matches(packageName)

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
    }
}
