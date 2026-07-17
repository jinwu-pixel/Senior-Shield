package com.example.seniorshield.monitoring.session

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.testutil.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class RiskSessionTrackerIdleExpiryTest {

    @Test
    fun `passive session expires only after the 30 minute boundary`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }

        assertNotNull(tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList()))

        clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS)
        assertFalse(tracker.expireIfTimedOut())
        assertNotNull(tracker.sessionState.value)

        clock.advanceMs(1L)
        assertTrue(tracker.expireIfTimedOut())
        assertNull(tracker.sessionState.value)
        assertFalse("expiry must be idempotent", tracker.expireIfTimedOut())
    }

    @Test
    fun `trigger session expires only after the 60 minute boundary`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }

        assertNotNull(
            tracker.update(
                callSignals = emptyList(),
                appSignals = listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            ),
        )

        clock.advanceMs(TRIGGER_IDLE_TIMEOUT_MS)
        assertFalse(tracker.expireIfTimedOut())
        assertNotNull(tracker.sessionState.value)

        clock.advanceMs(1L)
        assertTrue(tracker.expireIfTimedOut())
        assertNull(tracker.sessionState.value)
        assertFalse("expiry must be idempotent", tracker.expireIfTimedOut())
    }

    /** TTL 초과 + 겹침 없는 새 신호 → 부활이 아니라 fresh episode(새 ID) — 모든 호출자 공통 의미론. */
    @Test
    fun `stale session becomes a fresh episode when a non-overlapping signal arrives past TTL`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }

        val oldId = requireNotNull(tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())).id

        clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
        val outcome = tracker.transition(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        val fresh = requireNotNull(outcome.session)

        assertTrue("이전 세션 종료가 outcome에 보고돼야 함", outcome.expiredPrevious)
        assertFalse(outcome.renewed)
        assertNotEquals("만료 세션 부활 금지 — 새 세션 ID여야 함", oldId, fresh.id)
        assertEquals(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), fresh.accumulatedSignals)
    }

    /** TTL 초과 + 겹치는 latched 신호 → 같은 ID renewal + notified* 승계 (D1 개정). */
    @Test
    fun `stale session renews in place when the latched signal overlaps past TTL`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }

        val created = requireNotNull(tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList()))
        tracker.markAlertStateNotified(com.example.seniorshield.domain.model.AlertState.GUARDED)

        clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)
        val outcome = tracker.transition(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        val renewed = requireNotNull(outcome.session)

        assertTrue(outcome.renewed)
        assertFalse(outcome.expiredPrevious)
        assertEquals("renewal은 같은 세션 ID", created.id, renewed.id)
        assertTrue("TTL 창 rebase", renewed.lastSignalAt > created.lastSignalAt)
        assertEquals(
            "통보 메타데이터 승계 — 재알림이 자동 발동되지 않는 근거",
            com.example.seniorshield.domain.model.AlertState.GUARDED,
            renewed.notifiedAlertState,
        )
    }

    /** 락 내 원자 epoch 비교 — reset 이전에 생산된(stale epoch) 호출은 상태를 건드리지 못한다. */
    @Test
    fun `transition with a stale expected epoch aborts without touching state`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val staleEpoch = tracker.userResetEpoch

        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        tracker.reset() // 사용자 리셋: epoch 전진 + 세션 종료

        val outcome = tracker.transition(
            listOf(RiskSignal.UNKNOWN_CALLER),
            emptyList(),
            expectedEpoch = staleEpoch,
        )

        assertTrue("stale epoch 호출은 aborted", outcome.aborted)
        assertNull("사용자 리셋 결과가 유지되어야 함(재생성 금지)", tracker.sessionState.value)
    }

    /** 부분 소멸 rebase: 소멸 몫만 걷어내고, 남는 근거가 없으면 세션을 종료한다. */
    @Test
    fun `rebase removes lost signals and expires when nothing sustains the session`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val session = requireNotNull(
            tracker.update(
                listOf(RiskSignal.UNKNOWN_CALLER),
                listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            ),
        )

        // 다른 id는 no-op
        val untouched = tracker.rebaseRenewedSession("other-id", setOf(RiskSignal.UNKNOWN_CALLER))
        assertEquals(session.id, untouched?.id)
        assertEquals(session.accumulatedSignals, untouched?.accumulatedSignals)

        val rebased = requireNotNull(
            tracker.rebaseRenewedSession(session.id, setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)),
        )
        assertEquals(session.id, rebased.id)
        assertEquals(setOf(RiskSignal.UNKNOWN_CALLER), rebased.accumulatedSignals)
        assertFalse("trigger 소멸 시 hasTrigger 재계산", rebased.hasTrigger)
        assertEquals("rebase는 새 신호가 아니다 — lastSignalAt 불변", session.lastSignalAt, rebased.lastSignalAt)

        assertNull(
            "남는 근거가 없으면 종료",
            tracker.rebaseRenewedSession(session.id, setOf(RiskSignal.UNKNOWN_CALLER)),
        )
        assertNull(tracker.sessionState.value)
    }

    /** 근거 전멸 분리: 잔존 신호는 새 ID·빈 통보 메타데이터의 fresh episode가 된다. */
    @Test
    fun `split creates a fresh episode when survivors can sustain a session`() {
        val clock = FakeClock(now = 1_000_000L)
        val tracker = RiskSessionTracker().also { it.clock = clock.provider }
        val session = requireNotNull(
            tracker.update(
                listOf(RiskSignal.UNKNOWN_CALLER),
                listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
            ),
        )
        tracker.markAlertStateNotified(com.example.seniorshield.domain.model.AlertState.CRITICAL)

        val fresh = requireNotNull(
            tracker.splitAfterRenewalBasisDied(session.id, setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)),
        )
        assertNotEquals("fresh episode는 새 ID", session.id, fresh.id)
        assertEquals("live 신호만으로 구성 — 과거 누적값 부활 금지", setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), fresh.accumulatedSignals)
        assertNull("통보 메타데이터 미승계 — 새 위협은 정상 알림 경로", fresh.notifiedAlertState)
        assertTrue(fresh.hasTrigger)

        assertNull(
            "live 신호가 세션을 만들 수 없으면 종료만",
            tracker.splitAfterRenewalBasisDied(fresh.id, emptySet()),
        )
        assertNull(tracker.sessionState.value)
    }

    /** 사용자 주도 리셋은 userResetEpoch를 즉시 증가시켜 진행 중 tick의 잔여 효과를 무효화한다. */
    @Test
    fun `user resets increment the reset epoch`() {
        val tracker = RiskSessionTracker().also { it.clock = { 1_000_000L } }
        val before = tracker.userResetEpoch

        tracker.reset()
        assertEquals(before + 1, tracker.userResetEpoch)

        tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
        tracker.resetAfterUserConfirmedSafe()
        assertEquals(before + 2, tracker.userResetEpoch)

        // 시스템 주도 만료/무효화는 사용자 epoch를 건드리지 않는다
        val id = requireNotNull(tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())).id
        tracker.expireNowIfCurrent(id, "test")
        assertEquals(before + 2, tracker.userResetEpoch)
    }

    /**
     * expireIfTimedOut(IO lane)과 update(main thread)가 경합해도 stale expire가 방금 만든
     * 세션을 지우지 못한다 — @Synchronized 직렬화로 두 실행 순서 모두 새 세션이 살아남는다.
     */
    @Test
    fun `concurrent expireIfTimedOut cannot clobber a session freshly created by update`() {
        repeat(200) {
            val clock = FakeClock(now = 1_000_000L)
            val tracker = RiskSessionTracker().also { it.clock = clock.provider }
            tracker.update(listOf(RiskSignal.UNKNOWN_CALLER), emptyList())
            clock.advanceMs(DEFAULT_IDLE_TIMEOUT_MS + 1L)

            val barrier = CyclicBarrier(2)
            val errors = mutableListOf<Throwable>()
            val expirer = thread {
                barrier.await()
                runCatching { tracker.expireIfTimedOut() }.onFailure { synchronized(errors) { errors += it } }
            }
            val updater = thread {
                barrier.await()
                runCatching { tracker.update(emptyList(), listOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)) }
                    .onFailure { synchronized(errors) { errors += it } }
            }
            expirer.join()
            updater.join()

            assertEquals("스레드 내부 예외 없음: $errors", emptyList<Throwable>(), errors)
            val survivor = tracker.sessionState.value
            assertNotNull("어느 순서로 직렬화돼도 새 세션은 살아있어야 함", survivor)
            assertEquals(setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED), survivor?.accumulatedSignals)
        }
    }
}
