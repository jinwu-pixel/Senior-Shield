package com.example.seniorshield.monitoring.appusage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import com.example.seniorshield.monitoring.registry.RemoteControlAppRegistry
import com.example.seniorshield.monitoring.session.ResetEpochProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-AppMonitor"
private const val POLL_INTERVAL_MS = 5_000L

/** 원격제어 앱을 탐색할 최근 사용 시간 윈도우 (30초) */
private const val DETECTION_WINDOW_MS = 30_000L

/**
 * 원격제어 앱 감지 후 이 시간(ms) 이내에 뱅킹앱이 열리면 연계 신호를 발생시킨다.
 * 세션 타임아웃(30분)과 동일하게 설정하여, TeamViewer 종료 후 뱅킹 앱 실행까지
 * 시간 차가 있어도 BANKING_APP_OPENED_AFTER_REMOTE_APP 신호가 생성되도록 한다.
 */
private const val REMOTE_APP_WINDOW_MS = 30 * 60 * 1000L

/**
 * UsageStatsManager 기반 앱 사용 신호 수집기.
 *
 * 5초 간격으로 최근 30초 내 사용된 앱 전체를 폴링하여 아래 두 가지 신호를 감지한다.
 *  - REMOTE_CONTROL_APP_OPENED : 원격제어 앱이 최근 30초 이내에 포그라운드였음
 *  - BANKING_APP_OPENED_AFTER_REMOTE_APP : 원격제어 앱 사용 30분 이내에 뱅킹 앱이 열림
 *
 * ## 생산 provenance (A안, 2026-07-16)
 * 각 poll 반복은 **조회를 시작하기 전**(루프 헤드)에 [ResetEpochProvider.userResetEpoch]를
 * 캡처해 그 반복의 방출에 스탬프한다 — 조회·분류 도중 사용자 reset이 끼면 방출이 보수적으로
 * stale 처리된다. flowOn 버퍼를 건너도 생산 시점이 보존된다.
 *
 * ## 조회 3상 계약 (라운드 11)
 * 실제 raw 관측이 있는 조회만 Observed(빈 집합 = 유효한 "부재" 관측). raw 0건·manager 없음·
 * 예외는 Unknown — Unknown tick은 방출을 건너뛰어 일시 장애가 상태 전이(위험 해제/뱅킹 종료)로
 * 위장되지 않는다. 최초 1회만 combine 충전용 중립값을 방출한다(권한 미부여 기기 liveness).
 *
 * ## 원격앱 clean-transition 격리 (라운드 10-11)
 * epoch 전진(사용자 reset) 후에는 **성공한 부재 관측 전에는 재무장하지 않는다** — 30초 lookback이
 * 나르는 pre-reset 원격앱 사용이 fresh epoch로 세탁되어 직접 신호·30분 앵커(최대 30분 30초 오염)를
 * 되살리는 경로를 차단한다. 격리 상태는 monitor 수명 단일 원자 상태([RemoteGateState])로 유지되어
 * FGS 재구독으로 초기화되지 않으며, collector generation으로 구세대 blocking 조회 결과를 폐기한다.
 *
 * 동작 전제:
 *  - PACKAGE_USAGE_STATS 특수 권한이 부여되어 있어야 한다.
 *  - 권한이 없으면 Unknown이 지속되어 최초 중립값 이후 방출이 없다 (앱은 중단되지 않는다).
 *
 * 로그 태그: SeniorShield-AppMonitor
 */
