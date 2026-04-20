package com.example.seniorshield.monitoring.appusage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.registry.RemoteControlAppRegistry
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
    private val remoteControlRegistry: RemoteControlAppRegistry,
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

    private fun isRemoteControlApp(packageName: String): Boolean =
        remoteControlRegistry.matches(packageName)

    /**
     * 최근 [windowMs] 이내에 사용된 앱의 패키지명 집합을 반환한다.
     *
     * 기기 호환성을 위해 3단계 fallback 체인으로 조회한다:
     * 1. INTERVAL_BEST — 수초 단위 고해상도 (대부분의 기기)
     * 2. INTERVAL_DAILY — 일부 MediaTek ROM에서 INTERVAL_BEST가 빈 결과를 반환하는 경우
     * 3. queryEvents — MOVE_TO_FOREGROUND 이벤트 직접 조회 (최종 폴백)
     *
     * PACKAGE_USAGE_STATS 미허가 시 빈 집합을 반환하고 경고 로그를 출력한다.
     */
    private fun getRecentlyUsedPackages(windowMs: Long): Set<String> {
        val manager = usageStatsManager ?: return emptySet()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - windowMs

        // 1순위: INTERVAL_BEST (수초 단위 고해상도)
        tryQueryUsageStats(manager, UsageStatsManager.INTERVAL_BEST, startTime, endTime)?.let {
            Log.d(TAG, "recent packages via INTERVAL_BEST (${windowMs / 1000}s, ${it.size}개): $it")
            return it
        }

        // 2순위: INTERVAL_DAILY (일부 MediaTek ROM에서 INTERVAL_BEST 미지원)
        tryQueryUsageStats(manager, UsageStatsManager.INTERVAL_DAILY, startTime, endTime)?.let {
            Log.d(TAG, "recent packages via INTERVAL_DAILY fallback (${windowMs / 1000}s, ${it.size}개): $it")
            return it
        }

        // 3순위: queryEvents → MOVE_TO_FOREGROUND 직접 조회
        tryQueryEvents(manager, startTime, endTime)?.let {
            Log.d(TAG, "recent packages via queryEvents fallback (${windowMs / 1000}s, ${it.size}개): $it")
            return it
        }

        Log.w(TAG, "모든 UsageStats 조회 실패 — 권한 미부여 또는 기기 호환성 문제")
        return emptySet()
    }

    /** queryUsageStats 시도. 유효 결과 없으면 null 반환. */
    private fun tryQueryUsageStats(
        manager: UsageStatsManager,
        interval: Int,
        startTime: Long,
        endTime: Long,
    ): Set<String>? {
        return try {
            val stats = manager.queryUsageStats(interval, startTime, endTime)
            val raw = stats?.size ?: -1
            val filtered = stats
                ?.filter { it.lastTimeUsed >= startTime }
                ?.mapTo(mutableSetOf()) { it.packageName }
                ?: emptySet()
            Log.w(TAG, "tryQueryUsageStats(interval=$interval): raw=$raw, filtered=${filtered.size} $filtered")
            filtered.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "queryUsageStats(interval=$interval) 실패: ${e.message}")
            null
        }
    }

    /** queryEvents로 MOVE_TO_FOREGROUND 이벤트에서 패키지명 추출. */
    private fun tryQueryEvents(
        manager: UsageStatsManager,
        startTime: Long,
        endTime: Long,
    ): Set<String>? {
        return try {
            val events = manager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            val packages = mutableSetOf<String>()
            var totalEvents = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                totalEvents++
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    packages.add(event.packageName)
                }
            }
            Log.w(TAG, "tryQueryEvents: totalEvents=$totalEvents, foreground=${packages.size} $packages")
            packages.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents 실패: ${e.message}")
            null
        }
    }

    override fun latestBankingForegroundEventTimestamp(windowMs: Long): Long? {
        val manager = usageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val startTime = now - windowMs
        return try {
            val events = manager.queryEvents(startTime, now)
            val event = UsageEvents.Event()
            var latestTs: Long? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.packageName in BANKING_PACKAGES
                ) {
                    val ts = event.timeStamp
                    if (latestTs == null || ts > latestTs) latestTs = ts
                }
            }
            latestTs
        } catch (e: Exception) {
            Log.w(TAG, "latestBankingForegroundEventTimestamp 실패: ${e.message}")
            null
        }
    }

    companion object {
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
