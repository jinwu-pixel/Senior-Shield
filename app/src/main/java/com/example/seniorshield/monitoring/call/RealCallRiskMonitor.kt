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
import com.example.seniorshield.monitoring.model.CallState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TelephonyCallback(API 31+) 또는 PhoneStateListener(API 26-30) 기반 통화 문맥 수집기.
 *
 * 수집 범위:
 * - 통화 상태 전이 (IDLE / RINGING / OFFHOOK)
 * - RINGING 시 발신자 번호 → 연락처 조회 → isUnknownCaller 설정
 * - OFFHOOK 기준 실제 통화 시작 시각 → durationSec 계산
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

    @Volatile private var lastSuspiciousCallEndedAt: Long? = null

    /** 최근 30분 이내 미확인/미검증 수신 호출 타임스탬프 버퍼 */
    private val recentUnknownCalls: MutableList<Long> = CopyOnWriteArrayList()

    override fun observeCallContext(): Flow<CallContext?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) observeCallContextApi31()
        else observeCallContextLegacy()

    override fun observeCallSignals(): Flow<List<RiskSignal>> =
        combine(
            observeCallContext(),
            settingsRepository.observeTestModeEnabled(),
        ) { ctx, testMode -> ctx to testMode }
            .flatMapLatest { (ctx, testMode) ->
                val thresholdMs = if (testMode) TEST_LONG_CALL_THRESHOLD_MS else LONG_CALL_THRESHOLD_MS
                val thresholdSec = if (testMode) CallSignalMapper.TEST_LONG_CALL_THRESHOLD_SEC
                                   else CallSignalMapper.LONG_CALL_THRESHOLD_SEC
                when {
                    ctx == null -> flowOf(emptyList<RiskSignal>())

                    // OFFHOOK: 즉시 신호 방출 후 임계 시간 경과 시 LONG_CALL_DURATION 추가
                    // flatMapLatest가 IDLE 전환 시 이 flow를 즉시 취소하므로
                    // 짧은 통화에서는 LONG_CALL_DURATION이 발생하지 않는다.
                    ctx.state == CallState.OFFHOOK -> flow<List<RiskSignal>> {
                        if (ctx.isOutgoing) {
                            // ── 발신 감지: 텔레뱅킹 판별 ──
                            delay(OUTGOING_CALL_LOG_DELAY_MS)
                            val dialedNumber = queryLatestOutgoingNumber()
                            if (dialedNumber != null && bankArsRegistry.matches(dialedNumber) && isTelebankingWindow()) {
                                Log.d(TAG, "텔레뱅킹 감지: number=$dialedNumber")
                                emit(listOf(RiskSignal.TELEBANKING_AFTER_SUSPICIOUS))
                            } else {
                                emit(emptyList())
                            }
                        } else {
                            // ── 수신 통화: 기존 로직 + 반복 호출 감지 ──
                            // 미확인/미검증 수신 시 반복 호출 버퍼에 기록
                            if (ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) {
                                recordUnknownCall()
                            }
                            val repeated = isRepeatedUnknownCaller()
                            // 즉시 신호: UNKNOWN_CALLER + REPEATED_UNKNOWN_CALLER
                            val immediateSignals = buildList {
                                if (ctx.isUnknownCaller == true) add(RiskSignal.UNKNOWN_CALLER)
                                if (repeated) add(RiskSignal.REPEATED_UNKNOWN_CALLER)
                            }
                            emit(immediateSignals.ifEmpty { emptyList() })

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
                            emit(signals)
                        }
                    }

                    // IDLE: 통화 종료 — 신호 세트 방출 후 즉시 리셋
                    ctx.state == CallState.IDLE && ctx.endedAtMillis != null -> flow<List<RiskSignal>> {
                        // 의심 통화 종료 시각 기록 (텔레뱅킹 5분 윈도우용)
                        if (ctx.isUnknownCaller == true || ctx.isVerifiedCaller == false) {
                            lastSuspiciousCallEndedAt = ctx.endedAtMillis
                            Log.d(TAG, "의심 통화 종료 기록: ${ctx.endedAtMillis}")
                        }
                        val signals = mapper.map(ctx, thresholdSec)
                        if (signals.isNotEmpty()) {
                            Log.d(TAG, "signals emitted: $signals")
                            emit(signals)
                        }
                        emit(emptyList()) // combine 캐시 리셋
                    }

                    else -> flowOf(emptyList<RiskSignal>())
                }
            }
            .distinctUntilChanged()

    // ── API 31+ (TelephonyCallback + BroadcastReceiver for number) ──────────

    @RequiresApi(Build.VERSION_CODES.S)
    private fun observeCallContextApi31(): Flow<CallContext?> = callbackFlow {
        if (!hasPhoneStatePermission()) {
            Log.w(TAG, "permission denied — skipping TelephonyCallback registration")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

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
        trySend(null)

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

        awaitClose {
            telephonyManager.unregisterTelephonyCallback(callback)
            executor.shutdown()
            try { context.unregisterReceiver(numberReceiver) } catch (_: Exception) {}
            Log.d(TAG, "TelephonyCallback unregistered")
        }
    }

    // ── API 26-30 (PhoneStateListener) ───────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun observeCallContextLegacy(): Flow<CallContext?> = callbackFlow {
        if (!hasPhoneStatePermission()) {
            Log.w(TAG, "permission denied — skipping PhoneStateListener registration")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

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
        trySend(null)

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
                    val duration = offhookAtMillis?.let { (now - it) / 1000L } ?: 0L
                    Log.d(TAG, "call ended (OFFHOOK→IDLE), durationSec=$duration, isUnknownCaller=$isUnknownCaller, isVerifiedCaller=$isVerifiedCaller")
                    onOffhookUpdated(null)
                    CallContext(
                        state = CallState.IDLE,
                        phoneNumber = phoneNumber,
                        startedAtMillis = offhookAtMillis,
                        endedAtMillis = now,
                        durationSec = duration,
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

    /** CallLog에서 최근 발신 번호를 조회한다. READ_CALL_LOG 권한 필요. */
    private fun queryLatestOutgoingNumber(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CALL_LOG 권한 없음 — 발신 번호 조회 불가")
            return null
        }
        return try {
            context.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER),
                "${android.provider.CallLog.Calls.TYPE} = ?",
                arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 1",
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
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
        private const val OUTGOING_CALL_LOG_DELAY_MS = 1500L     // CallLog 기록 대기
        private const val REPEATED_CALL_WINDOW_MS = 30 * 60 * 1000L // 반복 호출 판단 윈도우: 30분
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
