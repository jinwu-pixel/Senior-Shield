package com.example.seniorshield.monitoring.appusage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.seniorshield.domain.model.RiskSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-AppMonitor"
private const val POLL_INTERVAL_MS = 5_000L

/** 원격제어 앱을 탐색할 최근 사용 시간 윈도우 (30초) */
private const val DETECTION_WINDOW_MS = 30_000L

/**
 * 원격제어 앱 감지 후 이 시간(ms) 이내에 뱅킹앱이 열리면 연계 신호를 발생시킨다.
 * 세션 타임아웃(30분)과 동일하게 설정하여, TeamViewer 종료 후 뱅킹 앱 실행까지
 * 시간 차가 있어도 BANKING_APP_OPENED_AFTER_REMOTE_APP 신호가 생성되도록 한다.
 */
private const val REMOTE_APP_WINDOW_MS = 30 * 60 * 1000L

/**
 * UsageStatsManager 기반 앱 사용 신호 수집기.
 *
 * 5초 간격으로 최근 30초 내 사용된 앱 전체를 폴링하여 아래 두 가지 신호를 감지한다.
 *  - REMOTE_CONTROL_APP_OPENED : 원격제어 앱이 최근 30초 이내에 포그라운드였음
 *  - BANKING_APP_OPENED_AFTER_REMOTE_APP : 원격제어 앱 사용 60초 이내에 뱅킹 앱이 열림
 *
 * 이전 버전과의 차이:
 *  - 단일 앱(가장 최근)만 보던 것 → 윈도우 내 모든 앱 집합으로 변경
 *    (다른 앱이 위에 올라와도 TeamViewer 감지 가능)
 *  - INTERVAL_DAILY → INTERVAL_BEST (수초 단위 고해상도 기록)
 *
 * 동작 전제:
 *  - PACKAGE_USAGE_STATS 특수 권한이 부여되어 있어야 한다.
 *  - 권한이 없으면 빈 리스트를 계속 방출하며 앱을 중단시키지 않는다.
 *
 * 로그 태그: SeniorShield-AppMonitor
 */
