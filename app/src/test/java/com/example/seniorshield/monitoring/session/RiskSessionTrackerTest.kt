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
import org.junit.Assert.assertFalse
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
    // 7. reset 호출 시 세션 초기화
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

    // -----------------------------------------------------------------------
    // 11. P0.5 신호 누적 — 텔레뱅킹 유도 시나리오
    // -----------------------------------------------------------------------

    @Test
    fun `텔레뱅킹 시나리오 신호 단계별 누적`() {
        // Phase 1: 의심 통화
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        // Phase 2: 반복 호출 + 장시간 통화
        tracker.update(
            listOf(RiskSignal.REPEATED_UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION, RiskSignal.REPEATED_CALL_THEN_LONG_TALK),
            emptyList(),
        )
        // Phase 3: 텔레뱅킹
        val session = tracker.update(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS), emptyList())

        assertNotNull(session)
        assertEquals(5, session!!.accumulatedSignals.size)
        assertTrue(session.accumulatedSignals.contains(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
        assertTrue(session.accumulatedSignals.contains(RiskSignal.REPEATED_UNKNOWN_CALLER))
    }

    // -----------------------------------------------------------------------
    // 12. markActiveThreatsNotified 후 새 위협 추가 시 diff 감지
    // -----------------------------------------------------------------------

    @Test
    fun `markActiveThreatsNotified 후 새 위협 추가 감지`() {
        tracker.update(listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), emptyList())
        tracker.markActiveThreatsNotified(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))

        // 새 위협 추가
        tracker.update(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS), emptyList())

        val session = tracker.sessionState.value
        assertNotNull(session)
        assertNotNull(session)
        // 기존 알림 세트에는 REMOTE_CONTROL만 있으므로 TELEBANKING은 새 위협
        assertFalse(session!!.notifiedActiveThreats.contains(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
        assertTrue(session.accumulatedSignals.contains(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
    }

    // -----------------------------------------------------------------------
    // 13. HIGH_RISK_DEVICE_ENVIRONMENT 단독으로는 세션 미생성
    // -----------------------------------------------------------------------

    @Test
    fun `HIGH_RISK_DEVICE_ENVIRONMENT 단독 세션 미생성`() {
        val result = tracker.update(
            emptyList(),
            listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT),
        )
        assertNull(result)
        assertNull(tracker.sessionState.value)
    }

    @Test
    fun `HIGH_RISK_DEVICE_ENVIRONMENT + 다른 신호 동시 도착 시 세션 생성`() {
        val result = tracker.update(
            listOf(RiskSignal.UNKNOWN_CALLER),
            listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT),
        )
        assertNotNull(result)
        assertTrue(result!!.accumulatedSignals.contains(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT))
        assertTrue(result.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
    }

    // -----------------------------------------------------------------------
    // 14. hasTrigger 플래그
    // -----------------------------------------------------------------------

    @Test
    fun `PASSIVE 신호만 - hasTrigger false`() {
        val result = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(result)
        assertFalse(result!!.hasTrigger)
    }

    @Test
    fun `TRIGGER 신호 포함 - hasTrigger true`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        val result = tracker.update(
            emptyList(),
            listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        assertNotNull(result)
        assertTrue(result!!.hasTrigger)
    }

    @Test
    fun `hasTrigger 한 번 true이면 이후에도 true 유지`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        tracker.update(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        // PASSIVE만 추가해도 hasTrigger 유지
        val result = tracker.update(listOf(RiskSignal.LONG_CALL_DURATION), emptyList())
        assertNotNull(result)
        assertTrue(result!!.hasTrigger)
    }

    // -----------------------------------------------------------------------
    // 15. markAlertStateNotified
    // -----------------------------------------------------------------------

    @Test
    fun `markAlertStateNotified 정상 갱신`() {
        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        tracker.markAlertStateNotified(com.example.seniorshield.domain.model.AlertState.GUARDED)

        val session = tracker.sessionState.value
        assertNotNull(session)
        assertEquals(
            com.example.seniorshield.domain.model.AlertState.GUARDED,
            session!!.notifiedAlertState,
        )
    }

    // -----------------------------------------------------------------------
    // 커버리지 보완 — 세션 없는 상태에서 mark 메서드 호출
    // -----------------------------------------------------------------------

    @Test
    fun `세션 없는 상태에서 markNotified 호출 - 에러 없이 null 유지`() {
        assertNull(tracker.sessionState.value)
        tracker.markNotified(RiskLevel.HIGH)
        assertNull(tracker.sessionState.value)
    }

    @Test
    fun `세션 없는 상태에서 markAlertStateNotified 호출 - 에러 없이 null 유지`() {
        assertNull(tracker.sessionState.value)
        tracker.markAlertStateNotified(com.example.seniorshield.domain.model.AlertState.GUARDED)
        assertNull(tracker.sessionState.value)
    }

    @Test
    fun `세션 없는 상태에서 markActiveThreatsNotified 호출 - 에러 없이 null 유지`() {
        assertNull(tracker.sessionState.value)
        tracker.markActiveThreatsNotified(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        assertNull(tracker.sessionState.value)
    }

    // -----------------------------------------------------------------------
    // 커버리지 보완 — reset 후 재생성된 세션은 이전과 다른 UUID
    // -----------------------------------------------------------------------

    @Test
    fun `reset 후 신호 재입력 시 새 세션 UUID가 이전과 다름`() {
        val first = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(first)
        val firstId = first!!.id

        tracker.reset()
        assertNull(tracker.sessionState.value)

        val second = tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        assertNotNull(second)
        val secondId = second!!.id

        assertFalse("reset 후 새 세션 UUID는 이전과 달라야 한다", firstId == secondId)
    }

    // -----------------------------------------------------------------------
    // 커버리지 보완 — callSignals + appSignals 양쪽 동일 신호 중복 처리
    // -----------------------------------------------------------------------

    @Test
    fun `callSignals와 appSignals에 동일 신호 입력 시 accumulatedSignals 중복 없음`() {
        // UNKNOWN_CALLER를 callSignals와 appSignals 양쪽에 모두 전달
        val result = tracker.update(
            callSignals = listOf(RiskSignal.UNKNOWN_CALLER),
            appSignals = listOf(RiskSignal.UNKNOWN_CALLER),
        )
        assertNotNull(result)
        // Set 합산이므로 중복 제거 → 신호 1개만 누적
        assertEquals(1, result!!.accumulatedSignals.size)
        assertTrue(result.accumulatedSignals.contains(RiskSignal.UNKNOWN_CALLER))
    }
}
