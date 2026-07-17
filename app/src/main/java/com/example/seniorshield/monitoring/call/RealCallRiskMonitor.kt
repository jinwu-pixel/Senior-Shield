@file:Suppress("DEPRECATION") // PhoneStateListener — API 26-30 레거시 폴백용 의도적 사용
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.example.seniorshield.monitoring.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.model.CallContext
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.model.CallState
import com.example.seniorshield.monitoring.model.Produced
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TelephonyCallback(API 31+) 또는 PhoneStateListener(API 26-30) 기반 통화 문맥 수집기.
 *
 * ## 권한 인식 재구독 구조
 * READ_PHONE_STATE 권한 상태를 3초 간격으로 폴링하고, flatMapLatest로
 * 내부 callbackFlow를 재구독한다.
 * - 권한 false→true: 새 callbackFlow 생성 → TelephonyCallback 등록 → 현재 통화 상태 seed
 * - 권한 true→false: 기존 callbackFlow 취소 (awaitClose에서 unregister) → NoPermission emit
 *
 * ## 다중 구독 안전성
 * [sharedCallContext]를 shareIn으로 공유하여, 여러 collector가 구독해도
 * TelephonyCallback/PhoneStateListener가 중복 등록되지 않는다.
 *
 * ## 수집 범위
 * - 통화 상태 전이 (IDLE / RINGING / OFFHOOK)
 * - RINGING 시 발신자 번호 → 연락처 조회 → isUnknownCaller 설정
 * - OFFHOOK 기준 실제 통화 시작 시각 → durationMs 계산
 *
 * API 31+ 번호 수신:
 * - TelephonyCallback은 번호를 제공하지 않으므로 BroadcastReceiver(EXTRA_INCOMING_NUMBER)를
 *   병행 등록한다. EXTRA_INCOMING_NUMBER는 READ_CALL_LOG 권한이 필요하다.
 * - TelephonyCallback과 BroadcastReceiver의 타이밍 경쟁을 해소하기 위해,
 *   BroadcastReceiver에서 번호 수신 시 RINGING 상태의 CallContext를 다시 emit한다.
 *   OFFHOOK 이후 도착 시에는 변수만 갱신하여 IDLE 종료 신호에 반영한다.
 *
 * API 26-30 번호 수신:
 * - PhoneStateListener.onCallStateChanged(state, phoneNumber) 파라미터로 직접 수신한다.
 *
 * 신호 방출 시점:
 * - OFFHOOK 진입 시 isUnknownCaller == true 이면 UNKNOWN_CALLER 즉시 방출 (실시간 감지)
 * - IDLE 전환 시 통화 종료 신호 세트 방출 후 빈 리스트로 리셋 (combine 캐시 차단)
 *
 * 로그 태그: SeniorShield-CallMonitor
 */
