package com.example.seniorshield.monitoring.appinstall

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RealAppInstallRiskMonitor의 signal hold/reset 패턴을 검증한다.
 *
 * callbackFlow + BroadcastReceiver를 직접 테스트할 수 없으므로,
 * 동일한 cancel/relaunch + delay 리셋 패턴을 추출하여 검증한다.
 *
 * 검증 대상:
 * 1. 연속 설치 시 resetJob cancel → relaunch로 타이머 갱신
 * 2. flow close(awaitClose) 시 resetJob.cancel()로 유령 emit 방지
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppInstallSignalResetTest {

    companion object {
        private const val HOLD_MS = 5_000L
    }

    @Test
    fun `5초 이내 연속 설치 시 reset timer가 갱신된다`() = runTest {
        val state = MutableStateFlow<List<String>>(emptyList())
        var resetJob: Job? = null

        fun simulateInstall() {
            state.value = listOf("SUSPICIOUS_APP_INSTALLED")
            resetJob?.cancel()
            resetJob = launch {
                delay(HOLD_MS)
                state.value = emptyList()
            }
        }

        // 첫 번째 설치 — delay(5000) 등록
        simulateInstall()
        runCurrent()
        assertEquals(listOf("SUSPICIOUS_APP_INSTALLED"), state.value)

        // 3초 후 두 번째 설치 — 첫 타이머 cancel, 새 타이머(delay 5000) 시작
        advanceTimeBy(3_000)
        runCurrent()
        simulateInstall()
        runCurrent()

        // 5초 시점 (첫 설치 +5초) — 첫 타이머가 cancel되었으므로 리셋 안 됨
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(
            "첫 타이머가 cancel되어 아직 SIGNAL 유지",
            listOf("SUSPICIOUS_APP_INSTALLED"),
            state.value,
        )

        // 8초 시점 (두 번째 설치 +5초) — 두 번째 타이머 만료 → 리셋
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(
            "두 번째 타이머 만료 → emptyList 리셋",
            emptyList<String>(),
            state.value,
        )
    }

    @Test
    fun `flow close 후 reset emit이 발생하지 않는다`() = runTest {
        val state = MutableStateFlow<List<String>>(emptyList())
        var resetJob: Job? = null

        fun simulateInstall() {
            state.value = listOf("SUSPICIOUS_APP_INSTALLED")
            resetJob?.cancel()
            resetJob = launch {
                delay(HOLD_MS)
                state.value = emptyList()
            }
        }

        // 설치 트리거 → 5초 타이머 시작
        simulateInstall()
        runCurrent()
        assertEquals(listOf("SUSPICIOUS_APP_INSTALLED"), state.value)

        // awaitClose { resetJob?.cancel() } 시뮬레이션
        resetJob?.cancel()
        resetJob = null

        // hold 시간 경과 — 유령 emit이 발생하면 안 됨
        advanceTimeBy(HOLD_MS + 1_000)
        runCurrent()
        assertEquals(
            "flow close 후 추가 emit 없음 — 값 변화 없어야 함",
            listOf("SUSPICIOUS_APP_INSTALLED"),
            state.value,
        )
    }
}
