package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallContext
import com.example.seniorshield.monitoring.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalMapperTest {

    private val mapper = CallSignalMapper()
    private val now = System.currentTimeMillis()

    private fun idleContext(
        startedAtMillis: Long? = now - 200_000,
        durationMs: Long = 200_000L,
        isUnknownCaller: Boolean? = null,
        isVerifiedCaller: Boolean? = null,
    ) = CallContext(
        state = CallState.IDLE,
        phoneNumber = null,
        startedAtMillis = startedAtMillis,
        endedAtMillis = now,
        durationMs = durationMs,
        durationSec = durationMs / 1000L,
        isUnknownCaller = isUnknownCaller,
        isVerifiedCaller = isVerifiedCaller,
    )

    @Test
    fun `OFFHOOK 없이 종료된 통화 - 신호 없음`() {
        val ctx = idleContext(startedAtMillis = null, durationMs = 0L, isUnknownCaller = true)
        assertTrue(mapper.map(ctx).isEmpty())
    }

    @Test
    fun `통화 시간 0ms - 신호 없음`() {
        val ctx = idleContext(durationMs = 0L, isUnknownCaller = true)
        assertTrue(mapper.map(ctx).isEmpty())
    }

    @Test
    fun `3분 미만 단발 통화 - LONG_CALL_DURATION 없음`() {
        val ctx = idleContext(durationMs = 179_999L, isUnknownCaller = false)
        assertFalse(RiskSignal.LONG_CALL_DURATION in mapper.map(ctx))
    }

    @Test
    fun `정확히 3분 통화 - LONG_CALL_DURATION 발생`() {
        val ctx = idleContext(durationMs = 180_000L)
        assertTrue(RiskSignal.LONG_CALL_DURATION in mapper.map(ctx))
    }

    @Test
    fun `미확인 발신자 단발 통화 - UNKNOWN_CALLER만 발생`() {
        val ctx = idleContext(durationMs = 60_000L, isUnknownCaller = true)
        val signals = mapper.map(ctx)
        assertEquals(listOf(RiskSignal.UNKNOWN_CALLER), signals)
    }

    @Test
    fun `미확인 발신자 3분 이상 통화 - LONG_CALL_DURATION + UNKNOWN_CALLER`() {
        val ctx = idleContext(durationMs = 200_000L, isUnknownCaller = true)
        val signals = mapper.map(ctx)
        assertTrue(RiskSignal.LONG_CALL_DURATION in signals)
        assertTrue(RiskSignal.UNKNOWN_CALLER in signals)
    }

    @Test
    fun `확인된 발신자 장시간 통화 - LONG_CALL_DURATION만 발생`() {
        val ctx = idleContext(durationMs = 200_000L, isUnknownCaller = false)
        val signals = mapper.map(ctx)
        assertEquals(listOf(RiskSignal.LONG_CALL_DURATION), signals)
    }

    @Test
    fun `isUnknownCaller null - UNKNOWN_CALLER 미발생 (판단 불가)`() {
        val ctx = idleContext(durationMs = 200_000L, isUnknownCaller = null)
        assertFalse(RiskSignal.UNKNOWN_CALLER in mapper.map(ctx))
    }

    @Test
    fun `미검증 발신자 - UNVERIFIED_CALLER 발생`() {
        val ctx = idleContext(durationMs = 60_000L, isVerifiedCaller = false)
        assertTrue(RiskSignal.UNVERIFIED_CALLER in mapper.map(ctx))
    }

    @Test
    fun `isVerifiedCaller null - UNVERIFIED_CALLER 미발생`() {
        val ctx = idleContext(durationMs = 60_000L, isVerifiedCaller = null)
        assertFalse(RiskSignal.UNVERIFIED_CALLER in mapper.map(ctx))
    }

    @Test
    fun `isVerifiedCaller true - UNVERIFIED_CALLER 미발생`() {
        val ctx = idleContext(durationMs = 60_000L, isVerifiedCaller = true)
        assertFalse(RiskSignal.UNVERIFIED_CALLER in mapper.map(ctx))
    }

    @Test
    fun `isVerifiedCaller false isUnknownCaller false - UNVERIFIED_CALLER만 발생`() {
        val ctx = idleContext(durationMs = 60_000L, isUnknownCaller = false, isVerifiedCaller = false)
        val signals = mapper.map(ctx)
        assertTrue(RiskSignal.UNVERIFIED_CALLER in signals)
        assertFalse(RiskSignal.UNKNOWN_CALLER in signals)
    }
}