@Singleton
class RealAppUsageRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val remoteControlRegistry: RemoteControlAppRegistry,
    private val resetEpochProvider: ResetEpochProvider,
) : AppUsageRiskMonitor {

    /** 테스트용 시계 주입점. 프로덕션은 System.currentTimeMillis(). */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * replay 지평 판정 전용 monotonic 시계 주입점. 프로덕션은 SystemClock.elapsedRealtime —
     * 벽시계 역행(수동 시간 변경·NTP 보정)이 만료 관측을 fresh로 되살리지 못한다 (라운드 14-②).
     */
    @VisibleForTesting
    internal var monotonicClock: () -> Long = SystemClock::elapsedRealtime

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /** 조회 3상 결과. [Observed.packages]가 비어 있으면 "성공한 부재 관측"이다. */
    private sealed interface UsageQuery {
        data class Observed(val packages: Set<String>) : UsageQuery
        data object Unknown : UsageQuery
    }

    /**
     * 원격앱 격리 게이트 + 30분 앵커 — monitor 수명 단일 원자 상태.
     * [activeGeneration]이 상태 안에 있어 "generation 검사 → 상태 적용"이 하나의 CAS로
     * 원자 결정된다(라운드 12-② — 검사와 적용 사이에 새 collector가 끼어드는 창 제거).
     */
    @VisibleForTesting
    internal data class RemoteGateState(
        /** 이 상태를 소유한 최신 collector generation — 더 낮은 세대의 관측은 적용 거부. */
        val activeGeneration: Long,
        /** 마지막으로 관측한 epoch. [INITIAL]의 -1은 "최초 관측 전" 표지. */
        val lastObservedEpoch: Long,
        /** 현재 epoch에서 성공한(Observed) 원격앱 부재를 1회 이상 관측했는가 = gate open. */
        val remoteAbsentObservedInCurrentEpoch: Boolean,
        /** 원격앱 마지막 관측 시각(30분 앵커). null = 미장전. */
        val anchorAtMs: Long?,
        /** 앵커가 (재)장전된 시점의 epoch — 복합 신호의 causal epoch (min 규칙). */
        val anchorEpoch: Long,
        /**
         * 마지막 실제(Observed) APP 방출 캐시 — bounded replay 용 ([CachedObservation] KDoc).
         * 등록(세대 전진)과 **같은 원자 상태**에 있어, 새 collector 등록 이후 도착한
         * 구세대 관측은 캐시에 기록될 수 없다 (라운드 14 권장).
         */
        val cachedObservation: CachedObservation<List<RiskSignal>>? = null,
    ) {
        companion object {
            val INITIAL = RemoteGateState(
                activeGeneration = 0L,
                lastObservedEpoch = -1L,
                remoteAbsentObservedInCurrentEpoch = false,
                anchorAtMs = null,
                anchorEpoch = 0L,
                cachedObservation = null,
            )
        }
    }

    /** [applyAppObservation]의 원자 판정 결과. */
    @VisibleForTesting
    internal sealed interface AppObservationOutcome {
        /** gate 전이·신호 산출·캐시 기록이 하나의 CAS로 커밋됨 — [produced]만 방출이 허가된다. */
        data class Applied(val produced: Produced<List<RiskSignal>>) : AppObservationOutcome

        /** 더 새로운 collector가 상태를 소유 — 이 collector는 종료해야 한다 (방출 금지). */
        data object Superseded : AppObservationOutcome

        /** 관측 epoch가 상태보다 오래됨 — 상태·방출 없이 tick 폐기. */
        data object StaleEpoch : AppObservationOutcome
    }

    private val remoteGateState = AtomicReference(RemoteGateState.INITIAL)

    /**
     * BANKING collector 등록(세대)과 관측 캐시의 단일 원자 상태 — APP 축의 [RemoteGateState]와
     * 동형 구조다. APP과 BANKING은 동시 수집되는 독립 flow이므로 세대 축을 분리한다
     * (공유하면 서로의 조회 결과를 무효화한다 — 라운드 11 계약).
     */
    private data class BankingCollectorState(
        val activeGeneration: Long,
        val cached: CachedObservation<Boolean>?,
    )

    private val bankingState = AtomicReference(BankingCollectorState(0L, null))

    /**
     * 마지막 실제(Observed) 방출 캐시 — 재구독 첫 Unknown에서 **원래 epoch로 replay**한다
     * (라운드 12-③: fresh 중립 재생성은 직전 상태와 다른 값일 때 가짜 edge를 만든다).
     * 캐시는 collector 등록과 **같은 원자 상태**(APP=[RemoteGateState],
     * BANKING=[BankingCollectorState]) 안에 있어, 새 collector 등록 이후 도착한 구세대
     * 관측은 캐시에 기록될 수 없다 (라운드 14 권장 — 등록↔첫 기록 사이 창 제거).
     *
     * ## bounded replay (라운드 13-②·14-② 정책 결정)
     * replay는 관측이 **자신의 lookback 지평**(APP=30초, BANKING=6초) 안에 있을 때만 허용 —
     * 관측은 그 지평 안에서만 "현재 상태"로 재주장될 수 있다. 지평의 기준 시각은
     * **조회 시작 시각**([monotonicClock] 축)이다: 완료 시각 기준은 blocking 조회 소요만큼
     * 유효기간을 연장하고, 벽시계는 역행 시 음수 age를 fresh로 위장시킨다 (라운드 14-②).
     * 유효 구간은 폐구간 0..지평. 지평을 벗어나면 중립값으로 복귀한다: 장기 공백 후의
     * 위험 상태 무기한 유지(만료 세션 재생성)를 차단하고, 공백 후 회복(예: 뱅킹 재실행)은
     * 진짜 신규 edge로 취급된다.
     */
    internal data class CachedObservation<T>(
        val produced: Produced<T>,
        /** 이 관측을 확정한 조회의 **시작** 시각 — [monotonicClock] 축. */
        val observedAtMs: Long,
        /** 이 관측을 기록한 collector 세대 (provenance 기록용). */
        val generation: Long,
    )

    /**
     * collector 시작 즉시 세대 전진·등록 — 등록·캐시가 같은 원자 상태이므로, 등록 이후의
     * 구세대 관측은 상태 적용([applyAppObservation])도 캐시 기록도 원자적으로 거부된다.
     * @return 이 collector의 세대.
     */
    @VisibleForTesting
    internal fun registerAppCollector(): Long =
        remoteGateState.updateAndGet { it.copy(activeGeneration = it.activeGeneration + 1) }
            .activeGeneration

    @VisibleForTesting
    internal fun registerBankingCollector(): Long =
        bankingState.updateAndGet { it.copy(activeGeneration = it.activeGeneration + 1) }
            .activeGeneration

    /**
     * BANKING 관측 1건의 캐시 기록 + 방출 권한을 단일 CAS로 결정한다 (라운드 15-④) —
     * 등록과 같은 원자 상태 위라서 구세대의 늦은 기록과 방출이 함께 거부된다.
     * @return true = 기록·방출 허가, false = superseded (방출 금지, collector 종료).
     */
    @VisibleForTesting
    internal fun commitBankingObservation(
        produced: Produced<Boolean>,
        observedAtMs: Long,
        generation: Long,
    ): Boolean {
        while (true) {
            val prev = bankingState.get()
            if (prev.activeGeneration != generation) return false
            val next = prev.copy(cached = CachedObservation(produced, observedAtMs, generation))
            if (bankingState.compareAndSet(prev, next)) return true
        }
    }

    /** age가 폐구간 0..[windowMs] 안일 때만 replay — 음수 age(시계 역행)는 fresh가 아니다. */
    private fun <T> CachedObservation<T>?.replayWithin(
        windowMs: Long,
        nowMs: Long,
    ): Produced<T>? = this?.takeIf { (nowMs - it.observedAtMs) in 0..windowMs }?.produced

    /**
     * 관측 1건을 generation-aware CAS로 적용한다 — 세대 검사(`!=` 소유권 계약, 라운드 15 권장)·
     * epoch 검사·gate 전이·신호 산출·replay 캐시 기록이 **단일 compareAndSet** 성공으로만
     * 반영된다 (라운드 15-③: gate 상태와 캐시가 서로 다른 세대로 갈라질 수 없다; blocking
     * 조회는 이 함수 밖). 방출 권한도 이 선형화 결과로 결정된다 — [AppObservationOutcome.Applied]가
     * 아니면 방출 금지 (라운드 15-④).
     */
    @VisibleForTesting
    internal fun applyAppObservation(
        generation: Long,
        epoch: Long,
        packages: Set<String>,
        nowMs: Long,
        observedAtMs: Long,
    ): AppObservationOutcome {
        val remoteApp = packages.firstOrNull { isRemoteControlApp(it) }
        val remotePresent = remoteApp != null
        while (true) {
            val prev = remoteGateState.get()
            if (generation != prev.activeGeneration) return AppObservationOutcome.Superseded
            if (epoch < prev.lastObservedEpoch) return AppObservationOutcome.StaleEpoch
            val transitioned = advanceGate(prev, epoch, remotePresent, nowMs)
            val produced = stampedSignals(packages, remoteApp, transitioned, epoch)
            val next = transitioned.copy(
                cachedObservation = CachedObservation(produced, observedAtMs, generation),
            )
            if (remoteGateState.compareAndSet(prev, next)) {
                logAppliedObservation(prev, next, remoteApp, produced)
                return AppObservationOutcome.Applied(produced)
            }
        }
    }

    /** 성공 커밋된 전이만 기록한다 — CAS 재시도 중의 로그는 반영되지 않은 상태를 기록한다 (라운드 16 권장). */
    private fun logAppliedObservation(
        prev: RemoteGateState,
        next: RemoteGateState,
        remoteApp: String?,
        produced: Produced<List<RiskSignal>>,
    ) {
        if (remoteApp != null && !next.remoteAbsentObservedInCurrentEpoch) {
            Log.d(TAG, "remote app quarantined until a successful absence observation ($remoteApp)")
        }
        if (prev.anchorAtMs != null && next.anchorAtMs == null) {
            Log.d(TAG, "remote anchor cleared (expired or stale on clean transition)")
        }
        if (produced.value.isNotEmpty()) {
            Log.d(TAG, "signals committed: ${produced.value} (epoch=${produced.producedAtEpoch})")
        }
    }

    @VisibleForTesting
    internal fun gateStateForTest(): RemoteGateState = remoteGateState.get()

    /**
     * 뱅킹 앱이 현재 포그라운드에 있는지 감지한다.
     * 폴링 간격보다 약간 넉넉한 윈도우(POLL + 1초)로 쿼리해 타이밍 오차를 흡수한다.
     * 값이 바뀔 때만 방출한다 (value-only 비교 — epoch는 동일성에서 제외).
     */
    override fun observeBankingAppForeground(): Flow<Produced<Boolean>> = flow {
        val myGeneration = registerBankingCollector()
        var emittedThisCollection = false
        while (true) {
            // 계약: 조회를 시작하기 **전** epoch 캡처 — 조회 도중 reset이 끼면 보수적으로 stale.
            val epoch = resetEpochProvider.userResetEpoch
            // replay 지평의 기준 시각 = 조회 **시작** (라운드 14-② — 완료 시각 기준은
            // blocking 조회 소요만큼 관측의 유효기간을 연장한다).
            val observedAtMs = monotonicClock()
            val query = queryRecentlyUsedPackages(POLL_INTERVAL_MS + 1_000L)
            currentCoroutineContext().ensureActive() // 방출 직전 cancellation 확인
            when (query) {
                is UsageQuery.Unknown -> {
                    // replay 선택·방출 권한을 등록과 같은 원자 상태의 **단일 스냅숏**으로 결정
                    // (라운드 15-④) — 교체된 collector는 첫 Unknown replay도 방출 금지.
                    val state = bankingState.get()
                    if (state.activeGeneration != myGeneration) {
                        Log.d(TAG, "banking poll discarded — superseded by newer collector")
                        return@flow
                    }
                    if (!emittedThisCollection) {
                        // 조회 실패 tick은 스킵(false 방출 금지 — "뱅킹 종료" 위장 차단).
                        // 재구독 첫 Unknown은 지평(6초) 내 마지막 실제 관측을 원래 epoch로 replay,
                        // 지평 밖·무이력이면 중립값 (bounded replay — 캐시 KDoc 참조).
                        emittedThisCollection = true
                        emit(
                            state.cached.replayWithin(POLL_INTERVAL_MS + 1_000L, monotonicClock())
                                ?: Produced(false, epoch),
                        )
                    }
                }

                is UsageQuery.Observed -> {
                    val produced = Produced(query.packages.any { it in BANKING_PACKAGES }, epoch)
                    // 캐시 기록과 방출 권한을 같은 CAS로 결정 — 기록이 거부되면 방출도 금지
                    // (라운드 15-④: 거부-후-방출 창 제거).
                    if (!commitBankingObservation(produced, observedAtMs, myGeneration)) {
                        Log.d(TAG, "banking poll discarded — superseded by newer collector")
                        return@flow
                    }
                    emittedThisCollection = true
                    emit(produced)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged { a, b -> a.value == b.value }
        .flowOn(ioDispatcher)

    override fun observeAppUsageSignals(): Flow<Produced<List<RiskSignal>>> = flow {
        val myGeneration = registerAppCollector() // 시작 즉시 등록 — 구세대의 검사-후-적용 창 제거
        var emittedThisCollection = false
        while (true) {
            // 계약: 조회·분류를 시작하기 **전** epoch 캡처.
            val epoch = resetEpochProvider.userResetEpoch
            // replay 지평의 기준 시각 = 조회 **시작** (라운드 14-②).
            val observedAtMs = monotonicClock()
            val query = queryRecentlyUsedPackages(DETECTION_WINDOW_MS) // blocking 조회 — CAS 밖
            currentCoroutineContext().ensureActive()
            when (query) {
                is UsageQuery.Unknown -> {
                    // replay 선택·방출 권한을 gate 상태의 **단일 스냅숏**으로 결정 (라운드 15-④)
                    // — 교체된 collector는 첫 Unknown replay도 방출 금지.
                    val state = remoteGateState.get()
                    if (state.activeGeneration != myGeneration) {
                        Log.d(TAG, "app-usage poll discarded — superseded by newer collector")
                        return@flow
                    }
                    if (!emittedThisCollection) {
                        // 재구독 첫 Unknown: 지평(30초) 내 마지막 실제 관측만 원래 epoch로 replay
                        // (bounded replay — 캐시 KDoc 참조).
                        emittedThisCollection = true
                        emit(
                            state.cachedObservation
                                .replayWithin(DETECTION_WINDOW_MS, monotonicClock())
                                ?: Produced(emptyList(), epoch),
                        )
                    }
                }

                is UsageQuery.Observed -> {
                    when (val outcome = applyAppObservation(myGeneration, epoch, query.packages, clock(), observedAtMs)) {
                        is AppObservationOutcome.Superseded -> {
                            Log.d(TAG, "app-usage poll discarded — superseded by newer collector")
                            return@flow
                        }

                        is AppObservationOutcome.StaleEpoch -> {
                            Log.d(TAG, "app-usage poll discarded — observation epoch older than gate state")
                        }

                        is AppObservationOutcome.Applied -> {
                            emittedThisCollection = true
                            emit(outcome.produced)
                        }
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }
        .distinctUntilChanged { a, b -> a.value == b.value }
        .flowOn(ioDispatcher)

    /**
     * 격리 게이트·앵커의 순수 상태 전이 — [applyAppObservation]의 CAS 루프 안에서만 호출된다
     * (세대·epoch 선행 검사를 통과한 관측만 도달; activeGeneration은 copy로 보존).
     * 규칙(라운드 10-11 확정):
     * - 최초 관측 epoch 0 = gate open(클린 부팅), 최초 관측 epoch > 0 = gate closed.
     * - epoch 전진 → 부재 확인 상태 초기화(격리 재시작) — 첫 post-reset 조회에 원격앱이 보여도 격리.
     * - **성공한(Observed) 부재 관측만** gate를 연다. Unknown은 부재로 인정하지 않는다.
     * - 부재 관측 시 이전 epoch의 stale 앵커를 정리한다 (clean transition).
     * - gate open 상태의 원격앱 관측만 앵커를 (재)장전한다 — causal epoch 동반 저장.
     */
    private fun advanceGate(
        prev: RemoteGateState,
        epoch: Long,
        remotePresent: Boolean,
        nowMs: Long,
    ): RemoteGateState {
        var state = if (prev.lastObservedEpoch < 0L) {
            prev.copy(
                lastObservedEpoch = epoch,
                remoteAbsentObservedInCurrentEpoch = epoch == 0L,
            )
        } else {
            prev
        }
        if (epoch > state.lastObservedEpoch) {
            state = state.copy(
                lastObservedEpoch = epoch,
                remoteAbsentObservedInCurrentEpoch = false,
            )
        }
        // 앵커 30분 자연 만료 (기존 규칙 유지). 로그는 CAS 성공 후 [logAppliedObservation]에서 —
        // 재시도 중의 로그는 반영되지 않은 상태를 기록한다 (라운드 16 권장).
        val anchorAt = state.anchorAtMs
        if (anchorAt != null && nowMs - anchorAt > REMOTE_APP_WINDOW_MS) {
            state = state.copy(anchorAtMs = null)
        }
        return if (remotePresent) {
            if (state.remoteAbsentObservedInCurrentEpoch) {
                state.copy(anchorAtMs = nowMs, anchorEpoch = epoch)
            } else {
                state // 격리 중 — 재무장 금지
            }
        } else {
            val staleAnchor = state.anchorAtMs != null && state.anchorEpoch < epoch
            state.copy(
                remoteAbsentObservedInCurrentEpoch = true,
                anchorAtMs = if (staleAnchor) null else state.anchorAtMs,
            )
        }
    }

    /**
     * 이번 반복의 신호와 스탬프를 결정한다. 격리 중(gate closed)에는 원격앱 파생 신호
     * (직접 신호·복합 신호)를 모두 방출하지 않는다. 복합 신호가 포함되면 스탬프는
     * min(앵커 epoch, 반복 epoch) = 앵커 epoch — 원인(원격앱 감지)의 causal epoch를 승계한다.
     */
    private fun stampedSignals(
        packages: Set<String>,
        remoteApp: String?,
        state: RemoteGateState,
        iterationEpoch: Long,
    ): Produced<List<RiskSignal>> {
        val gateOpen = state.remoteAbsentObservedInCurrentEpoch
        val signals = mutableListOf<RiskSignal>()

        if (remoteApp != null && gateOpen) {
            signals += RiskSignal.REMOTE_CONTROL_APP_OPENED
        }

        var stampEpoch = iterationEpoch
        val bankingApp = packages.firstOrNull { it in BANKING_PACKAGES }
        if (gateOpen && state.anchorAtMs != null && bankingApp != null) {
            signals += RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP
            stampEpoch = minOf(state.anchorEpoch, iterationEpoch)
        }

        return Produced(signals, stampEpoch)
    }

    private fun isRemoteControlApp(packageName: String): Boolean =
        remoteControlRegistry.matches(packageName)

    /**
     * 최근 [windowMs] 이내에 사용된 앱의 패키지명 집합을 3상 계약으로 조회한다.
     *
     * 기기 호환성을 위해 3단계 fallback 체인을 유지한다:
     * 1. INTERVAL_BEST — 수초 단위 고해상도 (대부분의 기기)
     * 2. INTERVAL_DAILY — 일부 MediaTek ROM에서 INTERVAL_BEST가 빈 결과를 반환하는 경우
     * 3. queryEvents — MOVE_TO_FOREGROUND 이벤트 직접 조회 (최종 폴백)
     *
     * 비어있지 않은 첫 계층을 채택하고(기존 탐지 순서 유지), 전 계층이 비었어도 raw 관측이
     * 하나라도 있었으면 Observed(empty) = 유효한 부재 관측이다. raw 무관측·manager 없음·
     * 예외 전멸은 Unknown.
     */
    private fun queryRecentlyUsedPackages(windowMs: Long): UsageQuery {
        val manager = usageStatsManager ?: return UsageQuery.Unknown
        val endTime = clock()
        val startTime = endTime - windowMs
        var sawRawObservation = false

        for (interval in intArrayOf(UsageStatsManager.INTERVAL_BEST, UsageStatsManager.INTERVAL_DAILY)) {
            val layer = tryQueryUsageStats(manager, interval, startTime, endTime) ?: continue
            sawRawObservation = true
            if (layer.isNotEmpty()) {
                Log.d(TAG, "recent packages via interval=$interval (${windowMs / 1000}s, ${layer.size}개): $layer")
                return UsageQuery.Observed(layer)
            }
        }

        val eventPackages = tryQueryEvents(manager, startTime, endTime)
        if (eventPackages != null) {
            sawRawObservation = true
            if (eventPackages.isNotEmpty()) {
                Log.d(TAG, "recent packages via queryEvents fallback (${windowMs / 1000}s, ${eventPackages.size}개): $eventPackages")
                return UsageQuery.Observed(eventPackages)
            }
        }

        return if (sawRawObservation) {
            UsageQuery.Observed(emptySet()) // raw 관측 존재 + 윈도우 내 사용 없음 = 성공한 부재 관측
        } else {
            Log.w(TAG, "모든 UsageStats 조회에서 raw 관측 없음 — 권한 미부여 또는 기기 호환성 문제: Unknown")
            UsageQuery.Unknown
        }
    }

    /** queryUsageStats 시도. raw 관측이 없거나(0건) 예외면 null — 계층 Unknown 취급. */
    private fun tryQueryUsageStats(
        manager: UsageStatsManager,
        interval: Int,
        startTime: Long,
        endTime: Long,
    ): Set<String>? {
        return try {
            val stats = manager.queryUsageStats(interval, startTime, endTime)
            val raw = stats?.size ?: 0
            if (raw <= 0) return null
            val filtered = stats
                .filter { it.lastTimeUsed >= startTime }
                .mapTo(mutableSetOf()) { it.packageName }
            Log.w(TAG, "tryQueryUsageStats(interval=$interval): raw=$raw, filtered=${filtered.size} $filtered")
            filtered
        } catch (e: Exception) {
            Log.w(TAG, "queryUsageStats(interval=$interval) 실패: ${e.message}")
            null
        }
    }

    /** queryEvents로 MOVE_TO_FOREGROUND 이벤트에서 패키지명 추출. raw 이벤트 0건·예외면 null. */
    private fun tryQueryEvents(
        manager: UsageStatsManager,
        startTime: Long,
        endTime: Long,
    ): Set<String>? {
        return try {
            val events = manager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            val packages = mutableSetOf<String>()
            var totalEvents = 0
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                totalEvents++
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    packages.add(event.packageName)
                }
            }
            Log.w(TAG, "tryQueryEvents: totalEvents=$totalEvents, foreground=${packages.size} $packages")
            if (totalEvents <= 0) null else packages
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents 실패: ${e.message}")
            null
        }
    }

    override fun latestBankingForegroundEventTimestamp(windowMs: Long): Long? {
        val manager = usageStatsManager ?: return null
        val now = clock()
        val startTime = now - windowMs
        return try {
            val events = manager.queryEvents(startTime, now)
            val event = UsageEvents.Event()
            var latestTs: Long? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.packageName in BANKING_PACKAGES
                ) {
                    val ts = event.timeStamp
                    if (latestTs == null || ts > latestTs) latestTs = ts
                }
            }
            latestTs
        } catch (e: Exception) {
            Log.w(TAG, "latestBankingForegroundEventTimestamp 실패: ${e.message}")
            null
        }
    }

    companion object {
        /** 원격제어 앱과 연계 감지 대상인 뱅킹 앱 패키지명 목록 */
        private val BANKING_PACKAGES = setOf(
            "com.kbstar.kbbank",                              // KB국민은행 (KB스타뱅킹)
            "com.nonghyup.nhallonebank",                      // NH농협은행 (NH올원뱅크)
            "nh.smart.banking",                               // NH농협은행 (NH스마트뱅킹)
            "com.shinhan.sbanking",                           // 신한은행 (신한 SOL뱅크)
            "com.hanabank.ebk.channel.android.hananbank",     // 하나은행 (구 하나원큐)
            "com.hanabank.oqf",                               // 하나은행 (신 하나원큐)
            "com.wooribank.smart.npib",                       // 우리은행 (우리WON뱅킹)
            "com.ibk.android.ionebank",                       // IBK기업은행 (i-ONE Bank)
            "com.scbank.ma30",                                // SC제일은행 (SC모바일뱅킹)
            "com.smg.spbs",                                   // 새마을금고 (MG더뱅킹)
            "com.suhyup.psmb",                                // 수협은행 (수협 파트너뱅크)
            "com.kakaobank.channel",                          // 카카오뱅크
            "com.kbankwith.smartbank",                        // 케이뱅크
            "viva.republica.toss",                            // 토스
        )
    }
}
