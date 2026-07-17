package com.example.seniorshield.monitoring.appusage

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.registry.RemoteControlAppRegistry
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** poll 간격 미러 (5초). */
private const val POLL_MS = 5_000L

/**
 * [RealAppUsageRiskMonitor] 생산 경계(A안) + 3상 조회 + clean-transition 격리 검증.
 *
 * 하네스: UsageStatsManager를 mockk로 제어 — 테스트가 반복(iteration)마다 조회 결과를
 * [statsResult]로 바꾼다. `null` = raw 무관측(Unknown), 목록 = Observed(윈도우 내 사용은
 * lastTimeUsed로 표현). ioDispatcher는 test scheduler라 poll 루프가 가상 시간으로 결정적이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealAppUsageRiskMonitorProvenanceTest {

    private class SteppingEpochProvider(var current: Long = 0L) : ResetEpochProvider {
        override val userResetEpoch: Long get() = current
    }

    private val provider = SteppingEpochProvider()
    private val fakeClock = FakeClock(now = 1_000_000L)

    /** 이번 poll의 조회 결과. null → 전 계층 raw 무관측(Unknown). */
    private var statsResult: List<UsageStats>? = null

    private val usm = mockk<UsageStatsManager> {
        every { queryUsageStats(any(), any(), any()) } answers { statsResult }
        every { queryEvents(any(), any()) } returns mockk<UsageEvents> {
            every { hasNextEvent() } returns false
        }
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.USAGE_STATS_SERVICE) } returns usm
    }
    private val registry = mockk<RemoteControlAppRegistry> {
        every { matches(any()) } answers { firstArg<String>().contains("teamviewer") }
    }

    private fun usageStats(packageName: String, lastUsedAt: Long): UsageStats = mockk {
        every { this@mockk.packageName } returns packageName
        every { lastTimeUsed } returns lastUsedAt
    }

    /** 윈도우 내 사용 앱. */
    private fun recentApp(packageName: String) = usageStats(packageName, fakeClock.provider())

    /** raw 관측은 있으나 윈도우 밖(성공한 부재 관측). */
    private fun staleApp() = usageStats("com.some.old", 0L)

    private fun TestScope.newMonitor(): RealAppUsageRiskMonitor =
        RealAppUsageRiskMonitor(
            context,
            StandardTestDispatcher(testScheduler),
            registry,
            provider,
        ).apply {
            clock = fakeClock.provider
            monotonicClock = fakeClock.provider // replay 지평 축도 같은 가짜 시계로 제어
        }

    private fun TestScope.collectApp(
        monitor: RealAppUsageRiskMonitor,
    ): Pair<MutableList<Produced<List<RiskSignal>>>, Job> {
        val emissions = mutableListOf<Produced<List<RiskSignal>>>()
        val job = launch { monitor.observeAppUsageSignals().collect { emissions += it } }
        runCurrent()
        return emissions to job
    }

    private fun TestScope.nextPoll() {
        advanceTimeBy(POLL_MS)
        runCurrent()
    }

    @Test
    fun `epoch is captured at the loop head before the query`() = runTest {
        var bumped = false
        every { usm.queryUsageStats(any(), any(), any()) } answers {
            // 조회 도중 reset — 캡처가 루프 헤드(조회 전)라면 이번 방출은 epoch 0.
            if (!bumped) {
                bumped = true
                provider.current = 5L
            }
            statsResult
        }
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissions, job) = collectApp(monitor)

        assertEquals(1, emissions.size)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissions[0].value)
        assertEquals("조회 시작 전(루프 헤드) 캡처 epoch여야 함", 0L, emissions[0].producedAtEpoch)
        job.cancel()
    }

    @Test
    fun `unknown banking query skips the tick after the initial neutral emission`() = runTest {
        statsResult = null // 전 계층 raw 무관측 → Unknown
        val monitor = newMonitor()
        val emissions = mutableListOf<Produced<Boolean>>()
        val job = launch { monitor.observeBankingAppForeground().collect { emissions += it } }
        runCurrent()

        assertEquals("최초 1회 combine 충전용 중립값", 1, emissions.size)
        assertFalse(emissions[0].value)

        nextPoll() // 여전히 Unknown → 방출 없음 (false로 위장 금지)
        nextPoll()
        assertEquals("Unknown tick은 스킵되어야 함", 1, emissions.size)

        statsResult = listOf(recentApp("com.kakaobank.channel")) // 조회 회복 — 뱅킹 포그라운드
        nextPoll()
        assertEquals(2, emissions.size)
        assertTrue(emissions[1].value)
        job.cancel()
    }

    @Test
    fun `remote seen on the first post-reset poll is quarantined until a successful absence`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissions, job) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissions[0].value)

        provider.current = 1L // 사용자 안전 확인 (reset)

        // 첫 post-reset 조회 — lookback이 pre-reset 사용을 계속 관측: 격리(직접 신호도 차단).
        nextPoll()
        assertEquals(2, emissions.size)
        assertTrue("격리 중에는 REMOTE 직접 신호도 방출 금지", emissions[1].value.isEmpty())

        nextPoll() // 격리 유지 — 값 불변이라 방출 없음
        assertEquals(2, emissions.size)

        statsResult = listOf(staleApp()) // 성공한(Observed) 부재 관측 → gate 개방 + stale 앵커 정리
        nextPoll()
        assertEquals("부재 관측은 값 불변([]) — 방출 없음", 2, emissions.size)

        statsResult = listOf(recentApp("com.teamviewer.host")) // 소멸→재등장: clean transition
        nextPoll()
        assertEquals(3, emissions.size)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissions[2].value)
        assertEquals("재무장은 fresh epoch", 1L, emissions[2].producedAtEpoch)
        job.cancel()
    }

    @Test
    fun `unknown results never open the gate`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissions, job) = collectApp(monitor)

        provider.current = 1L // reset → 격리 시작
        statsResult = null // 조회 실패 반복 — 부재 관측으로 인정되면 안 된다
        nextPoll()
        nextPoll()

        statsResult = listOf(recentApp("com.teamviewer.host"))
        nextPoll()
        assertTrue(
            "Unknown은 gate를 열지 못한다 — REMOTE 미방출",
            emissions.none { it.producedAtEpoch >= 1L && RiskSignal.REMOTE_CONTROL_APP_OPENED in it.value },
        )
        job.cancel()
    }

    @Test
    fun `composite signal derives from the armed anchor`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissions, job) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissions[0].value)

        // 원격앱 소멸(부재 관측) 후 30분 창 안에서 뱅킹 등장 → 복합 신호, 앵커 epoch 승계
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        nextPoll()
        assertEquals(2, emissions.size)
        assertEquals(listOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP), emissions[1].value)
        assertEquals("복합 신호는 앵커 장전 시점 epoch 승계", 0L, emissions[1].producedAtEpoch)
        job.cancel()
    }

    /**
     * 라운드 12-③: 재구독 첫 Unknown에서 fresh 중립(false)을 재생성하면, 직전 실제 상태가
     * true였던 경우 회복된 true가 가짜 신규 edge(false→true)가 되어 쿨다운을 오발동시킬 수 있다.
     * 마지막 실제 관측을 **원래 epoch로 replay**해야 한다.
     */
    @Test
    fun `resubscription replays the last real banking observation instead of a fresh neutral`() = runTest {
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue("실제 관측: 뱅킹 포그라운드", emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        statsResult = null // 재구독 직후 조회 실패 (FGS 재시작 + 일시 장애 모사)
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()

        assertEquals(1, emissionsB.size)
        assertTrue(
            "fresh 중립(false)이 아니라 마지막 실제 관측(true)을 replay해야 함 — 가짜 edge 차단",
            emissionsB[0].value,
        )
        assertEquals("원래 관측 epoch 유지 (재스탬프 금지)", 0L, emissionsB[0].producedAtEpoch)
        jobB.cancel()
    }

    /** 라운드 12-③ (APP 축): 재구독 첫 Unknown은 마지막 실제 신호 목록을 원래 epoch로 replay한다. */
    @Test
    fun `resubscription replays the last real app observation instead of a fresh neutral`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        statsResult = null
        val (emissionsB, jobB) = collectApp(monitor)
        assertEquals(1, emissionsB.size)
        assertEquals(
            "fresh 중립(empty)이 아니라 마지막 실제 관측을 replay해야 함",
            listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            emissionsB[0].value,
        )
        assertEquals(0L, emissionsB[0].producedAtEpoch)
        jobB.cancel()
    }

    @Test
    fun `a newer collector supersedes the older one`() = runTest {
        statsResult = listOf(staleApp())
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor) // 구세대
        assertEquals(1, emissionsA.size)

        val (emissionsB, jobB) = collectApp(monitor) // 신세대 — generation 전진
        assertEquals(1, emissionsB.size)

        statsResult = listOf(recentApp("com.teamviewer.host"))
        nextPoll()
        nextPoll()

        assertEquals("구세대 collector는 결과를 폐기하고 종료해야 함", 1, emissionsA.size)
        assertTrue(
            "신세대 collector는 정상 방출",
            emissionsB.any { RiskSignal.REMOTE_CONTROL_APP_OPENED in it.value },
        )
        jobA.cancel()
        jobB.cancel()
    }

    /**
     * 라운드 13-②: replay는 관측이 자신의 lookback 지평(BANKING 6초) 안에 있을 때만 허용된다 —
     * 수 시간 전 관측을 재구독에서 재방출하면 만료된 위험 상태가 무기한 유지된다 (bounded replay 정책).
     */
    @Test
    fun `expired banking observation is not replayed on resubscription`() = runTest {
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue(emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        fakeClock.advanceMs(POLL_MS + 1_000L + 1L) // 관측 유효 지평(6초) 초과
        provider.current = 3L // 만료 중립은 캐시 epoch이 아니라 현재 epoch를 받아야 한다
        statsResult = null
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()

        assertEquals(1, emissionsB.size)
        assertFalse(
            "지평을 벗어난 관측은 replay 금지 — 중립값 (회복 시 edge는 진짜 신규)",
            emissionsB[0].value,
        )
        assertEquals("만료 중립의 스탬프는 현재 epoch", 3L, emissionsB[0].producedAtEpoch)

        // 공백 후 회복 — 만료가 이후의 실제 관측을 막지 않고, 진짜 신규 edge로 재방출된다.
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        nextPoll()
        assertEquals(2, emissionsB.size)
        assertTrue("회복 관측은 정상 재방출", emissionsB[1].value)
        assertEquals(3L, emissionsB[1].producedAtEpoch)
        jobB.cancel()
    }

    /** 라운드 13-② (APP 축): DETECTION_WINDOW(30초)를 벗어난 관측은 replay하지 않는다. */
    @Test
    fun `expired app observation is not replayed on resubscription`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        fakeClock.advanceMs(30_000L + 1L) // DETECTION_WINDOW_MS 초과
        provider.current = 3L // 만료 중립은 현재 epoch를 받아야 한다
        statsResult = null
        val (emissionsB, jobB) = collectApp(monitor)
        assertEquals(1, emissionsB.size)
        assertTrue(
            "지평을 벗어난 관측은 replay 금지 — 중립값",
            emissionsB[0].value.isEmpty(),
        )
        assertEquals("만료 중립의 스탬프는 현재 epoch", 3L, emissionsB[0].producedAtEpoch)

        // 공백 후 회복: 성공한 부재 관측(gate 개방) 후 재등장 — fresh epoch로 정상 재방출.
        statsResult = listOf(staleApp())
        nextPoll()
        statsResult = listOf(recentApp("com.teamviewer.host"))
        nextPoll()
        assertEquals(2, emissionsB.size)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsB[1].value)
        assertEquals(3L, emissionsB[1].producedAtEpoch)
        jobB.cancel()
    }

    /**
     * 라운드 14-②: replay 지평의 시간 기준은 **조회 시작 시각**이다 — 완료 시각 기준이면
     * blocking 조회가 오래 걸릴수록, 조회 시작 때 이미 확정된 데이터가 소요 시간만큼
     * 유효기간을 연장받는다.
     */
    @Test
    fun `slow banking query must not extend the replay horizon past the query start`() = runTest {
        var slowQueryDone = false
        every { usm.queryUsageStats(any(), any(), any()) } answers {
            if (!slowQueryDone) {
                slowQueryDone = true
                fakeClock.advanceMs(10_000L) // blocking 조회가 지평(6초)보다 오래 걸린다
            }
            statsResult
        }
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue(emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        // 조회 완료 직후 재구독 — 완료 시각 기준 age 0(fresh 위장), 시작 시각 기준 age 10초(만료).
        statsResult = null
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()
        assertEquals(1, emissionsB.size)
        assertFalse("조회 시작 기준 지평(6초)을 넘긴 관측은 replay 금지", emissionsB[0].value)
        jobB.cancel()
    }

    /** 라운드 14-② (APP 축): 조회 소요 시간이 관측의 유효 지평을 연장하면 안 된다. */
    @Test
    fun `slow app query must not extend the replay horizon past the query start`() = runTest {
        var slowQueryDone = false
        every { usm.queryUsageStats(any(), any(), any()) } answers {
            if (!slowQueryDone) {
                slowQueryDone = true
                fakeClock.advanceMs(40_000L) // blocking 조회가 지평(30초)보다 오래 걸린다
            }
            statsResult
        }
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        statsResult = null
        val (emissionsB, jobB) = collectApp(monitor)
        assertEquals(1, emissionsB.size)
        assertTrue("조회 시작 기준 지평(30초)을 넘긴 관측은 replay 금지", emissionsB[0].value.isEmpty())
        jobB.cancel()
    }

    /**
     * 라운드 14-② (rollback): 시계가 뒤로 이동해 age가 음수가 되어도 fresh로 인정하면 안 된다 —
     * 유효 구간은 폐구간 0..지평 (수정: replay 축은 monotonic 시계 + 하한 방어).
     */
    @Test
    fun `clock rollback must not make an old observation fresh again`() = runTest {
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue(emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        fakeClock.advanceMs(-60_000L) // 수동 시간 변경 등으로 시계 역행
        statsResult = null
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()
        assertEquals(1, emissionsB.size)
        assertFalse("음수 age는 fresh가 아니다 — 중립값", emissionsB[0].value)
        jobB.cancel()
    }

    /** 라운드 14 권장: 지평 경계(정확히 6초)의 관측은 여전히 replay된다 — 유효 구간은 폐구간. */
    @Test
    fun `banking observation exactly at the horizon boundary is still replayed`() = runTest {
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue(emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        fakeClock.advanceMs(POLL_MS + 1_000L) // 정확히 지평(6초)
        statsResult = null
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()
        assertEquals(1, emissionsB.size)
        assertTrue("경계값(age == 지평)은 replay 허용", emissionsB[0].value)
        assertEquals("원래 관측 epoch 유지", 0L, emissionsB[0].producedAtEpoch)
        jobB.cancel()
    }

    /**
     * 라운드 15-④: 교체된(superseded) collector는 Unknown 첫 tick에서도 replay를 방출하면
     * 안 된다 — blocking 조회 도중 신세대가 등록되는 인터리브를 answers 훅으로 결정론 재현.
     * replay 선택·방출 권한은 등록과 같은 원자 상태의 단일 스냅숏으로 결정되어야 한다.
     */
    @Test
    fun `a superseded collector must not emit an unknown replay`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor) // 실관측 [REMOTE] 캐시 확보
        assertEquals(1, emissionsA.size)
        jobA.cancel()
        runCurrent()

        statsResult = null
        var interleaved = false
        every { usm.queryUsageStats(any(), any(), any()) } answers {
            if (!interleaved) {
                interleaved = true
                monitor.registerAppCollector() // B의 blocking 조회 도중 신세대 등록
            }
            statsResult
        }
        val (emissionsB, jobB) = collectApp(monitor)
        assertTrue("경합이 실제 발생했음", interleaved)
        assertEquals("교체된 collector는 replay를 방출하면 안 됨", 0, emissionsB.size)
        jobB.cancel()
    }

    /** 라운드 16 권장 (BANKING 대칭): 교체된 collector는 Unknown 첫 tick replay도 방출하지 않는다. */
    @Test
    fun `a superseded banking collector must not emit an unknown replay`() = runTest {
        statsResult = listOf(recentApp("com.kakaobank.channel"))
        val monitor = newMonitor()
        val emissionsA = mutableListOf<Produced<Boolean>>()
        val jobA = launch { monitor.observeBankingAppForeground().collect { emissionsA += it } }
        runCurrent()
        assertTrue(emissionsA[0].value) // 실관측 캐시 확보
        jobA.cancel()
        runCurrent()

        statsResult = null
        var interleaved = false
        every { usm.queryUsageStats(any(), any(), any()) } answers {
            if (!interleaved) {
                interleaved = true
                monitor.registerBankingCollector() // B의 blocking 조회 도중 신세대 등록
            }
            statsResult
        }
        val emissionsB = mutableListOf<Produced<Boolean>>()
        val jobB = launch { monitor.observeBankingAppForeground().collect { emissionsB += it } }
        runCurrent()
        assertTrue("경합이 실제 발생했음", interleaved)
        assertEquals("교체된 collector는 replay를 방출하면 안 됨", 0, emissionsB.size)
        jobB.cancel()
    }

    /** 라운드 14 권장 (APP 축): 경계(정확히 30초)의 관측은 replay된다. */
    @Test
    fun `app observation exactly at the horizon boundary is still replayed`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsA[0].value)
        jobA.cancel()
        runCurrent()

        fakeClock.advanceMs(30_000L) // 정확히 DETECTION_WINDOW_MS
        statsResult = null
        val (emissionsB, jobB) = collectApp(monitor)
        assertEquals(1, emissionsB.size)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsB[0].value)
        assertEquals(0L, emissionsB[0].producedAtEpoch)
        jobB.cancel()
    }

    /**
     * 라운드 12-②·15-③: generation 검사(`!=` 소유권 계약)·epoch 검사·gate 전이·신호 산출·
     * replay 캐시 기록이 **단일 CAS**로 원자 결정됨을 고정한다. 검사-후-적용 창(두 문장
     * 사이에 새 collector가 끼는 창)은 코루틴 테스트로 재현이 불가능하므로, 적용 함수
     * 수준에서 적대적 순서를 직접 구성해 invariant를 고정한다 (RED-first 아님 —
     * invariant-pinning, 라운드 12 선례).
     */
    @Test
    fun `generation-aware CAS rejects stale collectors and older epochs atomically`() = runTest {
        val monitor = newMonitor()
        val now = fakeClock.provider()

        val gen1 = monitor.registerAppCollector()
        val gen2 = monitor.registerAppCollector() // 신세대 등록 — gen1은 이 시점부터 소유권 상실

        // 신세대(gen2)의 관측: epoch 1에서 원격앱 → 격리(absent=false) + 캐시 동반 커밋.
        provider.current = 1L
        val applied = monitor.applyAppObservation(
            generation = gen2, epoch = 1L, packages = setOf("com.teamviewer.host"),
            nowMs = now, observedAtMs = now,
        )
        assertTrue(applied is RealAppUsageRiskMonitor.AppObservationOutcome.Applied)
        assertTrue(
            "격리 중 신호 없음 — Applied가 방출 허가값을 나른다",
            (applied as RealAppUsageRiskMonitor.AppObservationOutcome.Applied).produced.value.isEmpty(),
        )
        val after = monitor.gateStateForTest()
        assertEquals(gen2, after.activeGeneration)
        assertEquals(1L, after.lastObservedEpoch)
        assertFalse("최초 관측 epoch > 0 → gate closed", after.remoteAbsentObservedInCurrentEpoch)
        assertEquals("캐시가 같은 CAS로 커밋", applied.produced, after.cachedObservation?.produced)

        // 구세대(gen1)의 늦은 관측 — 부재로 gate를 열려는 시도: 원자 거부, gate·캐시 모두 불변.
        val superseded = monitor.applyAppObservation(
            generation = gen1, epoch = 0L, packages = emptySet(), nowMs = now, observedAtMs = now,
        )
        assertEquals(RealAppUsageRiskMonitor.AppObservationOutcome.Superseded, superseded)
        assertEquals("gate·캐시 불변", after, monitor.gateStateForTest())

        // 같은(최신) 세대라도 더 오래된 epoch의 관측은 거부 — gate 개방·앵커·캐시 조작 불가.
        val staleEpoch = monitor.applyAppObservation(
            generation = gen2, epoch = 0L, packages = emptySet(), nowMs = now, observedAtMs = now,
        )
        assertEquals(RealAppUsageRiskMonitor.AppObservationOutcome.StaleEpoch, staleEpoch)
        assertEquals("gate·캐시 불변", after, monitor.gateStateForTest())
    }

    /**
     * 라운드 15-④ (BANKING, invariant-pinning): 캐시 기록과 방출 권한이 같은 CAS로 결정됨을
     * 고정한다 — "검사 통과 후 기록 직전 신세대 등록" 창은 코루틴으로 재현 불가(라운드 12
     * 선례). commit 거부 = 방출 금지는 flow 경로의 단일 return으로 강제된다.
     */
    @Test
    fun `banking commit refuses a superseded generation and keeps the cache intact`() = runTest {
        val monitor = newMonitor()
        val now = fakeClock.provider()

        val gen1 = monitor.registerBankingCollector()
        assertTrue(
            "소유 세대의 commit은 허가",
            monitor.commitBankingObservation(Produced(true, 0L), now, gen1),
        )

        monitor.registerBankingCollector() // 신세대 등록 — gen1 소유권 상실
        assertFalse(
            "교체된 세대의 늦은 commit은 원자 거부 (방출도 금지)",
            monitor.commitBankingObservation(Produced(false, 0L), now, gen1),
        )

        // 거부가 캐시를 오염시키지 않았음을 재구독 replay로 관측 — 마지막 유효 관측(true) 유지.
        statsResult = null
        val emissions = mutableListOf<Produced<Boolean>>()
        val job = launch { monitor.observeBankingAppForeground().collect { emissions += it } }
        runCurrent()
        assertEquals(1, emissions.size)
        assertTrue("gen1의 마지막 유효 관측이 replay되어야 함", emissions[0].value)
        job.cancel()
    }

    @Test
    fun `quarantine survives resubscription at the same epoch`() = runTest {
        statsResult = listOf(recentApp("com.teamviewer.host"))
        val monitor = newMonitor()
        val (emissionsA, jobA) = collectApp(monitor)
        assertEquals(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emissionsA[0].value)

        provider.current = 1L // reset → 격리
        nextPoll()
        jobA.cancel()
        runCurrent()

        // FGS 재구독 모사 — 같은 epoch에서 새 collector가 시작해도 monitor-수명 gate가 유지된다.
        val (emissionsB, jobB) = collectApp(monitor)
        assertEquals(1, emissionsB.size)
        assertTrue(
            "재구독이 격리를 초기화하면 안 됨 — REMOTE 미방출",
            emissionsB[0].value.isEmpty(),
        )
        jobB.cancel()
    }
}