@Singleton
class RealCallRiskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapper: CallSignalMapper,
    private val contactChecker: CallerContactChecker,
    private val settingsRepository: SettingsRepository,
    private val bankArsRegistry: BankArsRegistry,
    private val sessionTracker: com.example.seniorshield.monitoring.session.RiskSessionTracker,
) : CallRiskMonitor {

    /** 테스트용 시계 주입점. 프로덕션은 System.currentTimeMillis(). */
    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /** 테스트용 SDK 분기 주입점 — API31 executor 경합의 plain-JVM 결정론 재현용 ([clock] 선례). */
    @VisibleForTesting
    internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private fun isApi31OrLater(): Boolean = sdkIntProvider() >= Build.VERSION_CODES.S

    /**
     * [TelephonyManager.EXTRA_STATE_RINGING] 비교값. 실기기 값은 "RINGING"이지만 이 상수는
     * compile-time const가 아니라 단위테스트 stub jar(returnDefaultValues)에서 null이 된다 —
     * null-fallback으로 고정해 기기 동작은 불변, JVM 테스트에서만 실값을 대신한다.
     */
    private val extraStateRinging: String =
        TelephonyManager.EXTRA_STATE_RINGING ?: "RINGING"

    /** 테스트용 callback executor 주입점 — 수동 드레인으로 enqueue↔reset 경합을 재현한다. */
    @VisibleForTesting
    internal var callbackExecutorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() }

    /**
     * distinctUntilChanged()가 OFFHOOK(LIVE)과 IDLE(FINAL)의 동일 신호 리스트를
     * 구분할 수 있도록 phase를 포함하는 내부 wrapper.
     */
    private enum class SignalPhase { LIVE, FINAL, RESET }
    private data class CallSignalEvent(val phase: SignalPhase, val signals: List<RiskSignal>)

    /**
     * 통화 컨텍스트의 생산 provenance (A안, 2026-07-16). envelope는 shareIn **상류**
     * (callbackFlow trySend 시점)에서 완성된다 — replay=1 캐시가 원본 생산 epoch·출처를
     * 자동 승계하는 유일한 구조이며, 하류(수신 시점) 재스탬프는 replay 위장을 끊지 못한다.
     * [origin]=SEED는 등록 시 복원 방출(계약 1: 신호 파생·일회성 부수효과 금지)을 표시한다.
     */
    private enum class ContextOrigin { CALLBACK, SEED }

    private data class SourcedCallState(
        val state: CallMonitorState,
        val producedAtEpoch: Long,
        val origin: ContextOrigin,
    )

    /**
     * RINGING 조회로 확정된 발신자 정보 — **생산 epoch + 통화 회차(generation)에 결속**된다
     * (라운드 14-①·15-②). 소비(전이 컨텍스트 구성)는 전이의 producedAtEpoch와 같은 epoch
     * **그리고** 현재 회차의 generation일 때만 허용된다:
     * - epoch 결속 — 조회가 정상 완료된 뒤 reset이 끼어도 다음 fresh 전이가 reset 전
     *   번호·분류를 재사용하지 못한다 (안전 확인 의미 보존). API31 커밋 epoch는 방송 배달
     *   시점이 아니라 **RINGING 원인 epoch** — reset 전 벨의 늦은 방송이 새 epoch로
     *   위장되는 경로 차단 (라운드 15-②).
     * - 회차 결속 — IDLE(회차 종료) 뒤에 실행되는 늦은 번호 작업이 다음 통화의 분류를
     *   오염시키지 못한다 (IDLE에서 generation 전진 + metadata 무효화).
     * 불일치 시 null 취급(보수적): 해당 통화는 발신자 미상으로 처리된다 — 잔여 한계로 기록.
     */
    private data class CallerMetadata(
        val phoneNumber: String?,
        val isUnknownCaller: Boolean?,
        val isVerifiedCaller: Boolean?,
        val producedAtEpoch: Long,
        val callGeneration: Long,
    )

    /**
     * collector 소유권과 활성 통화 ID의 단일 원자 상태. claim·write·release가 같은 CAS 위에서
     * 결정되어, owner 검사 뒤 종료된 작업의 늦은 callId 기록을 원자적으로 거부한다 (라운드 17).
     */
    private data class CallOwnership(
        val ownerGeneration: Long,
        val currentCallId: Long?,
    )

    @Volatile @VisibleForTesting internal var lastSuspiciousCallEndedAt: Long? = null

    /**
     * 사용자가 안전 확인한 통화의 callId.
     * 다음 IDLE 전이 시 lastSuspiciousCallEndedAt 설정을 1회 스킵한다.
     * IDLE 처리 후 자동 클리어 — 다른 통화에 영향 없음.
     * (B-3 공백 메우기. RealCallRiskMonitor 내부 상태로 국소화 — C-3 미확장.)
     */
    @Volatile private var safeConfirmedCallId: Long? = null

    /** 최근 30분 이내 미확인/미검증 수신 호출 타임스탬프 버퍼 */
    @VisibleForTesting internal val recentUnknownCalls: MutableList<Long> = CopyOnWriteArrayList()

    /**
     * 활성 collector 소유 세대 + 현재 callId. OFFHOOK 진입 시각을 callId로 사용하며, release는
     * owner를 무효화하는 같은 CAS에서 callId도 null로 정리한다. 팝업 snooze의 call-scope
     * 바인딩은 종료된 collector의 ID를 더 이상 신뢰하지 않는다.
     */
    private val callOwnership = AtomicReference(CallOwnership(0L, null))

    /** 새 collector가 소유권을 선점하고 null callId로 시작한다. */
    @VisibleForTesting
    internal fun claimOwnership(): Long {
        while (true) {
            val previous = callOwnership.get()
            val claimed = CallOwnership(previous.ownerGeneration + 1L, null)
            if (callOwnership.compareAndSet(previous, claimed)) return claimed.ownerGeneration
        }
    }

    /** @return false면 소유권 상실로 늦은 callId 기록이 원자 거부되었다. */
    @VisibleForTesting
    internal fun writeCallId(myOwnerGeneration: Long, callId: Long?): Boolean {
        while (true) {
            val previous = callOwnership.get()
            if (previous.ownerGeneration != myOwnerGeneration) return false
            if (callOwnership.compareAndSet(previous, previous.copy(currentCallId = callId))) return true
        }
    }

    /** 소유자 일치 시에만 owner를 무효화하고 callId를 null로 정리한다. */
    @VisibleForTesting
    internal fun releaseOwnership(myOwnerGeneration: Long): Boolean {
        while (true) {
            val previous = callOwnership.get()
            if (previous.ownerGeneration != myOwnerGeneration) return false
            val released = CallOwnership(previous.ownerGeneration + 1L, null)
            if (callOwnership.compareAndSet(previous, released)) return true
        }
    }

    /** shareIn 용 프로세스 수준 스코프. Singleton이므로 앱 종료 시까지 유지. */
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 권한 상태 폴링 ──────────────────────────────────────────────────────────

    /**
     * READ_PHONE_STATE 권한 상태를 [PERMISSION_POLL_INTERVAL_MS] 간격으로 폴링.
     * Android는 권한 변경 콜백을 제공하지 않으므로 폴링이 유일한 방법.
     * distinctUntilChanged()로 실제 전환 시에만 downstream에 방출.
     */
    private fun observePhoneStatePermission(): Flow<Boolean> = flow {
        while (true) {
            emit(hasPhoneStatePermission())
            delay(PERMISSION_POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    // ── observeCallContext (공유 Flow) ───────────────────────────────────────────

    /**
     * 권한 상태에 따라 내부 callbackFlow를 재구독하는 raw flow.
     * - 권한 true: API 버전에 따라 Api31Internal 또는 LegacyInternal 구독
     * - 권한 false: NoPermission emit
     */
    private fun observeCallContextRaw(): Flow<SourcedCallState> =
        observePhoneStatePermission()
            .flatMapLatest { granted ->
                if (granted) {
                    Log.d(TAG, "permission granted — starting internal call monitor")
                    if (isApi31OrLater()) {
                        observeCallContextApi31Internal()
                    } else {
                        observeCallContextLegacyInternal()
                    }
                } else {
                    Log.d(TAG, "permission not granted — emitting NoPermission")
                    // 권한 전환 관측 시점 = 생산 시점 (버퍼 진입 전 캡처)
                    flowOf(
                        SourcedCallState(
                            CallMonitorState.NoPermission,
                            sessionTracker.userResetEpoch,
                            ContextOrigin.CALLBACK,
                        ),
                    )
                }
            }

    /**
     * 다중 구독 시 TelephonyCallback/PhoneStateListener 중복 등록을 방지하는 공유 Flow.
     * replay=1: 새 구독자가 즉시 마지막 상태를 받음.
     * WhileSubscribed(5_000): 모든 구독자 해제 후 5초 뒤 upstream 취소 (callback unregister).
     */
    private val sharedCallContext: Flow<SourcedCallState> by lazy {
        observeCallContextRaw().shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )
    }

    override fun observeCallContext(): Flow<CallMonitorState> = sharedCallContext.map { it.state }

    override fun currentCallId(): Long? = callOwnership.get().currentCallId

    override fun clearTelebankingAnchor() {
        if (lastSuspiciousCallEndedAt == null) return
        Log.d(TAG, "telebanking anchor cleared (user-initiated safe-confirm)")
        lastSuspiciousCallEndedAt = null
    }

    override fun markCurrentCallConfirmedSafe(callId: Long) {
        safeConfirmedCallId = callId
        Log.d(TAG, "current call marked safe-confirmed (callId=$callId): next IDLE anchor will be skipped")
    }

    override fun isTelebankingAnchorHot(): Boolean = isTelebankingWindow()

    // ── observeCallSignals ──────────────────────────────────────────────────────

    override fun observeCallSignals(): Flow<Produced<List<RiskSignal>>> =
        combine(
            sharedCallContext,
            settingsRepository.observeTestModeEnabled(),
        ) { sourced, testMode -> sourced to testMode }
            .flatMapLatest { (sourced, testMode) ->
                val thresholdMs = if (testMode) CallSignalMapper.TEST_LONG_CALL_THRESHOLD_MS else CallSignalMapper.LONG_CALL_THRESHOLD_MS
                val producedAtEpoch = sourced.producedAtEpoch

                // ── 공통 진입 게이트 (계약: stale replay의 내부 부수효과 선차단) ──
                // shareIn replay 등 reset 이전에 생산된 컨텍스트는 어떤 브랜치의 부수효과
                // (반복호출 기록·anchor·safe marker·receiver 소비/clear·CallLog)도 실행하지
                // 못한다 — coordinator의 signalsIfFresh는 방출만 거르고 이 변이는 못 막는다.
                // 방출은 승계 epoch의 중립 RESET 1회 (emptyFlow는 combine 슬롯을 막아 금지).
                if (producedAtEpoch < sessionTracker.userResetEpoch) {
                    Log.d(TAG, "stale call context (epoch=$producedAtEpoch) — side effects skipped")
                    return@flatMapLatest neutralReset(producedAtEpoch)
                }

                // ── SEED 중립화 (계약 1) ──
                // SEED는 상태 기계 복원 전용 — 기존 버퍼 기반 REPEATED 파생·LONG 타이머를 포함한
                // 위험신호 파생과 일회성 부수효과를 전면 건너뛰고 중립 RESET 1회만 방출한다.
                if (sourced.origin == ContextOrigin.SEED) {
                    Log.d(TAG, "SEED context — state restored, signal derivation skipped")
                    return@flatMapLatest neutralReset(producedAtEpoch)
                }

                when (val state = sourced.state) {
                    is CallMonitorState.NoPermission -> {
                        Log.d(TAG, "observeCallSignals: NoPermission — empty signals")
                        neutralReset(producedAtEpoch)
                    }

                    is CallMonitorState.Idle -> neutralReset(producedAtEpoch)

                    is CallMonitorState.Active -> {
                        val ctx = state.context
                        when {
                            // OFFHOOK: 즉시 신호 방출 후 임계 시간 경과 시 LONG_CALL_DURATION 추가
                            // flatMapLatest가 IDLE 전환 시 이 flow를 즉시 취소하므로
                            // 짧은 통화에서는 LONG_CALL_DURATION이 발생하지 않는다.
                            ctx.state == CallState.OFFHOOK && ctx.isOutgoing -> flow {
                                emit(Produced(CallSignalEvent(SignalPhase.LIVE, emptyList()), producedAtEpoch))
                                if (isTelebankingWindow()) {
                                    // ACTION_NEW_OUTGOING_CALL이 OFFHOOK보다 늦게 도착할 수 있으므로
                                    // 선캡처 번호가 없으면 짧은 대기 후 재시도한다.
                                    var number = OutgoingCallReceiver.consumeIfValid()
                                    if (number == null) {
                                        delay(OUTGOING_RECEIVER_WAIT_MS)
                                        // suspend 재개 후 재확인 (계약) — 대기 중 사용자 확인이
                                        // 끼었으면 잔여 조회·방출을 중단한다.
                                        if (sessionTracker.userResetEpoch != producedAtEpoch) return@flow
                                        number = OutgoingCallReceiver.consumeIfValid()
                                        Log.d(TAG, "OFFHOOK 텔레뱅킹 재시도: number=$number (${OUTGOING_RECEIVER_WAIT_MS}ms 대기 후)")
                                    }
                                    val matches = number != null && bankArsRegistry.matches(number)
                                    Log.d(TAG, "OFFHOOK 텔레뱅킹 체크 (선캡처): number=$number, matches=$matches")
                                    if (matches) {
                                        Log.d(TAG, "텔레뱅킹 즉시 감지: number=$number")
                                        emit(Produced(CallSignalEvent(SignalPhase.LIVE, listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS)), producedAtEpoch))
                                    }
                                }
                            }

                            ctx.state == CallState.OFFHOOK -> flow {
                                // ── 수신 통화: 기존 로직 + 반복 호출 감지 ──
                                // (진입 게이트를 통과한 CALLBACK-fresh 컨텍스트만 도달한다.)
                                if (ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) {
                                    recordUnknownCall()
                                }
                                val repeated = isRepeatedUnknownCaller()
                                val immediateSignals = buildList {
                                    if (ctx.isUnknownCaller == true) add(RiskSignal.UNKNOWN_CALLER)
                                    if (repeated) add(RiskSignal.REPEATED_UNKNOWN_CALLER)
                                }
                                emit(Produced(CallSignalEvent(SignalPhase.LIVE, immediateSignals), producedAtEpoch))

                                Log.d(TAG, "통화 임계 대기: ${thresholdMs / 1000}초 (테스트모드=$testMode, repeated=$repeated)")
                                delay(thresholdMs)
                                // one-shot delay 재개 후 재확인 (계약). LONG의 causal epoch는
                                // 타이머를 시작시킨 OFFHOOK ctx의 것을 승계한다 — 발화 시점
                                // 재캡처는 "reset 이전 통화가 reset 이후 epoch를 받는" 원문제의 부활.
                                if (sessionTracker.userResetEpoch != producedAtEpoch) return@flow
                                val signals = buildList {
                                    if (ctx.isUnknownCaller == true) add(RiskSignal.UNKNOWN_CALLER)
                                    add(RiskSignal.LONG_CALL_DURATION)
                                    if (repeated) {
                                        add(RiskSignal.REPEATED_UNKNOWN_CALLER)
                                        add(RiskSignal.REPEATED_CALL_THEN_LONG_TALK)
                                    }
                                }
                                Log.d(TAG, "통화 임계 시간 경과 — signals: $signals")
                                emit(Produced(CallSignalEvent(SignalPhase.LIVE, signals), producedAtEpoch))
                            }

                            // IDLE: 통화 종료 — FINAL phase로 방출 후 RESET으로 캐시 리셋
                            ctx.state == CallState.IDLE && ctx.endedAtMillis != null -> flow {
                                // callId == OFFHOOK 진입 시각(offhookAtMillis) == ctx.startedAtMillis (IDLE-from-OFFHOOK).
                                // callOwnership의 currentCallId는 TelephonyCallback에서 이미 null로 클리어된 상태이므로,
                                // ctx.startedAtMillis를 정답 callId로 사용한다. (RINGING→IDLE은 startedAtMillis=null로 매칭 불가 — B-3 적용 대상 아님.)
                                val callIdAtIdle = ctx.startedAtMillis
                                val confirmedSafe = (safeConfirmedCallId != null && callIdAtIdle != null && safeConfirmedCallId == callIdAtIdle)
                                if ((ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) && !confirmedSafe) {
                                    lastSuspiciousCallEndedAt = ctx.endedAtMillis
                                    Log.d(TAG, "의심 통화 종료 기록: ${ctx.endedAtMillis}")
                                } else if (confirmedSafe) {
                                    Log.d(TAG, "의심 통화 종료지만 사용자 안전 확인 — anchor 스킵 (callId=$callIdAtIdle)")
                                }
                                if (callIdAtIdle != null && safeConfirmedCallId == callIdAtIdle) safeConfirmedCallId = null

                                // ── 발신 종료 시 텔레뱅킹 폴백 ──
                                // OFFHOOK에서 선캡처로 이미 감지한 경우 distinctUntilChanged가 중복 제거.
                                // 선캡처 실패 시 CallLog로 폴백한다.
                                if (ctx.isOutgoing && isTelebankingWindow() && ctx.startedAtMillis != null) {
                                    // 1차: 선캡처 번호
                                    val preCapture = OutgoingCallReceiver.consumeIfValid()
                                    val number = preCapture
                                        ?: queryOutgoingNumberWithRetry(ctx.startedAtMillis, ctx.endedAtMillis)
                                    // CallLog 조회(suspend, 최대 ~2.4s) 재개 후 재확인 (계약) —
                                    // receiver clear·방출 전. stale이면 새 통화의 선캡처를 지우지 않는다.
                                    if (sessionTracker.userResetEpoch != producedAtEpoch) return@flow
                                    val matches = number != null && bankArsRegistry.matches(number)
                                    Log.d(TAG, "IDLE 텔레뱅킹 폴백: preCapture=$preCapture, callLog=${if (preCapture == null) number else "skip"}, matches=$matches")
                                    if (matches) {
                                        emit(Produced(CallSignalEvent(SignalPhase.LIVE, listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS)), producedAtEpoch))
                                    }
                                }

                                // 발신 통화 처리 완료 — 선캡처 번호 정리. 매칭·emit이 도는 동안
                                // 사용자 확인이 끼었을 수 있으므로 clear **직전** 재검증(라운드 12-④)
                                // — stale 경로가 다음 통화의 새 선캡처를 지우지 않는다.
                                if (ctx.isOutgoing && sessionTracker.userResetEpoch == producedAtEpoch) {
                                    OutgoingCallReceiver.clear()
                                }

                                // 수신 통화만 mapper 적용 — 발신은 텔레뱅킹 감지만 해당
                                if (!ctx.isOutgoing) {
                                    val signals = mapper.map(ctx, thresholdMs)
                                    if (signals.isNotEmpty()) {
                                        Log.d(TAG, "signals emitted (FINAL): $signals")
                                        emit(Produced(CallSignalEvent(SignalPhase.FINAL, signals), producedAtEpoch))
                                    }
                                }
                                emit(Produced(CallSignalEvent(SignalPhase.RESET, emptyList()), producedAtEpoch))
                            }

                            else -> neutralReset(producedAtEpoch)
                        }
                    }
                }
            }
            // 계약 3 (라운드 10-11): dUC는 Produced<CallSignalEvent> 단계에서 (phase, signals)만
            // 비교 — epoch 제외(같은 값 재방출 억제 유지), phase 포함(LIVE/FINAL 구분 보존).
            // 그 뒤에 신호 리스트로 변환한다.
            .distinctUntilChanged { a, b -> a.value == b.value }
            .map { Produced(it.value.signals, it.producedAtEpoch) }

    /**
     * 중립 RESET 1회 — 원인 컨텍스트의 epoch를 승계해 스탬프한다 (라운드 10: current-epoch
     * 예외 없음 — post-reset의 실제 IDLE/NoPermission은 컨텍스트 자체가 fresh라 배제 회복이
     * 자연 성립한다). emptyFlow는 combine 슬롯 미충전으로 파이프라인을 정지시키므로 금지.
     */
    private fun neutralReset(producedAtEpoch: Long): Flow<Produced<CallSignalEvent>> =
        flowOf(Produced(CallSignalEvent(SignalPhase.RESET, emptyList()), producedAtEpoch))

    // ── API 31+ Internal (권한 보유 전제) ────────────────────────────────────────

    /**
     * TelephonyCallback + BroadcastReceiver 기반 통화 문맥 수집.
     * 권한은 외부([observeCallContextRaw])에서 보장한다.
     *
     * ## 초기 방출 원자 선점 (라운드 11 계약)
     * callback과 explicit seed가 **같은 단일 executor**에서 [initialClaimed](AtomicBoolean)를
     * 선점하며, 첫 선점자는 항상 SEED(복원 전용)로 처리되어 중립 방출 1회만 낸다 —
     * `previousState` 비교는 첫 callback이 IDLE이면 상태값이 변하지 않아 선점 판정에 쓸 수 없다.
     * 번호 receiver의 상태 접근도 같은 executor로 직렬화한다 (lock 신설 없이 기존 기전 재사용).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun observeCallContextApi31Internal(): Flow<SourcedCallState> = callbackFlow {
        // 이 collector가 소유권을 선점한다 — 이전 collector의 잔존 작업은 세대 불일치로 폐기.
        val myOwnerGeneration = claimOwnership()
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager unavailable")
            trySend(SourcedCallState(CallMonitorState.Idle, sessionTracker.userResetEpoch, ContextOrigin.SEED))
            awaitClose {
                if (!releaseOwnership(myOwnerGeneration)) {
                    Log.d(TAG, "collector release ignored — owner already superseded (API31 manager unavailable)")
                }
            }
            return@callbackFlow
        }

        var previousState = CallState.IDLE
        var offhookAtMillis: Long? = null
        var callerMetadata: CallerMetadata? = null
        // 통화 회차 세대 — IDLE에서 전진 (라운드 15-②: 늦은 번호 작업의 회차 결속).
        // broadcast 스레드(onReceive 진입 캡처)와 executor 간 가시성 때문에 Atomic.
        val callGeneration = AtomicLong(0L)
        // 현재 회차 RINGING의 원인 epoch — 늦은 방송의 커밋은 배달 시점이 아니라 이 epoch에
        // 결속된다 (라운드 15-②). IDLE에서 해제.
        var episodeRingingEpoch: Long? = null
        var isOutgoing = false
        val initialClaimed = AtomicBoolean(false)

        // callback·seed·번호 receiver의 상태 접근을 단일 스레드로 직렬화한다.
        val executor = callbackExecutorFactory()

        // BroadcastReceiver로 수신 번호를 캡처한다.
        // EXTRA_INCOMING_NUMBER는 API 29+에서 READ_CALL_LOG 권한이 필요하다.
        val numberReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // 계약(라운드 12-①·13-①·15-②): epoch·회차 세대는 **진입 즉시**(broadcast
                // 수신 = 생산 시점) 캡처 — intent 해석·enqueue·조회보다 앞선다. 실행/조회
                // 시점에 읽으면 대기·조회 중 reset/IDLE이 끼었을 때 stale 정보가 위장된다.
                val producedAtEpoch = sessionTracker.userResetEpoch
                val taskGeneration = callGeneration.get()
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                if (state != extraStateRinging) return
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?.takeIf { it.isNotBlank() }
                    // READ_PHONE_STATE만 가진 수신자용 중복 방송(번호 extra 없음, 순서 비보장 —
                    // Android 공식 계약)은 무시한다: 조회·커밋하면 이미 확정된 발신자 정보를
                    // UNAVAILABLE로 강등시킨다 (라운드 15-①).
                    ?: return
                val task = Runnable {
                    // 종료/교체된 collector의 작업 무효화 (라운드 16-②).
                    if (callOwnership.get().ownerGeneration != myOwnerGeneration) {
                        Log.d(TAG, "stale number-enrichment task dropped — collector superseded or closed")
                        return@Runnable
                    }
                    // stale 작업 폐기 — caller metadata(연락처 조회 포함)도 변경하지 않는다.
                    if (sessionTracker.userResetEpoch != producedAtEpoch) {
                        Log.d(TAG, "stale number-enrichment task dropped (epoch=$producedAtEpoch)")
                        return@Runnable
                    }
                    // 회차가 끝난(IDLE 경과) 뒤 실행되는 늦은 작업 폐기 — 다음 통화의 분류를
                    // 오염시키지 않는다 (라운드 15-②).
                    if (callGeneration.get() != taskGeneration) {
                        Log.d(TAG, "stale number-enrichment task dropped — call episode ended")
                        return@Runnable
                    }
                    // 조회는 지역에서 — shared caller metadata는 조회 후 재검증을 통과했을 때만
                    // 한 번에 반영한다 (라운드 13-①: 조회 도중 reset 시 이전 번호·분류가
                    // 다음 fresh OFFHOOK에 재사용되는 것 차단).
                    val callerResult = contactChecker.checkCaller(number)
                    if (sessionTracker.userResetEpoch != producedAtEpoch) {
                        Log.d(TAG, "reset during contact lookup — stale caller info discarded")
                        return@Runnable
                    }
                    val unknown = callerResult.toIsUnknownCaller()
                    val verified = callerResult.toIsVerifiedCaller()
                    // 커밋 epoch = RINGING **원인** epoch (미확립 시 배달 시점) — reset 전
                    // 벨의 늦은 방송이 새 epoch를 받아 reset 이후 전이에 섞이는 것 차단.
                    // metadata는 epoch+회차에 결속 커밋된다 (라운드 14-①·15-②).
                    val commitEpoch = episodeRingingEpoch ?: producedAtEpoch
                    callerMetadata = CallerMetadata(number, unknown, verified, commitEpoch, taskGeneration)
                    Log.d(TAG, "incoming number via broadcast, isUnknown=$unknown, isVerified=$verified")

                    // API 31+ 타이밍 경쟁 해소: TelephonyCallback이 번호 없이
                    // RINGING을 먼저 전달한 경우, 번호 확보 시점에 RINGING 컨텍스트를
                    // 다시 emit한다 (RINGING→RINGING 번호 보강 재방출은 보존 — 라운드 11).
                    // OFFHOOK 이후에는 re-emit하지 않는다 —
                    // flatMapLatest가 LONG_CALL_DURATION 타이머를 리셋하기 때문이다.
                    // OFFHOOK 이후 도착 시에는 metadata만 세팅되어 IDLE 종료 신호에 반영된다.
                    // 즉 진행 중 통화의 UNKNOWN_CALLER 즉시 판정은 늦은 번호로 보강되지
                    // 않는다 — 의도적 제한 (라운드 15 권장: 실기 체크 항목).
                    if (previousState == CallState.RINGING) {
                        val updatedCtx = CallContext(
                            state = CallState.RINGING,
                            phoneNumber = number,
                            startedAtMillis = null,
                            endedAtMillis = null,
                            durationMs = 0L,
                            durationSec = 0L,
                            isUnknownCaller = unknown,
                            isVerifiedCaller = verified,
                        )
                        trySend(SourcedCallState(CallMonitorState.Active(updatedCtx), commitEpoch, ContextOrigin.CALLBACK))
                        Log.d(TAG, "re-emitted RINGING context with resolved number")
                    }
                }
                try {
                    executor.execute(task)
                } catch (e: RejectedExecutionException) {
                    // 종료 중(awaitClose가 executor shutdown) 도착한 broadcast — 무해하게 폐기.
                    Log.w(TAG, "number-enrichment task rejected — monitor shutting down")
                }
            }
        }

        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(numberReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(numberReceiver, intentFilter)
        }

        // framework → callback 전달 executor (라운드 16-①): 제출(execute) 진입 시점의 epoch를
        // 캡처해 실행되는 callback까지 승계한다 — 큐 대기 중 reset이 끼면 실행 시점 읽기는
        // reset 전 상태 전이를 새 epoch로 위장시킨다. 단일 스레드 executor 위의 핸드오프
        // 변수라 경합 없음 (대입 직후 같은 스레드에서 callback이 읽는다).
        var deliveredCallbackEpoch = 0L
        val callbackDeliveryExecutor = java.util.concurrent.Executor { command ->
            val submissionEpoch = sessionTracker.userResetEpoch
            try {
                executor.execute {
                    deliveredCallbackEpoch = submissionEpoch
                    command.run()
                }
            } catch (e: RejectedExecutionException) {
                // 종료 중(awaitClose가 executor shutdown) 도착한 callback — 무해하게 폐기.
                Log.w(TAG, "callback delivery rejected — monitor shutting down")
            }
        }

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                // 종료/교체된 collector의 늦은 callback 무효화 (라운드 16-②) — 전역 상태
                // (callOwnership.currentCallId) 변경 전 검증.
                if (callOwnership.get().ownerGeneration != myOwnerGeneration) {
                    Log.d(TAG, "stale callback dropped — collector superseded or closed")
                    return
                }
                // 계약(라운드 16-①): epoch는 executor 제출 시점 캡처값을 승계한다 — 실행
                // 시점 재읽기는 큐 대기 중의 reset을 fresh로 위장시킨다.
                val producedAtEpoch = deliveredCallbackEpoch
                val newState = state.toCallState()
                Log.d(TAG, "state transition (API31+): prev=$previousState new=$newState")
                val claimedInitial = initialClaimed.compareAndSet(false, true)
                // OFFHOOK→OFFHOOK 중복 억제 — 새 callId 조작 방지 (초기 IDLE·번호 보강
                // RINGING 재방출은 보존 — 라운드 11).
                if (!claimedInitial && newState == CallState.OFFHOOK && previousState == CallState.OFFHOOK) {
                    Log.d(TAG, "duplicate OFFHOOK ignored")
                    return
                }
                // 현재 회차 RINGING의 원인 epoch 확립 — 늦은 번호 방송의 커밋 기준 (라운드 15-②).
                if (newState == CallState.RINGING) {
                    episodeRingingEpoch = producedAtEpoch
                }
                val ctx: CallContext? = if (claimedInitial) {
                    // 첫 방출 선점 = SEED 복원 (계약 1) — blind gap 이후의 방향·번호는 알 수 없다.
                    restoreSeedState(
                        newState,
                        onOffhookUpdated = { offhookAtMillis = it },
                        onOutgoingUpdated = { isOutgoing = it },
                    )
                } else {
                    // metadata는 전이와 같은 epoch **그리고** 현재 회차일 때만 소비한다
                    // (라운드 14-①·15-②) — 불일치 시 null(보수적)로 컨텍스트를 구성한다.
                    val meta = callerMetadata?.takeIf {
                        it.producedAtEpoch == producedAtEpoch &&
                            it.callGeneration == callGeneration.get()
                    }
                    buildContext(
                        previousState, newState, offhookAtMillis,
                        meta?.phoneNumber, meta?.isUnknownCaller, meta?.isVerifiedCaller,
                        isOutgoing,
                        onOffhookUpdated = { offhookAtMillis = it },
                        onOutgoingUpdated = { isOutgoing = it },
                    )
                }
                previousState = newState
                when (newState) {
                    CallState.OFFHOOK -> {
                        if (!writeCallId(myOwnerGeneration, offhookAtMillis)) {
                            Log.d(TAG, "currentCallId write rejected — collector superseded or closed")
                            return
                        }
                        Log.d(TAG, "currentCallId set → $offhookAtMillis")
                    }
                    CallState.IDLE -> {
                        if (!writeCallId(myOwnerGeneration, null)) {
                            Log.d(TAG, "currentCallId clear rejected — collector superseded or closed")
                            return
                        }
                        Log.d(TAG, "currentCallId cleared (IDLE)")
                    }
                    else -> {}
                }
                if (newState == CallState.IDLE) {
                    // 회차 경계 — metadata 무효화 + 세대 전진(큐 대기 중인 늦은 번호 작업 폐기).
                    callerMetadata = null
                    episodeRingingEpoch = null
                    callGeneration.incrementAndGet()
                }
                val origin = if (claimedInitial) ContextOrigin.SEED else ContextOrigin.CALLBACK
                if (ctx != null) {
                    trySend(SourcedCallState(CallMonitorState.Active(ctx), producedAtEpoch, origin))
                } else if (claimedInitial) {
                    trySend(SourcedCallState(CallMonitorState.Idle, producedAtEpoch, ContextOrigin.SEED))
                }
            }
        }

        try {
            telephonyManager.registerTelephonyCallback(callbackDeliveryExecutor, callback)
            Log.d(TAG, "TelephonyCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "TelephonyCallback registration failed: ${e.message}")
            trySend(SourcedCallState(CallMonitorState.Idle, sessionTracker.userResetEpoch, ContextOrigin.SEED))
            try { context.unregisterReceiver(numberReceiver) } catch (_: Exception) {}
            awaitClose {
                if (!releaseOwnership(myOwnerGeneration)) {
                    Log.d(TAG, "collector release ignored — owner already superseded (API31 registration failure)")
                }
                executor.shutdownNow()
            }
            return@callbackFlow
        }

        // ── Seed: 현재 통화 상태 복원 — callback executor에 제출해 원자 직렬화 (계약 2) ──
        // TelephonyCallback은 등록 후 상태 전이가 발생해야 콜백을 호출하므로, 권한 false→true
        // 전환 시 진행 중 통화를 놓치지 않기 위해 현재 call state를 SEED로 1회 방출한다.
        // callback이 먼저 initialClaimed를 선점했으면 seed는 폐기 — 초기 방출은 정확히 1회.
        val seedTask = Runnable {
            // 종료/교체된 collector의 seed 무효화 (라운드 16-②).
            if (callOwnership.get().ownerGeneration != myOwnerGeneration) {
                Log.d(TAG, "stale seed task dropped — collector superseded or closed")
                return@Runnable
            }
            if (!initialClaimed.compareAndSet(false, true)) {
                Log.d(TAG, "explicit seed skipped — initial emission already claimed by callback")
                return@Runnable
            }
            // 계약: callState 읽기 **전** epoch 캡처. FGS 5초 초과 재시드는 fresh 처리
            // (A안 제품 결정: blind gap의 새 통화 오차단 금지 — 중복 경고 방향 수용).
            val producedAtEpoch = sessionTracker.userResetEpoch
            @Suppress("DEPRECATION") // callState — seed용 1회 읽기
            val currentCallState = telephonyManager.callState.toCallState()
            Log.d(TAG, "seed current call state: $currentCallState")
            // blind gap 중의 벨을 복원하는 seed — 이후 도착할 번호 방송의 커밋 기준 epoch.
            if (currentCallState == CallState.RINGING) {
                episodeRingingEpoch = producedAtEpoch
            }
            val seedCtx = restoreSeedState(
                currentCallState,
                onOffhookUpdated = { offhookAtMillis = it },
                onOutgoingUpdated = { isOutgoing = it },
            )
            previousState = currentCallState
            if (currentCallState == CallState.OFFHOOK) {
                if (!writeCallId(myOwnerGeneration, offhookAtMillis)) {
                    Log.d(TAG, "seed callId write rejected — collector superseded or closed")
                    return@Runnable
                }
                Log.d(TAG, "currentCallId seeded → $offhookAtMillis")
            } else if (currentCallState == CallState.IDLE) {
                if (!writeCallId(myOwnerGeneration, null)) {
                    Log.d(TAG, "seed callId clear rejected — collector superseded or closed")
                    return@Runnable
                }
            }
            if (seedCtx != null) {
                trySend(SourcedCallState(CallMonitorState.Active(seedCtx), producedAtEpoch, ContextOrigin.SEED))
                Log.d(TAG, "seeded in-progress call: state=$currentCallState")
            } else {
                trySend(SourcedCallState(CallMonitorState.Idle, producedAtEpoch, ContextOrigin.SEED)) // Initial idle
            }
        }
        try {
            executor.execute(seedTask)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "seed task rejected — monitor shutting down")
        }

        awaitClose {
            // 소유 세대 무효화가 1차 방어 — shutdownNow는 이미 실행 단계에 들어간 작업을
            // 못 막는다 (Java ExecutorService 계약, 라운드 16-②).
            if (!releaseOwnership(myOwnerGeneration)) {
                Log.d(TAG, "collector release ignored — owner already superseded (API31)")
            }
            telephonyManager.unregisterTelephonyCallback(callback)
            try { context.unregisterReceiver(numberReceiver) } catch (_: Exception) {}
            executor.shutdownNow()
            Log.d(TAG, "TelephonyCallback unregistered")
        }
    }

    // ── API 26-30 Internal (권한 보유 전제) ──────────────────────────────────────

    /**
     * PhoneStateListener 기반 통화 문맥 수집.
     * 권한은 외부([observeCallContextRaw])에서 보장한다.
     * PhoneStateListener.listen()은 등록 시 현재 상태로 즉시 콜백을 호출한다(암묵 seed) —
     * 첫 콜백을 [initialClaimed] 선점으로 SEED 처리해 API31 명시 seed와 규칙을 단일화한다
     * (라운드 11 계약; main looper가 콜백을 직렬화하므로 별도 executor 불필요).
     */
    @Suppress("DEPRECATION")
    private fun observeCallContextLegacyInternal(): Flow<SourcedCallState> = callbackFlow {
        // 이 collector가 소유권을 선점한다 (라운드 16-②) — unregister 후에도 main looper에
        // 남은 늦은 콜백이 전역 상태를 변경하지 못한다.
        val myOwnerGeneration = claimOwnership()
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager unavailable")
            trySend(SourcedCallState(CallMonitorState.Idle, sessionTracker.userResetEpoch, ContextOrigin.SEED))
            awaitClose {
                if (!releaseOwnership(myOwnerGeneration)) {
                    Log.d(TAG, "collector release ignored — owner already superseded (legacy manager unavailable)")
                }
            }
            return@callbackFlow
        }

        var previousState = CallState.IDLE
        var offhookAtMillis: Long? = null
        var callerMetadata: CallerMetadata? = null
        // 통화 회차 세대 — IDLE에서 전진 (라운드 15-②). legacy는 단일 콜백 채널(main looper
        // 직렬)이라 별도 원인-epoch 저장이 불필요하다 — RINGING 커밋 epoch가 곧 원인 epoch.
        var callGeneration = 0L
        var isOutgoing = false
        val initialClaimed = AtomicBoolean(false)

        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                // 종료/교체된 collector의 늦은 callback 무효화 (라운드 16-②).
                if (callOwnership.get().ownerGeneration != myOwnerGeneration) {
                    Log.d(TAG, "stale callback dropped — collector superseded or closed (legacy)")
                    return
                }
                // 계약: 연락처 조회·상태 변이 전 epoch 캡처. (main looper 단일 채널 — looper 큐
                // 대기 창은 API26-30 잔여 한계로 기록; 라운드 16-①은 API31 executor 경로.)
                val producedAtEpoch = sessionTracker.userResetEpoch
                val newState = state.toCallState()
                Log.d(TAG, "state transition (legacy): prev=$previousState new=$newState")
                val claimedInitial = initialClaimed.compareAndSet(false, true)
                // OFFHOOK→OFFHOOK 중복 억제 — 새 callId 조작 방지 (라운드 11).
                if (!claimedInitial && newState == CallState.OFFHOOK && previousState == CallState.OFFHOOK) {
                    Log.d(TAG, "duplicate OFFHOOK ignored (legacy)")
                    return
                }

                // RINGING: 번호 캡처 후 연락처 조회 — 상태 복원의 일부이므로 SEED에서도 수행
                // (이후 실제 전이의 신호 파생 근거가 된다; 파생 자체는 SEED 게이트가 차단).
                // 조회는 지역에서 수행하고, 조회 후 재검증을 통과했을 때만 shared에 한 번에
                // 반영한다 (라운드 13-①: 조회 도중 reset 시 이전 번호·분류가 다음 fresh
                // OFFHOOK에 재사용되는 것 차단 — 미반영 시 caller 정보는 null로 남아 보수적).
                if (newState == CallState.RINGING) {
                    val number = phoneNumber?.takeIf { it.isNotBlank() }
                    val callerResult = contactChecker.checkCaller(number)
                    if (sessionTracker.userResetEpoch == producedAtEpoch) {
                        val unknown = callerResult.toIsUnknownCaller()
                        val verified = callerResult.toIsVerifiedCaller()
                        // metadata는 생산 epoch+회차에 결속 커밋된다 (라운드 14-①·15-②).
                        callerMetadata = CallerMetadata(number, unknown, verified, producedAtEpoch, callGeneration)
                        Log.d(TAG, "incoming number (legacy), isUnknown=$unknown, isVerified=$verified")
                    } else {
                        Log.d(TAG, "reset during contact lookup (legacy) — stale caller info discarded")
                    }
                }

                val ctx: CallContext? = if (claimedInitial) {
                    // 암묵 seed(등록 즉시 현재 상태 콜백) = SEED 복원 (계약 1).
                    restoreSeedState(
                        newState,
                        onOffhookUpdated = { offhookAtMillis = it },
                        onOutgoingUpdated = { isOutgoing = it },
                    )
                } else {
                    // metadata는 전이와 같은 epoch **그리고** 현재 회차일 때만 소비한다
                    // (라운드 14-①·15-②).
                    val meta = callerMetadata?.takeIf {
                        it.producedAtEpoch == producedAtEpoch && it.callGeneration == callGeneration
                    }
                    buildContext(
                        previousState, newState, offhookAtMillis,
                        meta?.phoneNumber, meta?.isUnknownCaller, meta?.isVerifiedCaller,
                        isOutgoing,
                        onOffhookUpdated = { offhookAtMillis = it },
                        onOutgoingUpdated = { isOutgoing = it },
                    )
                }
                previousState = newState

                when (newState) {
                    CallState.OFFHOOK -> {
                        if (!writeCallId(myOwnerGeneration, offhookAtMillis)) {
                            Log.d(TAG, "currentCallId write rejected — collector superseded or closed (legacy)")
                            return
                        }
                        Log.d(TAG, "currentCallId set → $offhookAtMillis")
                    }
                    CallState.IDLE -> {
                        if (!writeCallId(myOwnerGeneration, null)) {
                            Log.d(TAG, "currentCallId clear rejected — collector superseded or closed (legacy)")
                            return
                        }
                        Log.d(TAG, "currentCallId cleared (IDLE)")
                    }
                    else -> {}
                }

                // IDLE 전환 후 리셋 (buildContext가 먼저 값을 사용한 뒤 초기화) — 회차 경계.
                if (newState == CallState.IDLE) {
                    callerMetadata = null
                    callGeneration++
                }

                val origin = if (claimedInitial) ContextOrigin.SEED else ContextOrigin.CALLBACK
                if (ctx != null) {
                    trySend(SourcedCallState(CallMonitorState.Active(ctx), producedAtEpoch, origin))
                } else if (claimedInitial) {
                    trySend(SourcedCallState(CallMonitorState.Idle, producedAtEpoch, ContextOrigin.SEED))
                }
            }
        }

        @Suppress("DEPRECATION")
        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "PhoneStateListener registration failed: ${e.message}")
            if (!releaseOwnership(myOwnerGeneration)) {
                Log.d(TAG, "collector release ignored — owner already superseded (legacy registration failure)")
            }
            trySend(SourcedCallState(CallMonitorState.Idle, sessionTracker.userResetEpoch, ContextOrigin.SEED))
            awaitClose { }
            return@callbackFlow
        }
        Log.d(TAG, "PhoneStateListener registered")

        awaitClose {
            if (!releaseOwnership(myOwnerGeneration)) {
                Log.d(TAG, "collector release ignored — owner already superseded (legacy)")
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            Log.d(TAG, "PhoneStateListener unregistered")
        }
    }

    // ── 상태 전이 처리 (공유) ────────────────────────────────────────────────

    /**
     * SEED 복원 컨텍스트 (계약 1): 현재 통화 상태만 복원한다 — 방향([CallContext.isOutgoing])은
     * blind gap 이후 알 수 없으므로 **추론하지 않고 false 고정**(발신 텔레뱅킹 분기·IDLE 발신
     * 폴백이 SEED발 통화에서 실행되지 않는다 — 잔여 한계로 기록). 번호·발신자 정보 없음.
     * 신호 파생·일회성 부수효과는 하류 SEED 게이트가 전면 차단한다.
     */
    private fun restoreSeedState(
        state: CallState,
        onOffhookUpdated: (Long?) -> Unit,
        onOutgoingUpdated: (Boolean) -> Unit,
    ): CallContext? = when (state) {
        CallState.OFFHOOK -> {
            val now = clock()
            onOffhookUpdated(now)
            onOutgoingUpdated(false)
            Log.d(TAG, "seed restore: in-progress call (OFFHOOK), direction unknown → isOutgoing=false")
            CallContext(
                state = CallState.OFFHOOK,
                phoneNumber = null,
                startedAtMillis = now,
                endedAtMillis = null,
                durationMs = 0L,
                durationSec = 0L,
                isUnknownCaller = null,
                isVerifiedCaller = null,
                isOutgoing = false,
            )
        }

        CallState.RINGING -> CallContext(
            state = CallState.RINGING,
            phoneNumber = null,
            startedAtMillis = null,
            endedAtMillis = null,
            durationMs = 0L,
            durationSec = 0L,
            isUnknownCaller = null,
            isVerifiedCaller = null,
        )

        CallState.IDLE -> {
            onOffhookUpdated(null)
            onOutgoingUpdated(false)
            null
        }
    }

    private fun buildContext(
        previous: CallState,
        next: CallState,
        offhookAtMillis: Long?,
        phoneNumber: String?,
        isUnknownCaller: Boolean?,
        isVerifiedCaller: Boolean?,
        isOutgoing: Boolean,
        onOffhookUpdated: (Long?) -> Unit,
        onOutgoingUpdated: (Boolean) -> Unit,
    ): CallContext? = when (next) {
        CallState.RINGING -> CallContext(
            state = CallState.RINGING,
            phoneNumber = phoneNumber,
            startedAtMillis = null,
            endedAtMillis = null,
            durationMs = 0L,
            durationSec = 0L,
            isUnknownCaller = isUnknownCaller,
            isVerifiedCaller = isVerifiedCaller,
        )

        CallState.OFFHOOK -> {
            val now = clock()
            onOffhookUpdated(now)
            val outgoing = previous == CallState.IDLE
            onOutgoingUpdated(outgoing)
            Log.d(TAG, "call connected, startedAtMillis=$now, isOutgoing=$outgoing, isUnknownCaller=$isUnknownCaller, isVerifiedCaller=$isVerifiedCaller")
            CallContext(
                state = CallState.OFFHOOK,
                phoneNumber = phoneNumber,
                startedAtMillis = now,
                endedAtMillis = null,
                durationMs = 0L,
                durationSec = 0L,
                isUnknownCaller = isUnknownCaller,
                isVerifiedCaller = isVerifiedCaller,
                isOutgoing = outgoing,
            )
        }

        CallState.IDLE -> {
            val now = clock()
            when (previous) {
                CallState.OFFHOOK -> {
                    val durationMs = offhookAtMillis?.let { now - it } ?: 0L
                    val durationSec = durationMs / 1000L
                    Log.d(TAG, "call ended (OFFHOOK→IDLE), durationMs=$durationMs, durationSec=$durationSec, isUnknownCaller=$isUnknownCaller, isVerifiedCaller=$isVerifiedCaller, isOutgoing=$isOutgoing")
                    onOffhookUpdated(null)
                    onOutgoingUpdated(false)
                    CallContext(
                        state = CallState.IDLE,
                        phoneNumber = phoneNumber,
                        startedAtMillis = offhookAtMillis,
                        endedAtMillis = now,
                        durationMs = durationMs,
                        durationSec = durationSec,
                        isUnknownCaller = isUnknownCaller,
                        isVerifiedCaller = isVerifiedCaller,
                        isOutgoing = isOutgoing,
                    )
                }
                CallState.RINGING -> {
                    Log.d(TAG, "missed/rejected call (RINGING→IDLE)")
                    onOffhookUpdated(null)
                    onOutgoingUpdated(false)
                    CallContext(
                        state = CallState.IDLE,
                        phoneNumber = phoneNumber,
                        startedAtMillis = null,
                        endedAtMillis = now,
                        durationMs = 0L,
                        durationSec = 0L,
                        isUnknownCaller = isUnknownCaller,
                        isVerifiedCaller = isVerifiedCaller,
                        isOutgoing = isOutgoing,
                    )
                }
                CallState.IDLE -> null
            }
        }
    }

    // ── 공통 유틸 ─────────────────────────────────────────────────────────────

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

    private fun Int.toCallState(): CallState = when (this) {
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
        else -> CallState.IDLE
    }

    /** 30분 초과 항목을 정리하고 반복 호출 여부를 반환한다. */
    @VisibleForTesting
    internal fun isRepeatedUnknownCaller(): Boolean {
        val cutoff = clock() - REPEATED_CALL_WINDOW_MS
        recentUnknownCalls.removeAll { it < cutoff }
        return recentUnknownCalls.size >= 2
    }

    /** 미확인/미검증 수신 호출을 버퍼에 기록한다. */
    @VisibleForTesting
    internal fun recordUnknownCall() {
        // 세션이 없으면 이전 버퍼를 초기화 (안전 확인 후 클린 슬레이트)
        if (sessionTracker.sessionState.value == null) {
            recentUnknownCalls.clear()
            lastSuspiciousCallEndedAt = null
        }
        val now = clock()
        val cutoff = now - REPEATED_CALL_WINDOW_MS
        recentUnknownCalls.removeAll { it < cutoff }
        recentUnknownCalls.add(now)
        Log.d(TAG, "미확인 호출 기록: count=${recentUnknownCalls.size}")
    }

    /** 텔레뱅킹 윈도우: 의심 통화 종료 후 5분 이내. anchor가 null이면 false. */
    @VisibleForTesting
    internal fun isTelebankingWindow(): Boolean {
        val lastSuspicious = lastSuspiciousCallEndedAt ?: return false
        return clock() - lastSuspicious <= TELEBANKING_WINDOW_MS
    }

    /**
     * 발신 통화 종료 후 CallLog에서 번호를 조회한다.
     * CallLog 기록 반영 지연을 고려해 최대 3회 재시도한다.
     */
    private suspend fun queryOutgoingNumberWithRetry(
        startedAtMillis: Long,
        endedAtMillis: Long,
    ): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CALL_LOG 권한 없음 — 발신 번호 조회 불가")
            return null
        }
        repeat(CALL_LOG_QUERY_MAX_RETRIES) { attempt ->
            val number = queryVerifiedOutgoingNumber(startedAtMillis, endedAtMillis)
            if (number != null) {
                Log.d(TAG, "CallLog 발신 번호 확인 (시도 ${attempt + 1}): $number")
                return number
            }
            if (attempt < CALL_LOG_QUERY_MAX_RETRIES - 1) {
                Log.d(TAG, "CallLog 미반영 — ${CALL_LOG_QUERY_RETRY_DELAY_MS}ms 후 재시도 (${attempt + 1}/$CALL_LOG_QUERY_MAX_RETRIES)")
                delay(CALL_LOG_QUERY_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "CallLog 발신 번호 조회 실패 — $CALL_LOG_QUERY_MAX_RETRIES 회 시도 후 포기")
        return null
    }

    /**
     * CallLog에서 이번 발신 통화와 가장 잘 맞는 기록 1건의 번호를 반환한다.
     *
     * 매칭 전략:
     * 1. OUTGOING_TYPE + DATE가 startedAtMillis ± [CALL_LOG_START_TOLERANCE_MS] 범위인 후보를 조회
     * 2. 후보 중 (DATE + DURATION*1000)이 endedAtMillis에 가장 근접한 건을 선택
     * 3. DURATION==0이거나 종료 시각 검증 실패 시, startedAtMillis에 가장 근접한 최신 1건으로 폴백
     */
    private fun queryVerifiedOutgoingNumber(
        startedAtMillis: Long,
        endedAtMillis: Long,
    ): String? {
        return try {
            val windowStart = startedAtMillis - CALL_LOG_START_TOLERANCE_MS
            val windowEnd = startedAtMillis + CALL_LOG_START_TOLERANCE_MS
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(
                    android.provider.CallLog.Calls.NUMBER,
                    android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION,
                ),
                "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(
                    android.provider.CallLog.Calls.OUTGOING_TYPE.toString(),
                    windowStart.toString(),
                    windowEnd.toString(),
                ),
                "${android.provider.CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val colNumber = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)
                val colDate = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE)
                val colDuration = cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DURATION)

                var bestNumber: String? = null
                var bestEndDelta = Long.MAX_VALUE
                var fallbackNumber: String? = null  // DURATION==0 또는 종료 검증 실패 시 최신 1건

                do {
                    val number = cursor.getString(colNumber) ?: continue
                    val date = cursor.getLong(colDate)
                    val durationSec = cursor.getLong(colDuration)

                    // 폴백: startedAtMillis에 가장 근접한 최신 1건 (DATE DESC이므로 첫 건)
                    if (fallbackNumber == null) fallbackNumber = number

                    if (durationSec > 0) {
                        val computedEnd = date + durationSec * 1000
                        val delta = kotlin.math.abs(computedEnd - endedAtMillis)
                        if (delta < bestEndDelta) {
                            bestEndDelta = delta
                            bestNumber = number
                        }
                    }
                } while (cursor.moveToNext())

                // 종료 시각 검증: bestEndDelta가 허용 범위 내이면 채택, 아니면 폴백
                if (bestNumber != null && bestEndDelta <= CALL_LOG_END_TOLERANCE_MS) {
                    Log.d(TAG, "CallLog 종료시각 검증 통과: endDelta=${bestEndDelta}ms")
                    bestNumber
                } else {
                    if (fallbackNumber != null) {
                        Log.d(TAG, "CallLog 종료시각 검증 실패 또는 DURATION=0 — 최신 후보로 폴백")
                    }
                    fallbackNumber
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog 조회 실패: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SeniorShield-CallMonitor"
        private const val TELEBANKING_WINDOW_MS = 5 * 60 * 1000L // 텔레뱅킹 감지 윈도우: 5분
        private const val CALL_LOG_QUERY_MAX_RETRIES = 3          // CallLog 조회 재시도 횟수
        private const val CALL_LOG_QUERY_RETRY_DELAY_MS = 800L    // 재시도 간격
        private const val CALL_LOG_START_TOLERANCE_MS = 5_000L    // startedAtMillis 기준 DATE 허용 범위
        private const val CALL_LOG_END_TOLERANCE_MS = 3_000L      // (DATE+DURATION*1000) vs endedAtMillis 허용 오차
        private const val REPEATED_CALL_WINDOW_MS = 30 * 60 * 1000L // 반복 호출 판단 윈도우: 30분
        private const val PERMISSION_POLL_INTERVAL_MS = 3_000L   // 권한 폴링 간격: 3초
        private const val OUTGOING_RECEIVER_WAIT_MS = 300L       // OutgoingCallReceiver 브로드캐스트 대기
    }
}

// ── CallerCheckResult 매핑 헬퍼 ─────────────────────────────────────────────

/**
 * UNKNOWN_CALLER 신호 판정용.
 * NOT_IN_CONTACTS → true (미저장), 그 외 → false/null
 */
private fun CallerCheckResult.toIsUnknownCaller(): Boolean? = when (this) {
    CallerCheckResult.NOT_IN_CONTACTS -> true
    CallerCheckResult.NEW_CONTACT -> false
    CallerCheckResult.VERIFIED_CONTACT -> false
    CallerCheckResult.UNAVAILABLE -> null
}

/**
 * UNVERIFIED_CALLER 신호 판정용.
 * NEW_CONTACT → false (미검증), VERIFIED_CONTACT → true (검증됨),
 * NOT_IN_CONTACTS → null (unknown이므로 verified 판단 불필요),
 * UNAVAILABLE → null
 */
private fun CallerCheckResult.toIsVerifiedCaller(): Boolean? = when (this) {
    CallerCheckResult.NOT_IN_CONTACTS -> null
    CallerCheckResult.NEW_CONTACT -> false
    CallerCheckResult.VERIFIED_CONTACT -> true
    CallerCheckResult.UNAVAILABLE -> null
}
