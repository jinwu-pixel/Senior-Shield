package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.session.RiskSession
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertStateResolverTest {

    private val resolver = AlertStateResolver()

    private fun session(vararg signals: RiskSignal): RiskSession {
        val now = System.currentTimeMillis()
        return RiskSession(
            startedAt = now,
            accumulatedSignals = signals.toSet(),
            lastSignalAt = now,
            hasTrigger = signals.any {
                it.category == com.example.seniorshield.domain.model.SignalCategory.TRIGGER
            },
        )
    }

    // ── OBSERVE ──────────────────────────────────────────────────────

    @Test
    fun `세션 없음 - OBSERVE`() {
        assertEquals(AlertState.OBSERVE, resolver.resolve(null))
    }

    @Test
    fun `HIGH_RISK_DEVICE_ENVIRONMENT 단독 세션 - Resolver는 GUARDED 반환 (SessionTracker가 생성 차단)`() {
        // SessionTracker가 env-only 세션 생성을 차단하는 것이 주 방어선.
        // Resolver 단독으로는 세션이 있으면 PASSIVE이므로 GUARDED를 반환한다.
        val session = session(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    // ── GUARDED ─────────────────────────────────────────────────────

    @Test
    fun `PASSIVE 단독 - GUARDED`() {
        val session = session(RiskSignal.UNKNOWN_CALLER)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `PASSIVE 복수 - GUARDED`() {
        val session = session(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `PASSIVE + AMPLIFIER - GUARDED`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
        )
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `REPEATED_UNKNOWN_CALLER 단독 - GUARDED`() {
        val session = session(RiskSignal.REPEATED_UNKNOWN_CALLER)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `AMPLIFIER 단독 - GUARDED`() {
        val session = session(RiskSignal.REPEATED_CALL_THEN_LONG_TALK)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    // ── INTERRUPT ───────────────────────────────────────────────────

    @Test
    fun `SUSPICIOUS_APP_INSTALLED 단독 - INTERRUPT`() {
        val session = session(RiskSignal.SUSPICIOUS_APP_INSTALLED)
        assertEquals(AlertState.INTERRUPT, resolver.resolve(session))
    }

    @Test
    fun `REMOTE_CONTROL 단독 - INTERRUPT`() {
        // 단독 trigger without call signals → INTERRUPT (not CRITICAL)
        val session = session(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        assertEquals(AlertState.INTERRUPT, resolver.resolve(session))
    }

    @Test
    fun `suspicious call + SUSPICIOUS_APP_INSTALLED - CRITICAL`() {
        // call-based 세션 + 임의의 trigger → CRITICAL
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    // ── CRITICAL ────────────────────────────────────────────────────

    @Test
    fun `suspicious call + REMOTE_CONTROL - CRITICAL`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `suspicious call + TELEBANKING - CRITICAL`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `REMOTE_CONTROL + BANKING_APP - CRITICAL`() {
        val session = session(
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `반복호출 + 원격제어 - CRITICAL`() {
        val session = session(
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `반복호출 + 텔레뱅킹 - CRITICAL`() {
        val session = session(
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `전체 사기 시나리오 - CRITICAL`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    // ── CRITICAL 금지 케이스 ────────────────────────────────────────

    @Test
    fun `REPEATED_CALL_THEN_LONG_TALK + TRIGGER - CRITICAL (CALL_SIGNALS에 포함)`() {
        val session = session(
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }

    @Test
    fun `REPEATED_CALL_THEN_LONG_TALK 단독 - CRITICAL 아님`() {
        val session = session(RiskSignal.REPEATED_CALL_THEN_LONG_TALK)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `PASSIVE 누적만 - CRITICAL 아님`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            RiskSignal.UNVERIFIED_CALLER,
            RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT,
            RiskSignal.REPEATED_UNKNOWN_CALLER,
            RiskSignal.REPEATED_CALL_THEN_LONG_TALK,
        )
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `HIGH_RISK_DEVICE_ENVIRONMENT 포함이어도 trigger 없으면 GUARDED`() {
        val session = session(
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT,
        )
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }
}