@Singleton
class RealAppUsageRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : AppUsageRiskMonitor {

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /**
     * 뱅킹 앱이 현재 포그라운드에 있는지 감지한다.
     *
     * 폴링 간격보다 약간 넉넉한 윈도우(POLL + 1초)로 쿼리해 타이밍 오차를 흡수한다.
     * 값이 바뀔 때만 방출한다.
     */
    override fun observeBankingAppForeground(): Flow<Boolean> = flow {
        while (true) {
            val recent = getRecentlyUsedPackages(POLL_INTERVAL_MS + 1_000L)
            emit(recent.any { it in BANKING_PACKAGES })
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    override fun observeAppUsageSignals(): Flow<List<RiskSignal>> = flow {
        var remoteAppLastSeenAt: Long? = null

        while (true) {
            val recentPackages = getRecentlyUsedPackages(DETECTION_WINDOW_MS)
            val now = System.currentTimeMillis()

            // 원격제어 앱 감지 → 마지막 감지 시각 갱신
            val detectedRemoteApp = recentPackages.firstOrNull { isRemoteControlApp(it) }
            if (detectedRemoteApp != null) {
                remoteAppLastSeenAt = now
                Log.d(TAG, "remote control app detected: $detectedRemoteApp")
            }

            // 추적 윈도우 만료 시 초기화
            if (remoteAppLastSeenAt != null && now - remoteAppLastSeenAt > REMOTE_APP_WINDOW_MS) {
                Log.d(TAG, "remote app window expired, resetting tracker")
                remoteAppLastSeenAt = null
            }

            emit(buildSignals(recentPackages, remoteAppLastSeenAt))
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged()
        .flowOn(ioDispatcher)

    private fun buildSignals(
        recentPackages: Set<String>,
        remoteAppLastSeenAt: Long?,
    ): List<RiskSignal> {
        val signals = mutableListOf<RiskSignal>()

        val remoteApp = recentPackages.firstOrNull { isRemoteControlApp(it) }
        if (remoteApp != null) {
            signals += RiskSignal.REMOTE_CONTROL_APP_OPENED
            Log.d(TAG, "signal: REMOTE_CONTROL_APP_OPENED ($remoteApp)")
        }

        val bankingApp = recentPackages.firstOrNull { it in BANKING_PACKAGES }
        if (remoteAppLastSeenAt != null && bankingApp != null) {
            signals += RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP
            Log.d(TAG, "signal: BANKING_APP_OPENED_AFTER_REMOTE_APP ($bankingApp)")
        }

        return signals
    }

    /**
     * 원격제어 앱 여부 판단.
     *
     * 정확한 패키지명 매칭(REMOTE_CONTROL_PACKAGES) 외에
     * 접두사 매칭(REMOTE_CONTROL_PREFIXES)을 병행한다.
     *
     * 배경: TeamViewer는 배포 채널에 따라 패키지명이 다르다.
     *   - Google Play:   com.teamviewer.teamviewer
     *   - Samsung Store: com.teamviewer.teamviewer.market.mobile
     *   - QuickSupport:  com.teamviewer.quicksupport.host
     *   → com.teamviewer. 접두사로 한 번에 포괄한다.
     */
    private fun isRemoteControlApp(packageName: String): Boolean =
        packageName in REMOTE_CONTROL_PACKAGES ||
                REMOTE_CONTROL_PREFIXES.any { packageName.startsWith(it) }

    /**
     * 최근 [windowMs] 이내에 사용된 앱의 패키지명 집합을 반환한다.
     *
     * INTERVAL_BEST: 수초 단위의 고해상도 사용 기록을 반환하는 모드.
     * PACKAGE_USAGE_STATS 미허가 시 빈 집합을 반환하고 경고 로그를 출력한다.
     */
    private fun getRecentlyUsedPackages(windowMs: Long): Set<String> {
        val manager = usageStatsManager ?: return emptySet()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - windowMs
        return try {
            val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)
            if (stats.isNullOrEmpty()) {
                Log.w(TAG, "queryUsageStats 결과 없음 — PACKAGE_USAGE_STATS 권한 미부여 또는 최근 앱 사용 없음")
                emptySet()
            } else {
                stats
                    .filter { it.lastTimeUsed >= startTime }
                    .mapTo(mutableSetOf()) { it.packageName }
                    .also { Log.d(TAG, "recent packages (${windowMs / 1000}s window, ${it.size}개): $it") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryUsageStats 실패: ${e.message}")
            emptySet()
        }
    }

    companion object {
        /**
         * 접두사 매칭: 해당 prefix로 시작하는 모든 패키지를 원격제어 앱으로 간주한다.
         * 배포 채널별 패키지명 변형을 한 번에 포괄하기 위해 사용한다.
         */
        private val REMOTE_CONTROL_PREFIXES = setOf(
            "com.teamviewer.",   // TeamViewer 전 변형 (market.mobile, quicksupport.host 등)
            "net.anydesk.",      // AnyDesk 구버전
            "com.anydesk.",      // AnyDesk 신버전
        )

        /** 정확한 패키지명 매칭 목록 (접두사 매칭으로 포괄되지 않는 앱) */
        private val REMOTE_CONTROL_PACKAGES = setOf(
            // 알서포트 (국내 다수 사용)
            "com.rsupport.rs.activity.rsupport",              // MobileSupport - RemoteCall
            "com.rsupport.rs.activity.rsupport.sec",          // MobileSupport for SAMSUNG
            "com.rsupport.remotecall.rtc.host",               // RemoteCall

            // 기타
            "com.logmein.rescuemobile",                       // LogMeIn Rescue
            "com.splashtop.remote.pad.v2",                    // Splashtop Personal
            "com.realvnc.viewer.android",                     // RealVNC Viewer
        )

        /** 원격제어 앱과 연계 감지 대상인 뱅킹 앱 패키지명 목록 */
        private val BANKING_PACKAGES = setOf(
            "com.kbstar.kbbank",                              // KB국민은행 (KB스타뱅킹)
            "com.nonghyup.nhallonebank",                      // NH농협은행 (NH올원뱅크)
            "nh.smart.banking",                               // NH농협은행 (NH스마트뱅킹)
            "com.shinhan.sbanking",                           // 신한은행 (신한 SOL뱅크)
            "com.hanabank.ebk.channel.android.hananbank",     // 하나은행 (구 하나원큐)
            "com.hanabank.oqf",                               // 하나은행 (신 하나원큐)
            "com.wooribank.smart.npib",                       // 우리은행 (우리WON뱅킹)
            "com.ibk.android.ionebank",                       // IBK기업은행 (i-ONE Bank)
            "com.scbank.ma30",                                // SC제일은행 (SC모바일뱅킹)
            "com.smg.spbs",                                   // 새마을금고 (MG더뱅킹)
            "com.suhyup.psmb",                                // 수협은행 (수협 파트너뱅크)
            "com.kakaobank.channel",                          // 카카오뱅크
            "com.kbankwith.smartbank",                        // 케이뱅크
            "viva.republica.toss",                            // 토스
        )
    }
}
