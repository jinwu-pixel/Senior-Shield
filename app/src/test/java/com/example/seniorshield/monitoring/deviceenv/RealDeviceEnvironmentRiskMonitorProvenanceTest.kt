package com.example.seniorshield.monitoring.deviceenv

import android.content.Context
import android.content.pm.PackageManager
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RealDeviceEnvironmentRiskMonitor] 생산 경계(A안) 검증 — epoch는 init 검사 **시작 전**
 * 1회 캡처되고(실패 fallback 포함), StateFlow replay는 그 epoch를 재스탬프 없이 승계한다.
 *
 * 하네스: plain JVM — su 바이너리 파일 체크는 부재(false), Build.TAGS는 default(null),
 * 루팅 패키지 판정만 mockk PackageManager로 제어한다.
 */
class RealDeviceEnvironmentRiskMonitorProvenanceTest {

    /** 조회 시점을 테스트가 제어하는 provider — "검사 도중 reset" 모델링용. */
    private class SteppingEpochProvider(var current: Long = 0L) : ResetEpochProvider {
        override val userResetEpoch: Long get() = current
    }

    @Test
    fun `epoch is captured before the root check starts`() = runTest {
        val provider = SteppingEpochProvider(current = 0L)
        val pm = mockk<PackageManager>()
        // 검사(PackageManager 조회)가 시작되는 순간 reset이 끼어드는 상황 — 캡처가 검사 전이면
        // 저장 epoch는 0이어야 한다. (루팅 패키지 1개 존재 → HIGH_RISK 경로)
        every { pm.getPackageInfo(any<String>(), any<Int>()) } answers {
            provider.current = 5L
            mockk(relaxed = true)
        }
        val context = mockk<Context>(relaxed = true) {
            every { packageManager } returns pm
        }

        val monitor = RealDeviceEnvironmentRiskMonitor(context, provider)

        val produced = monitor.observeDeviceEnvironmentSignals().first()
        assertEquals(listOf(RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT), produced.value)
        assertEquals("검사 시작 전 캡처된 epoch여야 함", 0L, produced.producedAtEpoch)
    }

    @Test
    fun `failure fallback still carries the pre-check epoch`() = runTest {
        val provider = SteppingEpochProvider(current = 3L)
        val context = mockk<Context>(relaxed = true) {
            every { packageManager } answers {
                provider.current = 9L
                // Exception은 checkRootPackages의 내부 catch에 흡수되어 fallback을 타지 않는다 —
                // outer catch(Throwable) 전용 fallback 경로를 실제로 행사하려면 Error가 필요하다.
                throw NoClassDefFoundError("pm unavailable")
            }
        }

        val monitor = RealDeviceEnvironmentRiskMonitor(context, provider)

        val produced = monitor.observeDeviceEnvironmentSignals().first()
        assertEquals("실패 fallback은 안전값", emptyList<RiskSignal>(), produced.value)
        assertEquals("fallback도 검사-전 epoch를 유지해야 함 (try 밖 캡처)", 3L, produced.producedAtEpoch)
        // fallback이 의도한 지점(PackageManager 접근 시 Error)에서 발생했음을 확인
        io.mockk.verify(exactly = 1) { context.packageManager }
    }

    @Test
    fun `stateflow replay keeps the init epoch without restamping`() = runTest {
        val provider = SteppingEpochProvider(current = 0L)
        val pm = mockk<PackageManager> {
            every { getPackageInfo(any<String>(), any<Int>()) } throws
                PackageManager.NameNotFoundException()
        }
        val context = mockk<Context>(relaxed = true) {
            every { packageManager } returns pm
        }
        val monitor = RealDeviceEnvironmentRiskMonitor(context, provider)

        val firstCollect = monitor.observeDeviceEnvironmentSignals().first()
        assertEquals(0L, firstCollect.producedAtEpoch)

        // 사용자 reset 이후의 재구독(replay) — 재검사·재스탬프 없이 init epoch 그대로.
        // 첫 reset 이후 이 source가 정직하게 stale이 되는 근거이며, HIGH_RISK의 생존은
        // coordinator의 DEVICE_ENV 한정 승계 예외가 담당한다.
        provider.current = 7L
        val replayed = monitor.observeDeviceEnvironmentSignals().first()
        assertEquals("replay는 동일 epoch 유지 (재스탬프 금지)", 0L, replayed.producedAtEpoch)
        assertEquals(firstCollect.value, replayed.value)
    }
}
