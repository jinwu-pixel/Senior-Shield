package com.example.seniorshield.monitoring.call

import android.content.Context
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.session.RiskSession
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import com.example.seniorshield.testutil.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RealCallRiskMonitor] 실물 단위 테스트.
 *
 * ## 하네스
 * 6 협력자는 전부 `mockk(relaxed = true)`. 본 3 테스트가 검증하는 시간-의존 헬퍼
 * (`recordUnknownCall`/`isRepeatedUnknownCaller`/`isTelebankingWindow`)는 context·mapper·
 * contactChecker·settings·bankArs를 쓰지 않으므로 단순 relaxed로 충분하다. Android Looper/Main
 * 의존이 없어(생성자 inert, `sharedCallContext`는 lazy) Robolectric 없이 plain JVM에서 실물
 * 인스턴스를 직접 생성할 수 있다.
 *
 * ## 시간 처리
 * [FakeClock]을 `monitor.clock`에 주입해 `clock()` 기반 윈도우 경계(반복 호출 30분 /
 * 텔레뱅킹 5분)를 결정론적으로 검증한다. 헬퍼는 clock seam 도입으로 주입 가능해졌고,
 * [VisibleForTesting][androidx.annotation.VisibleForTesting] internal로 직접 호출한다(리플렉션 없음).
 *
 * ## sessionState 주의 (T-P2)
 * `recordUnknownCall()`은 `sessionTracker.sessionState.value == null`이면 버퍼를 비운다
 * (안전 확인 후 클린 슬레이트). relaxed mock의 기본 `sessionState`는 null을 반환하므로,
 * 반복 누적을 검증하려면 [stubActiveSession]으로 비-null 세션을 주입해야 한다.
 */
class RealCallRiskMonitorTest {

    private val context = mockk<Context>(relaxed = true)
    private val mapper = mockk<CallSignalMapper>(relaxed = true)
    private val contactChecker = mockk<CallerContactChecker>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val bankArsRegistry = mockk<BankArsRegistry>(relaxed = true)
    private val sessionTracker = mockk<RiskSessionTracker>(relaxed = true)
    private val fakeClock = FakeClock(now = 1_000_000L)

    private val monitor = RealCallRiskMonitor(
        context, mapper, contactChecker, settingsRepository, bankArsRegistry, sessionTracker,
    ).apply { clock = fakeClock.provider }

    /** 활성 세션 stub — `recordUnknownCall`이 버퍼를 비우지 않도록 비-null 세션을 주입. */
    private fun stubActiveSession() {
        every { sessionTracker.sessionState } returns MutableStateFlow<RiskSession?>(mockk<RiskSession>(relaxed = true))
    }

    /** T-P2-1: 미확인 호출이 30분 윈도우 내 2회면 반복으로 감지된다. */
    @Test
    fun `미확인 호출 30분 윈도우 내 2회는 반복으로 감지`() {
        stubActiveSession()

        monitor.recordUnknownCall()                    // T0 = 1_000_000
        fakeClock.advanceMs(29 * 60 * 1000L)           // +29분 (윈도우 내)
        monitor.recordUnknownCall()                    // 버퍼 2건 (둘 다 cutoff 이내)

        assertTrue("30분 내 2회 → 반복", monitor.isRepeatedUnknownCaller())
    }

    /** T-P2-2: 두 미확인 호출이 30분을 초과하면 첫 건이 만료되어 반복이 아니다. */
    @Test
    fun `미확인 호출 30분 초과 시 첫 건 만료로 반복 아님`() {
        stubActiveSession()

        monitor.recordUnknownCall()                        // T0 = 1_000_000
        fakeClock.advanceMs(30 * 60 * 1000L + 1000L)       // +30분 1초 (윈도우 초과)
        monitor.recordUnknownCall()                        // 첫 건 cutoff 밖 → 제거, 버퍼 1건

        assertFalse("30분 초과 → 반복 아님", monitor.isRepeatedUnknownCaller())
    }

    /** T-P1-1: 텔레뱅킹 윈도우는 의심 통화 종료 5분 이내 true, 초과 시 false. */
    @Test
    fun `텔레뱅킹 윈도우 5분 이내 true, 초과 시 false`() {
        monitor.lastSuspiciousCallEndedAt = 1_000_000L     // 의심 통화 종료 시각

        fakeClock.advanceMs(4 * 60 * 1000L + 59 * 1000L)   // now = 1_299_000 (4분 59초)
        assertTrue("4분 59초 → 윈도우 내", monitor.isTelebankingWindow())

        fakeClock.advanceMs(2000L)                          // now = 1_301_000 (5분 1초)
        assertFalse("5분 1초 → 윈도우 만료", monitor.isTelebankingWindow())
    }
}
