/**
 * RiskSessionTracker 단위 테스트
 *
 * ## 테스트 범위 제한
 * 타임아웃(SESSION_IDLE_TIMEOUT_MS = 30분) 만료 경로는 이 파일에서 검증하지 않는다.
 * [RiskSessionTracker.update] 내부에서 `System.currentTimeMillis()`를 직접 호출하기 때문에
 * 외부에서 시각을 주입하거나 조작할 수 없다. 타임아웃 경로를 테스트하려면
 * RiskSessionTracker를 시계(Clock) 주입 형태로 리팩터링해야 한다.
 */
package com.example.seniorshield.monitoring.session

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RiskSessionTrackerTest {

    private lateinit var tracker: RiskSessionTracker

    @Before
    fun setUp() {
        tracker = RiskSessionTracker()
    }

    // -----------------------------------------------------------------------
    // 1. 신호 없고 세션 없으면 null 반환
    // -----------------------------------------------------------------------

    @Test
    fun `신호 없고 세션 없으면 null 반환`() {
        val result = tracker.update(emptyList(), emptyList())

        assertNull(result)
        assertNull(tracker.sessionState.value)
    }

    // -----------------------------------------------------------------------
    // 2. 첫 신호가 들어오면 세션 생성
    // -----------------------------------------------------------------------

    @Test
    fun `첫 신호가 들어오면 세션 생성`() {
        val result = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())

        assertNotNull(result)
        assertTrue(result!!.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
    }

    // -----------------------------------------------------------------------
    // 3. 동일 신호 재전달 시 누적 변화 없음
    // -----------------------------------------------------------------------

    @Test
    fun `동일 신호 재전달 시 누적 변화 없음`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        val result = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())

        assertNotNull(result)
        assertEquals(1, result!!.accumulatedSignals.size)
        assertTrue(result.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
    }

    // -----------------------------------------------------------------------
    // 4. 새로운 신호 추가 시 누적됨
    // -----------------------------------------------------------------------

    @Test
    fun `새로운 신호 추가 시 누적됨`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        val result = tracker.update(listOf(RiskSignal.LONG_CALL_DURATION), emptyList())

        assertNotNull(result)
        assertEquals(2, result!!.accumulatedSignals.size)
        assertTrue(result.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
        assertTrue(result.accumulatedSignals.contains(RiskSignal.LONG_CALL_DURATION))
    }

    // -----------------------------------------------------------------------
    // 5. 빈 신호 전달 시 세션 유지 (타임아웃 전)
    // -----------------------------------------------------------------------

    @Test
    fun `빈 신호 전달 시 세션 유지 타임아웃 전`() {
        // 세션을 먼저 생성한다.
        val first = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(first)

        // 바로 빈 신호를 전달한다. 30분 타임아웃이 지나지 않았으므로 세션은 유지돼야 한다.
        val result = tracker.update(emptyList(), emptyList())

        assertNotNull(result)
        assertEquals(first!!.id, result!!.id)
    }

    // -----------------------------------------------------------------------
    // 6. markNotified 호출 시 notifiedLevel 갱신
    // -----------------------------------------------------------------------

    @Test
    fun `markNotified 호출 시 notifiedLevel 갱신`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        tracker.markNotified(RiskLevel.HIGH)

        val session = tracker.sessionState.value
        assertNotNull(session)
        assertEquals(RiskLevel.HIGH, session!!.notifiedLevel)
    }

    // -----------------------------------------------------------------------
    // 7. markInterrupterShown 호출 시 플래그 갱신
    // -----------------------------------------------------------------------

    @Test
    fun `markInterrupterShown 호출 시 bankingInterrupterShown 플래그가 true로 갱신됨`() {
        tracker.update(listOf(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP), emptyList())
        tracker.markInterrupterShown()

        val session = tracker.sessionState.value
        assertNotNull(session)
        assertTrue(session!!.bankingInterrupterShown)
    }

    // -----------------------------------------------------------------------
    // 8. reset 호출 시 세션 초기화
    // -----------------------------------------------------------------------

    @Test
    fun `reset 호출 시 세션 초기화`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(tracker.sessionState.value)

        tracker.reset()

        assertNull(tracker.sessionState.value)
    }

    // -----------------------------------------------------------------------
    // 9. sessionState에서 세션 상태 관찰 가능
    // -----------------------------------------------------------------------

    @Test
    fun `sessionState에서 세션 상태 관찰 가능`() {
        assertNull(tracker.sessionState.value)

        tracker.update(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emptyList())

        val state = tracker.sessionState.value
        assertNotNull(state)
        assertTrue(state!!.accumulatedSignals.contains(RiskSignal.REMOTE_CONTROL_APP_OPENED))
    }

    // -----------------------------------------------------------------------
    // 10. callSignals와 appSignals 모두 합산
    // -----------------------------------------------------------------------

    @Test
    fun `callSignals와 appSignals 모두 합산`() {
        val result = tracker.update(
            callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
            appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )

        assertNotNull(result)
        assertEquals(2, result!!.accumulatedSignals.size)
        assertTrue(result.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
        assertTrue(result.accumulatedSignals.contains(RiskSignal.REMOTE_CONTROL_APP_OPENED))
    }
}
