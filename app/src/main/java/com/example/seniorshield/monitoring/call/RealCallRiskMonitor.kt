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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.SettingsRepository
import com.example.seniorshield.monitoring.model.CallContext
import com.example.seniorshield.monitoring.model.CallMonitorState
import com.example.seniorshield.monitoring.model.CallState
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
import java.util.concurrent.Executors
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

    /**
     * distinctUntilChanged()가 OFFHOOK(LIVE)과 IDLE(FINAL)의 동일 신호 리스트를
     * 구분할 수 있도록 phase를 포함하는 내부 wrapper.
     */
    private enum class SignalPhase { LIVE, FINAL, RESET }
    private data class CallSignalEvent(val phase: SignalPhase, val signals: List<RiskSignal>)

    @Volatile private var lastSuspiciousCallEndedAt: Long? = null

    /** 최근 30분 이내 미확인/미검증 수신 호출 타임스탬프 버퍼 */
    private val recentUnknownCalls: MutableList<Long> = CopyOnWriteArrayList()

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
    private fun observeCallContextRaw(): Flow<CallMonitorState> =
        observePhoneStatePermission()
            .flatMapLatest { granted ->
                if (granted) {
                    Log.d(TAG, "permission granted — starting internal call monitor")
                    val internal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        observeCallContextApi31Internal()
                    else observeCallContextLegacyInternal()
                    internal.map { ctx ->
                        if (ctx != null) CallMonitorState.Active(ctx) else CallMonitorState.Idle
                    }
                } else {
                    Log.d(TAG, "permission not granted — emitting NoPermission")
                    flowOf(CallMonitorState.NoPermission)
                }
            }

    /**
     * 다중 구독 시 TelephonyCallback/PhoneStateListener 중복 등록을 방지하는 공유 Flow.
     * replay=1: 새 구독자가 즉시 마지막 상태를 받음.
     * WhileSubscribed(5_000): 모든 구독자 해제 후 5초 뒤 upstream 취소 (callback unregister).
     */
    private val sharedCallContext: Flow<CallMonitorState> by lazy {
        observeCallContextRaw().shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )
    }

    override fun observeCallContext(): Flow<CallMonitorState> = sharedCallContext

    // ── observeCallSignals ──────────────────────────────────────────────────────

    override fun observeCallSignals(): Flow<List<RiskSignal>> =
        combine(
            observeCallContext(),
            settingsRepository.observeTestModeEnabled(),
        ) { state, testMode -> state to testMode }
            .flatMapLatest { (state, testMode) ->
                val thresholdMs = if (testMode) TEST_LONG_CALL_THRESHOLD_MS else LONG_CALL_THRESHOLD_MS
                when (state) {
                    is CallMonitorState.NoPermission -> {
                        Log.d(TAG, "observeCallSignals: NoPermission — empty signals")
                        flowOf(CallSignalEvent(SignalPhase.RESET, emptyList()))
                    }

                    is CallMonitorState.Idle ->
                        flowOf(CallSignalEvent(SignalPhase.RESET, emptyList()))

                    is CallMonitorState.Active -> {
                        val ctx = state.context
                        when {
                            // OFFHOOK: 즉시 신호 방출 후 임계 시간 경과 시 LONG_CALL_DURATION 추가
                            // flatMapLatest가 IDLE 전환 시 이 flow를 즉시 취소하므로
                            // 짧은 통화에서는 LONG_CALL_DURATION이 발생하지 않는다.
                            ctx.state == CallState.OFFHOOK && ctx.isOutgoing ->
                                flowOf(CallSignalEvent(SignalPhase.LIVE, emptyList()))

                            ctx.state == CallState.OFFHOOK -> flow<CallSignalEvent> {
                                // ── 수신 통화: 기존 로직 + 반복 호출 감지 ──
                                if (ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) {
                                    recordUnknownCall()
                                }
                                val repeated = isRepeatedUnknownCaller()
                                val immediateSignals = buildList {
                                    if (ctx.isUnknownCaller == true) add(RiskSignal.UNKNOWN_CALLER)
                                    if (repeated) add(RiskSignal.REPEATED_UNKNOWN_CALLER)
                                }
                                emit(CallSignalEvent(SignalPhase.LIVE, immediateSignals))

                                Log.d(TAG, "통화 임계 대기: ${thresholdMs / 1000}초 (테스트모드=$testMode, repeated=$repeated)")
                                delay(thresholdMs)
                                val signals = buildList {
                                    if (ctx.isUnknownCaller == true) add(RiskSignal.UNKNOWN_CALLER)
                                    add(RiskSignal.LONG_CALL_DURATION)
                                    if (repeated) {
                                        add(RiskSignal.REPEATED_UNKNOWN_CALLER)
                                        add(RiskSignal.REPEATED_CALL_THEN_LONG_TALK)
                                    }
                                }
                                Log.d(TAG, "통화 임계 시간 경과 — signals: $signals")
                                emit(CallSignalEvent(SignalPhase.LIVE, signals))
                            }

                            // IDLE: 통화 종료 — FINAL phase로 방출 후 RESET으로 캐시 리셋
                            ctx.state == CallState.IDLE && ctx.endedAtMillis != null -> flow<CallSignalEvent> {
                                if (ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) {
                                    lastSuspiciousCallEndedAt = ctx.endedAtMillis
                                    Log.d(TAG, "의심 통화 종료 기록: ${ctx.endedAtMillis}")
                                }

                                // ── 발신 종료 시 텔레뱅킹 감지 ──
                                // CallLog는 통화 종료 후 기록되므로, IDLE 시점에 조회해야 정확
                                if (ctx.isOutgoing && isTelebankingWindow() && ctx.startedAtMillis != null) {
                                    val dialedNumber = queryOutgoingNumberWithRetry(
                                        ctx.startedAtMillis, ctx.endedAtMillis,
                                    )
                                    val matches = dialedNumber != null && bankArsRegistry.matches(dialedNumber)
                                    Log.d(TAG, "발신 종료 텔레뱅킹 체크: number=$dialedNumber, matches=$matches")
                                    if (matches) {
                                        Log.d(TAG, "텔레뱅킹 감지: number=$dialedNumber")
                                        emit(CallSignalEvent(SignalPhase.LIVE, listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS)))
                                    }
                                }

                                val signals = mapper.map(ctx, thresholdMs)
                                if (signals.isNotEmpty()) {
                                    Log.d(TAG, "signals emitted (FINAL): $signals")
                                    emit(CallSignalEvent(SignalPhase.FINAL, signals))
                                }
                                emit(CallSignalEvent(SignalPhase.RESET, emptyList()))
                            }

                            else -> flowOf(CallSignalEvent(SignalPhase.RESET, emptyList()))
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .map { it.signals }

    // ── API 31+ Internal (권한 보유 전제) ────────────────────────────────────────

    /**
     * TelephonyCallback + BroadcastReceiver 기반 통화 문맥 수집.
     * 권한은 외부([observeCallContextRaw])에서 보장한다.
     * 등록 직후 현재 통화 상태를 1회 seed하여 진행 중 통화를 놓치지 않는다.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun observeCallContextApi31Internal(): Flow<CallContext?> = callbackFlow {
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager unavailable")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        var previousState = CallState.IDLE
        var offhookAtMillis: Long? = null
        var capturedPhoneNumber: String? = null
        var isUnknownCaller: Boolean? = null
        var isVerifiedCaller: Boolean? = null

        // BroadcastReceiver로 수신 번호를 캡처한다.
        // EXTRA_INCOMING_NUMBER는 API 29+에서 READ_CALL_LOG 권한이 필요하다.
        val numberReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        ?.takeIf { it.isNotBlank() }
                    capturedPhoneNumber = number
                    val callerResult = contactChecker.checkCaller(number)
                    isUnknownCaller = callerResult.toIsUnknownCaller()
                    isVerifiedCaller = callerResult.toIsVerifiedCaller()
                    Log.d(TAG, "incoming number via broadcast, isUnknown=$isUnknownCaller, isVerified=$isVerifiedCaller")

                    // API 31+ 타이밍 경쟁 해소: TelephonyCallback이 번호 없이
                    // RINGING을 먼저 전달한 경우, 번호 확보 시점에 RINGING 컨텍스트를
                    // 다시 emit한다. OFFHOOK 이후에는 re-emit하지 않는다 —
                    // flatMapLatest가 LONG_CALL_DURATION 타이머를 리셋하기 때문이다.
                    // OFFHOOK 이후 도착 시에는 변수만 세팅되어 IDLE 종료 신호에 반영된다.
                    if (number != null && previousState == CallState.RINGING) {
                        val updatedCtx = CallContext(
                            state = CallState.RINGING,
                            phoneNumber = number,
                            startedAtMillis = null,
                            endedAtMillis = null,
                            durationMs = 0L,
                            durationSec = 0L,
                            isUnknownCaller = isUnknownCaller,
                            isVerifiedCaller = isVerifiedCaller,
                        )
                        trySend(updatedCtx)
                        Log.d(TAG, "re-emitted RINGING context with resolved number")
                    }
                }
            }
        }

        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(numberReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(numberReceiver, intentFilter)
        }

        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val newState = state.toCallState()
                Log.d(TAG, "state transition (API31+): prev=$previousState new=$newState")
                val ctx = buildContext(
                    previousState, newState, offhookAtMillis,
                    capturedPhoneNumber, isUnknownCaller, isVerifiedCaller,
                ) { offhookAtMillis = it }
                previousState = newState
                if (newState == CallState.IDLE) {
                    capturedPhoneNumber = null
                    isUnknownCaller = null
                    isVerifiedCaller = null
                }
                if (ctx != null) trySend(ctx)
            }
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            telephonyManager.registerTelephonyCallback(executor, callback)
            Log.d(TAG, "TelephonyCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "TelephonyCallback registration failed: ${e.message}")
            trySend(null)
            try { context.unregisterReceiver(numberReceiver) } catch (_: Exception) {}
            awaitClose { executor.shutdown() }
            return@callbackFlow
        }

        // ── Seed: 현재 통화 상태를 1회 읽어 초기값으로 emit ──
        // TelephonyCallback은 등록 후 상태 전이가 발생해야 콜백을 호출한다.
        // 권한 false→true 전환 시 이미 진행 중인 통화를 놓치지 않기 위해
        // 현재 call state를 즉시 읽고 emit한다.
        @Suppress("DEPRECATION") // callState — seed용 1회 읽기
        val currentCallState = telephonyManager.callState.toCallState()
        Log.d(TAG, "seed current call state: $currentCallState")
        if (currentCallState != CallState.IDLE) {
            val seedCtx = buildContext(
                CallState.IDLE, currentCallState, offhookAtMillis,
                capturedPhoneNumber, isUnknownCaller, isVerifiedCaller,
            ) { offhookAtMillis = it }
            previousState = currentCallState
            if (seedCtx != null) {
                trySend(seedCtx)
                Log.d(TAG, "seeded in-progress call: state=$currentCallState")
            }
        } else {
            trySend(null) // Initial idle
        }

        awaitClose {
            telephonyManager.unregisterTelephonyCallback(callback)
            executor.shutdown()
            try { context.unregisterReceiver(numberReceiver) } catch (_: Exception) {}
            Log.d(TAG, "TelephonyCallback unregistered")
        }
    }

    // ── API 26-30 Internal (권한 보유 전제) ──────────────────────────────────────

    /**
     * PhoneStateListener 기반 통화 문맥 수집.
     * 권한은 외부([observeCallContextRaw])에서 보장한다.
     * PhoneStateListener.listen()은 등록 시 현재 상태로 즉시 콜백을 호출하므로
     * 별도 seed가 불필요하다.
     */
    @Suppress("DEPRECATION")
    private fun observeCallContextLegacyInternal(): Flow<CallContext?> = callbackFlow {
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager unavailable")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        var previousState = CallState.IDLE
        var offhookAtMillis: Long? = null
        var capturedPhoneNumber: String? = null
        var isUnknownCaller: Boolean? = null
        var isVerifiedCaller: Boolean? = null

        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val newState = state.toCallState()
                Log.d(TAG, "state transition (legacy): prev=$previousState new=$newState")

                // RINGING: 번호 캡처 후 연락처 조회
                if (newState == CallState.RINGING) {
                    val number = phoneNumber?.takeIf { it.isNotBlank() }
                    capturedPhoneNumber = number
                    val callerResult = contactChecker.checkCaller(number)
                    isUnknownCaller = callerResult.toIsUnknownCaller()
                    isVerifiedCaller = callerResult.toIsVerifiedCaller()
                    Log.d(TAG, "incoming number (legacy), isUnknown=$isUnknownCaller, isVerified=$isVerifiedCaller")
                }

                val ctx = buildContext(
                    previousState, newState, offhookAtMillis,
                    capturedPhoneNumber, isUnknownCaller, isVerifiedCaller,
                ) { offhookAtMillis = it }
                previousState = newState

                // IDLE 전환 후 리셋 (buildContext가 먼저 값을 사용한 뒤 초기화)
                if (newState == CallState.IDLE) {
                    capturedPhoneNumber = null
                    isUnknownCaller = null
                    isVerifiedCaller = null
                }

                if (ctx != null) trySend(ctx)
            }
        }

        @Suppress("DEPRECATION")
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener registered")

        awaitClose {
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
            Log.d(TAG, "PhoneStateListener unregistered")
        }
    }

    // ── 상태 전이 처리 (공유) ────────────────────────────────────────────────

    private fun buildContext(
        previous: CallState,
        next: CallState,
        offhookAtMillis: Long?,
        phoneNumber: String?,
        isUnknownCaller: Boolean?,
        isVerifiedCaller: Boolean?,
        onOffhookUpdated: (Long?) -> Unit,
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
            val now = System.currentTimeMillis()
            onOffhookUpdated(now)
            val outgoing = previous == CallState.IDLE
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
            val now = System.currentTimeMillis()
            when (previous) {
                CallState.OFFHOOK -> {
                    val durationMs = offhookAtMillis?.let { now - it } ?: 0L
                    val durationSec = durationMs / 1000L
                    Log.d(TAG, "call ended (OFFHOOK→IDLE), durationMs=$durationMs, durationSec=$durationSec, isUnknownCaller=$isUnknownCaller, isVerifiedCaller=$isVerifiedCaller")
                    onOffhookUpdated(null)
                    CallContext(
                        state = CallState.IDLE,
                        phoneNumber = phoneNumber,
                        startedAtMillis = offhookAtMillis,
                        endedAtMillis = now,
                        durationMs = durationMs,
                        durationSec = durationSec,
                        isUnknownCaller = isUnknownCaller,
                        isVerifiedCaller = isVerifiedCaller,
                    )
                }
                CallState.RINGING -> {
                    Log.d(TAG, "missed/rejected call (RINGING→IDLE)")
                    onOffhookUpdated(null)
                    CallContext(
                        state = CallState.IDLE,
                        phoneNumber = phoneNumber,
                        startedAtMillis = null,
                        endedAtMillis = now,
                        durationMs = 0L,
                        durationSec = 0L,
                        isUnknownCaller = isUnknownCaller,
                        isVerifiedCaller = isVerifiedCaller,
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
    private fun isRepeatedUnknownCaller(): Boolean {
        val cutoff = System.currentTimeMillis() - REPEATED_CALL_WINDOW_MS
        recentUnknownCalls.removeAll { it < cutoff }
        return recentUnknownCalls.size >= 2
    }

    /** 미확인/미검증 수신 호출을 버퍼에 기록한다. */
    private fun recordUnknownCall() {
        // 세션이 없으면 이전 버퍼를 초기화 (안전 확인 후 클린 슬레이트)
        if (sessionTracker.sessionState.value == null) {
            recentUnknownCalls.clear()
            lastSuspiciousCallEndedAt = null
        }
        val now = System.currentTimeMillis()
        val cutoff = now - REPEATED_CALL_WINDOW_MS
        recentUnknownCalls.removeAll { it < cutoff }
        recentUnknownCalls.add(now)
        Log.d(TAG, "미확인 호출 기록: count=${recentUnknownCalls.size}")
    }

    /** 텔레뱅킹 윈도우: 세션 활성 + 의심 통화 종료 후 5분 이내. */
    private fun isTelebankingWindow(): Boolean {
        val lastSuspicious = lastSuspiciousCallEndedAt ?: return false
        val sessionActive = sessionTracker.sessionState.value != null
        return sessionActive && (System.currentTimeMillis() - lastSuspicious <= TELEBANKING_WINDOW_MS)
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
        private const val LONG_CALL_THRESHOLD_MS = 180_000L      // 프로덕션: 3분
        private const val TEST_LONG_CALL_THRESHOLD_MS = 10_000L  // 테스트 모드: 10초
        private const val TELEBANKING_WINDOW_MS = 5 * 60 * 1000L // 텔레뱅킹 감지 윈도우: 5분
        private const val CALL_LOG_QUERY_MAX_RETRIES = 3          // CallLog 조회 재시도 횟수
        private const val CALL_LOG_QUERY_RETRY_DELAY_MS = 800L    // 재시도 간격
        private const val CALL_LOG_START_TOLERANCE_MS = 5_000L    // startedAtMillis 기준 DATE 허용 범위
        private const val CALL_LOG_END_TOLERANCE_MS = 3_000L      // (DATE+DURATION*1000) vs endedAtMillis 허용 오차
        private const val REPEATED_CALL_WINDOW_MS = 30 * 60 * 1000L // 반복 호출 판단 윈도우: 30분
        private const val PERMISSION_POLL_INTERVAL_MS = 3_000L   // 권한 폴링 간격: 3초
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
