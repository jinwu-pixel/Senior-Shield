package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.AlertState
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.session.RiskSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── 커버리지 보완 ─────────────────────────────────────────────────

    @Test
    fun `TELEBANKING_AFTER_SUSPICIOUS 단독 TRIGGER 세션 - INTERRUPT`() {
        // call 신호 없는 세션에서 TELEBANKING 단독 발생 → INTERRUPT
        val session = session(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS)
        assertEquals(AlertState.INTERRUPT, resolver.resolve(session))
    }

    @Test
    fun `BANKING_APP_OPENED_AFTER_REMOTE_APP 단독 TRIGGER - INTERRUPT`() {
        // REMOTE_CONTROL_APP_OPENED 없이 BANKING_APP 단독 → REMOTE+BANKING 콤보 미성립 → INTERRUPT
        val session = session(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP)
        assertEquals(AlertState.INTERRUPT, resolver.resolve(session))
    }

    @Test
    fun `SUSPICIOUS_APP_INSTALLED + TELEBANKING_AFTER_SUSPICIOUS no call - INTERRUPT`() {
        // call 신호 없는 세션에서 두 TRIGGER가 존재해도 REMOTE+BANKING 콤보가 아니면 INTERRUPT
        val session = session(
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
            RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
        )
        assertEquals(AlertState.INTERRUPT, resolver.resolve(session))
    }

    /**
     * UNVERIFIED_CALLER only session은 call-based가 아니므로 banking cooldown 미발동
     *
     * 설계 결정 명문화:
     * AlertStateResolver.CALL_SIGNALS에는 UNVERIFIED_CALLER가 포함되어 있다.
     * 따라서 UNVERIFIED_CALLER 단독 세션은 call-based 세션으로 분류된다.
     * 단, TRIGGER가 없으면 isHighConfidenceCombo 조건이 충족되지 않아 GUARDED를 반환한다.
     * banking cooldown(TRIGGER 발동 조건)은 이 세션에서 활성화되지 않는다.
     */
    @Test
    fun `UNVERIFIED_CALLER only session은 call-based가 아니므로 banking cooldown 미발동`() {
        // UNVERIFIED_CALLER는 CALL_SIGNALS 멤버 — call-based 세션으로 분류됨
        assertTrue(
            "UNVERIFIED_CALLER는 AlertStateResolver.CALL_SIGNALS에 포함된다",
            RiskSignal.UNVERIFIED_CALLER in AlertStateResolver.CALL_SIGNALS,
        )
        val session = session(RiskSignal.UNVERIFIED_CALLER)
        // TRIGGER 없음 → 고신뢰 콤보 미성립 → GUARDED (banking cooldown 미발동)
        assertEquals(AlertState.GUARDED, resolver.resolve(session))
    }

    @Test
    fun `UNVERIFIED_CALLER + TRIGGER 조합 - CRITICAL`() {
        // UNVERIFIED_CALLER는 CALL_SIGNALS에 포함 → call-based 세션.
        // call-based 세션 + TRIGGER → isHighConfidenceCombo 성립 → CRITICAL
        val session = session(
            RiskSignal.UNVERIFIED_CALLER,
            RiskSignal.SUSPICIOUS_APP_INSTALLED,
        )
        assertEquals(AlertState.CRITICAL, resolver.resolve(session))
    }
}
