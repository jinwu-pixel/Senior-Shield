package com.example.seniorshield.domain.repository

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-A5: RiskEventSink.clearCurrentRiskEvent()가 비-suspend로 변경되었음을 검증한다.
 *
 * - 인터페이스 시그니처가 비-suspend여야 RiskOverlayManager 등 동기 컨텍스트에서
 *   ad-hoc CoroutineScope 신설 없이 호출 가능하다.
 * - 컴파일러가 인터페이스 위반을 잡아주지만, 명시적 행동 검증으로 동작 보장.
 *
 * 본 테스트는 인터페이스 contract만 검증한다. RoomRiskEventStore의 실제 동작은
 * Room DAO 의존성 때문에 별도 androidTest 또는 수동 회귀로 검증한다.
 */
class RiskEventSinkClearTest {

    private class InMemoryRiskEventSink : RiskEventSink {
        private val _currentEvent = MutableStateFlow<RiskEvent?>(null)
        val currentEvent: StateFlow<RiskEvent?> = _currentEvent

        override suspend fun pushRiskEvent(event: RiskEvent) { _currentEvent.value = event }
        override suspend fun updateCurrentRiskEvent(event: RiskEvent) { _currentEvent.value = event }
        override fun clearCurrentRiskEvent() { _currentEvent.value = null }
        override suspend fun clearAll() { _currentEvent.value = null }
    }

    private fun sampleEvent(): RiskEvent = RiskEvent(
        id = "evt-1",
        title = "test",
        description = "test",
        occurredAtMillis = 1L,
        level = RiskLevel.HIGH,
        signals = emptyList(),
    )

    @Test
    fun `clearCurrentRiskEvent는 비-suspend 동기 컨텍스트에서 호출 가능`() {
        val sink = InMemoryRiskEventSink()
        runBlocking { sink.pushRiskEvent(sampleEvent()) }
        assertNotNull(sink.currentEvent.value)

        // 코루틴 없이 일반 함수처럼 호출 — 컴파일/실행 성공이 곧 contract 검증
        sink.clearCurrentRiskEvent()

        assertNull(sink.currentEvent.value)
    }

    @Test
    fun `clearCurrentRiskEvent 연속 호출은 멱등`() {
        val sink = InMemoryRiskEventSink()
        sink.clearCurrentRiskEvent()
        sink.clearCurrentRiskEvent()
        assertNull(sink.currentEvent.value)
    }
}
