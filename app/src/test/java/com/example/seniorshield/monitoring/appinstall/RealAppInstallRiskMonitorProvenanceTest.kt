package com.example.seniorshield.monitoring.appinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.registry.RemoteControlAppRegistry
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RealAppInstallRiskMonitor.observeInstallSignals]의 SIGNAL_HOLD_MS 미러 (5초). */
private const val HOLD_MS = 5_000L

/**
 * [RealAppInstallRiskMonitor] 생산 경계(A안) 검증:
 * - epoch는 onReceive 진입부(설치 출처 조회·분류 **전**)에 단일 캡처되어 두 분기가 공유한다.
 * - 사건 flow 예외: dUC가 (value, epoch)를 비교 — reset 후 hold 창 내 실제 신규 설치는
 *   같은 값이어도 새 epoch로 통과하고, 같은 epoch의 중복 설치는 합쳐진다.
 * - seed·hold 만료 클리어는 방출 시점 epoch를 새로 읽는다.
 *
 * 하네스: plain JVM(SDK_INT=0) — legacy getInstallerPackageName 분기, 2-인자 registerReceiver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealAppInstallRiskMonitorProvenanceTest {

    private class SteppingEpochProvider(var current: Long = 0L) : ResetEpochProvider {
        override val userResetEpoch: Long get() = current
    }

    private val provider = SteppingEpochProvider()
    private val receiverSlot = slot<BroadcastReceiver>()

    // mockk stub 캡처일 뿐 실제 등록이 아니다 — 실물 등록(RealAppInstallRiskMonitor)은
    // TIRAMISU+에서 RECEIVER_EXPORTED 플래그를 사용한다.
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private val context = mockk<Context>(relaxed = true) {
        every { registerReceiver(capture(receiverSlot), any<IntentFilter>()) } returns null
    }
    private val registry = mockk<RemoteControlAppRegistry> {
        every { matches(any()) } returns false
    }
    private val monitor = RealAppInstallRiskMonitor(context, registry, provider)

    private fun installIntent(packageName: String): Intent = mockk {
        every { action } returns Intent.ACTION_PACKAGE_ADDED
        every { getBooleanExtra(Intent.EXTRA_REPLACING, false) } returns false
        every { data } returns mockk<Uri> { every { schemeSpecificPart } returns packageName }
    }

    private fun stubSideloaded() {
        every { context.packageManager.getInstallerPackageName(any()) } returns "com.unknown.source"
    }

    private fun TestScope.collectEmissions(): Pair<MutableList<Produced<List<RiskSignal>>>, Job> {
        val emissions = mutableListOf<Produced<List<RiskSignal>>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.observeInstallSignals().collect { emissions += it }
        }
        runCurrent()
        return emissions to job
    }

    @Test
    fun `epoch is captured at onReceive entry before source classification`() = runTest {
        stubSideloaded()
        // 분류(설치 출처 조회) 도중 reset이 끼어드는 상황 — 방출은 진입 시점 epoch(0)여야 한다.
        every { context.packageManager.getInstallerPackageName("com.evil.app") } answers {
            provider.current = 5L
            "com.unknown.source"
        }
        val (emissions, job) = collectEmissions()
        assertEquals("seed 1회", 1, emissions.size)

        receiverSlot.captured.onReceive(context, installIntent("com.evil.app"))
        runCurrent()

        assertEquals(2, emissions.size)
        assertEquals(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED), emissions[1].value)
        assertEquals("분류 전(진입부) 캡처 epoch여야 함", 0L, emissions[1].producedAtEpoch)
        job.cancel()
    }

    @Test
    fun `same signal with a new epoch passes the event dedupe after reset`() = runTest {
        stubSideloaded()
        val (emissions, job) = collectEmissions()

        receiverSlot.captured.onReceive(context, installIntent("com.evil.one"))
        runCurrent()
        assertEquals(2, emissions.size) // seed + 설치 A (epoch 0)

        provider.current = 1L // 사용자 reset — hold(5초) 창 안에서
        receiverSlot.captured.onReceive(context, installIntent("com.evil.two"))
        runCurrent()

        assertEquals("reset 후 실제 신규 설치는 같은 값이어도 통과해야 함", 3, emissions.size)
        assertEquals(listOf(RiskSignal.SUSPICIOUS_APP_INSTALLED), emissions[2].value)
        assertEquals(1L, emissions[2].producedAtEpoch)
        job.cancel()
    }

    @Test
    fun `duplicate installs at the same epoch merge as before`() = runTest {
        stubSideloaded()
        val (emissions, job) = collectEmissions()

        receiverSlot.captured.onReceive(context, installIntent("com.evil.one"))
        runCurrent()
        receiverSlot.captured.onReceive(context, installIntent("com.evil.two"))
        runCurrent()

        assertEquals("같은 epoch의 동일 신호는 기존처럼 합쳐진다", 2, emissions.size)
        job.cancel()
    }

    @Test
    fun `seed and hold clear stamp at emission time`() = runTest {
        stubSideloaded()
        provider.current = 3L
        val (emissions, job) = collectEmissions()
        assertEquals("seed는 방출 시점 epoch", 3L, emissions[0].producedAtEpoch)
        assertTrue(emissions[0].value.isEmpty())

        receiverSlot.captured.onReceive(context, installIntent("com.evil.app"))
        runCurrent()
        assertEquals(3L, emissions[1].producedAtEpoch)

        provider.current = 9L // hold 대기 중 reset
        advanceTimeBy(HOLD_MS)
        runCurrent()

        assertEquals(3, emissions.size)
        assertTrue("hold 만료 클리어", emissions[2].value.isEmpty())
        assertEquals(
            "클리어는 방출 시점 epoch — fresh 클리어가 source 배제를 자연 해소한다",
            9L,
            emissions[2].producedAtEpoch,
        )
        job.cancel()
    }
}
