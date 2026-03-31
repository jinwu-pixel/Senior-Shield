package com.example.seniorshield.monitoring.event

import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RiskEventFactoryTest {

    private val factory = RiskEventFactory()

    private fun score(vararg signals: RiskSignal, level: RiskLevel = RiskLevel.HIGH) =
        RiskScore(total = 50, level = level, signals = signals.toList())

    @Test
    fun `BANKING_APP_OPENED_AFTER_REMOTE_APP - 원격제어 금융 앱 메시지`() {
        val event = factory.create(score(RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP, level = RiskLevel.CRITICAL))
        assertEquals("원격제어 중 금융 앱 실행 감지", event.title)
    }

    @Test
    fun `REMOTE_CONTROL_APP_OPENED + UNKNOWN_CALLER - 의심 통화 후 원격제어 메시지`() {
        val event = factory.create(score(RiskSignal.REMOTE_CONTROL_APP_OPENED, RiskSignal.UNKNOWN_CALLER))
        assertEquals("의심 통화 후 원격제어 앱 실행", event.title)
    }

    @Test
    fun `REMOTE_CONTROL_APP_OPENED 단독 - 원격제어 앱 실행 메시지`() {
        val event = factory.create(score(RiskSignal.REMOTE_CONTROL_APP_OPENED))
        assertEquals("원격제어 앱 실행 감지", event.title)
    }

    @Test
    fun `UNKNOWN_CALLER + LONG_CALL_DURATION - 장시간 의심 통화 메시지`() {
        val event = factory.create(score(RiskSignal.UNKNOWN_CALLER, RiskSignal.LONG_CALL_DURATION))
        assertEquals("장시간 의심 통화 감지", event.title)
    }

    @Test
    fun `BANKING보다 우선순위 높은 신호 없을 때 BANKING이 최우선`() {
        // BANKING_APP 과 다른 신호가 함께 있어도 BANKING 메시지
        val event = factory.create(score(
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
            RiskSignal.UNKNOWN_CALLER,
            RiskSignal.LONG_CALL_DURATION,
            level = RiskLevel.CRITICAL,
        ))
        assertEquals("원격제어 중 금융 앱 실행 감지", event.title)
    }

    @Test
    fun `이벤트마다 고유한 ID 발급`() {
        val s = score(RiskSignal.LONG_CALL_DURATION)
        val e1 = factory.create(s)
        val e2 = factory.create(s)
        assertNotEquals(e1.id, e2.id)
    }

    @Test
    fun `이벤트 레벨이 score 레벨과 일치`() {
        val event = factory.create(score(RiskSignal.LONG_CALL_DURATION, level = RiskLevel.CRITICAL))
        assertEquals(RiskLevel.CRITICAL, event.level)
    }
}
