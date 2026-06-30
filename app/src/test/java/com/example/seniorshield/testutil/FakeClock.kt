package com.example.seniorshield.testutil

/**
 * 테스트용 가짜 시계. clock seam(`@VisibleForTesting internal var clock: () -> Long`)에 주입한다.
 *
 * 사용:
 * ```
 * val fakeClock = FakeClock(now = 1_000_000L)
 * obj.clock = fakeClock.provider     // 시간축을 공유하는 모든 seam 객체에 동일 provider 주입
 * fakeClock.advanceMs(30_000L)       // wall-clock 전진 → 공유 provider가 동시에 이동
 * ```
 *
 * **주의:** `FakeClock`은 wall-clock(`System.currentTimeMillis()`) 비교만 제어한다.
 * 코루틴 가상시간(`delay(...)`)은 별개 축이며 `advanceMs`로 움직이지 않는다
 * (그쪽은 `TestScheduler`/`runCurrent()` 소관).
 */
class FakeClock(var now: Long = 0L) {
    /** 각 seam 객체의 `.clock` 에 주입할 시간 소스. mutable [now]를 클로저로 캡처한다. */
    val provider: () -> Long = { now }

    /** wall-clock을 [delta] ms 만큼 전진시킨다. */
    fun advanceMs(delta: Long) { now += delta }
}
