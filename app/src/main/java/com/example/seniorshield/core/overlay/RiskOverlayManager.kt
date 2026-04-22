package com.example.seniorshield.core.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.example.seniorshield.BuildConfig
import com.example.seniorshield.MainActivity
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.domain.repository.RiskEventSink
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.orchestrator.AlertStateResolver
import com.example.seniorshield.monitoring.session.RiskSessionTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Overlay"

/** showInCallScreen() 후 dismiss 지연. 전화 앱이 foreground로 올라올 시간 확보. */
private const val SHOW_IN_CALL_DELAY_MS = 500L

/** IDLE 감지 후 suppression 유지 시간. 다이얼러 InCallFragment 정리 시퀀스 완료 대기. */
private const val POST_CALL_UI_STABILIZATION_MS = 1_000L

/** IDLE이 감지되지 않을 경우 suppression 강제 해제 (안전 타임아웃). */
private const val MAX_END_CALL_SUPPRESSION_MS = 3_000L

/**
 * 위험 감지 시 화면 전체를 덮는 경고 팝업을 표시한다.
 *
 * TYPE_APPLICATION_OVERLAY + MATCH_PARENT 높이로 다른 앱 위, 통화 중에도 표시된다.
 * SYSTEM_ALERT_WINDOW 권한이 없으면 조용히 생략한다.
 *
 * 주 버튼은 통화 중이면 "전화 앱으로 이동"(showInCallScreen + dismiss),
 * 아니면 "일단 닫기"(dismiss만, 세션 유지).
 *
 * 보조 CTA 라벨은 렌더 시점 통화 여부에 따라 분기된다:
 * - 통화 중: "통화 경고 닫기"
 * - 비통화: "위험 경고 해제"
 *
 * 클릭 시 부수효과는 **두 축**으로 분리된다:
 *
 * 1. **snooze (respawn 억제)** — `sessionTracker.snoozeForCall(liveCallId)`.
 *    같은 callId의 call-derived signal을 Coordinator pre-update 필터에서 제거.
 *    `liveCallId != null` 이면 **항상** 적용 (provenance 무관). 목적: 사용자가 방금 닫은
 *    팝업이 call-derived signal 재감지만으로 즉시 respawn되는 UX 회귀 방지.
 *
 * 2. **anchor suppression** — `clearTelebankingAnchor` + `markCurrentCallConfirmedSafe`.
 *    두 호출은 같은 목적(텔레뱅킹 감지용 `lastSuspiciousCallEndedAt` anchor의 즉시 삭제 + 다음 IDLE
 *    시 재설정 차단)이라 **동일 predicate 아래 묶어서** 실행/스킵한다. 반쪽 실행 금지.
 *    predicate는 [shouldApplyCallSafeEffects] — positive allowlist.
 *    의미 계약: "현재 overlay를 닫는 행위가 해당 통화를 안전 확인하는 뜻으로 해석 가능한 경우에만" 적용.
 *    app-derived(REMOTE_CONTROL 등) / TELEBANKING 포함 / mixed 는 모두 deny.
 *
 * 클릭 시 실행 순서 (pure function [performSafeCtaSideEffects] 에 추출됨):
 *   1. sessionTracker.resetAfterUserConfirmedSafe()   (세션 즉시 종료 + α arm)
 *   2. eventSink.clearCurrentRiskEvent()              (UI 반영)
 *   3. (liveCallId != null) snoozeForCall(liveCallId) — 축 1
 *   4. (callSafe == true && liveCallId != null) clearTelebankingAnchor + markCurrentCallConfirmedSafe — 축 2
 *   5. dismiss()
 *
 * snooze는 IDLE 전이 / 통화 전환 / TTL 15분 / 상위 trigger(REMOTE_CONTROL 등) 출현 시 자동 해제된다.
 */
@Singleton
class RiskOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callEndHelper: CallEndHelper,
    private val callRiskMonitor: CallRiskMonitor,
    private val sessionTracker: RiskSessionTracker,
    private val eventSink: RiskEventSink,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: LinearLayout? = null
    @Volatile private var isDismissing = false
    private var currentParams: WindowManager.LayoutParams? = null

    // ── end-call suppression state ──────────────────────────────────
    @Volatile private var endCallSuppressionActive = false
    @Volatile private var idleStabilizationScheduled = false
    private var suppressionReleaseRunnable: Runnable? = null
    private var onSuppressionReleased: (() -> Unit)? = null

    fun show(event: RiskEvent, guardian: Guardian? = null) {
        if (endCallSuppressionActive) {
            Log.d(TAG, "suppression active, skip risk popup")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음 — 팝업 생략")
            return
        }
        mainHandler.post {
            if (overlayView != null) {
                Log.d(TAG, "팝업 이미 표시 중 — 새 내용으로 갱신")
                try {
                    windowManager.removeView(overlayView)
                    overlayView = null
                } catch (e: Exception) {
                    Log.e(TAG, "기존 팝업 제거 실패, 갱신 중단: ${e.message}")
                    return@post
                }
            }
            val (view, primaryButton) = buildView(event, guardian)
            val params = WindowManager.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            )
            try {
                windowManager.addView(view, params)
                overlayView = view
                currentParams = params
                primaryButton.requestFocus()
                Log.d(TAG, "전체화면 팝업 표시: level=${event.level}, title=${event.title}")
            } catch (e: Exception) {
                Log.e(TAG, "팝업 addView 실패: ${e.message}")
            }
        }
    }

    fun dismiss() {
        isDismissing = true
        mainHandler.post {
            val view = overlayView ?: run {
                isDismissing = false
                return@post
            }
            try {
                windowManager.removeView(view)
                Log.d(TAG, "팝업 닫힘")
            } catch (e: Exception) {
                Log.e(TAG, "팝업 removeView 실패: ${e.message}")
            } finally {
                overlayView = null
                currentParams = null
                isDismissing = false
            }
        }
    }

    fun ensureCriticalOnTop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "ensureCriticalOnTop called off-main, posting to main")
            mainHandler.post { ensureCriticalOnTop() }
            return
        }
        val view = overlayView ?: return
        if (view.parent == null) return
        if (isDismissing) return
        val params = currentParams ?: return
        try {
            windowManager.removeView(view)
            windowManager.addView(view, params)
            Log.d(TAG, "팝업 z-order 최상위 재배치")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "ensureCriticalOnTop race: ${e.message}")
        }
    }

    // ── end-call suppression ───────────────────────────────────────

    /** coordinator가 UI 억제 여부를 확인할 때 사용. */
    fun isEndCallSuppressed(): Boolean = endCallSuppressionActive

    /**
     * 통화 종료 동작을 유도한 직후 호출되어 팝업 재표시를 억제한다.
     * 안전 타임아웃을 건 뒤, IDLE 감지 또는 타임아웃 도래 시 해제된다.
     * (현재 호출부 없음 — 팝업 주 버튼이 showInCallScreen 경로로 바뀌면서 유휴 상태.)
     */
    internal fun startEndCallSuppression(onReleased: (() -> Unit)? = null) {
        endCallSuppressionActive = true
        idleStabilizationScheduled = false
        onSuppressionReleased = onReleased
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        val safetyRelease = Runnable {
            endCallSuppressionActive = false
            idleStabilizationScheduled = false
            suppressionReleaseRunnable = null
            Log.d(TAG, "suppression force-released (safety timeout)")
            onSuppressionReleased?.invoke()
            onSuppressionReleased = null
        }
        suppressionReleaseRunnable = safetyRelease
        mainHandler.postDelayed(safetyRelease, MAX_END_CALL_SUPPRESSION_MS)
        Log.d(TAG, "end-call suppression started")
    }

    /**
     * coordinator가 IDLE(callSignals 비어짐)을 감지하면 호출.
     * 안전 타임아웃을 취소하고 짧은 안정화 지연 후 suppression 해제.
     */
    fun scheduleSuppressionRelease() {
        if (!endCallSuppressionActive || idleStabilizationScheduled) return
        idleStabilizationScheduled = true
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        val release = Runnable {
            endCallSuppressionActive = false
            idleStabilizationScheduled = false
            suppressionReleaseRunnable = null
            Log.d(TAG, "suppression released after stabilization")
            onSuppressionReleased?.invoke()
            onSuppressionReleased = null
        }
        suppressionReleaseRunnable = release
        mainHandler.postDelayed(release, POST_CALL_UI_STABILIZATION_MS)
        Log.d(TAG, "call became IDLE, suppression release in ${POST_CALL_UI_STABILIZATION_MS}ms")
    }

    // ── 레이아웃 ─────────────────────────────────────────────────

    private data class OverlayViews(
        val root: LinearLayout,
        val primaryButton: Button,
    )

    private fun buildView(event: RiskEvent, guardian: Guardian? = null): OverlayViews {
        val bgColor = when (event.level) {
            RiskLevel.CRITICAL -> Color.parseColor("#B71C1C")
            RiskLevel.HIGH     -> Color.parseColor("#BF360C")
            RiskLevel.MEDIUM   -> Color.parseColor("#E65100")
            RiskLevel.LOW      -> Color.parseColor("#1565C0")
        }
        val levelLabel = when (event.level) {
            RiskLevel.CRITICAL -> "🚨 매우 위험"
            RiskLevel.HIGH     -> "⚠ 위험"
            RiskLevel.MEDIUM   -> "⚠ 주의"
            RiskLevel.LOW      -> "ℹ 낮음"
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // ── 본문 영역 ─────────────────────────────────────────────
        val contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(16), dp(24), dp(16), dp(0))
        }

        contentArea.addView(TextView(context).apply {
            text = "⚠"
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })

        contentArea.addView(TextView(context).apply {
            text = levelLabel
            textSize = 16f
            setTextColor(bgColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        })

        // 핵심 경고 제목 — 화면 폭에 가깝게 크게 표시
        contentArea.addView(TextView(context).apply {
            text = event.title
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })

        // 상세 설명
        contentArea.addView(TextView(context).apply {
            text = event.description
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        })

        // ── 버튼 영역 ─────────────────────────────────────────────
        val buttonArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(24), dp(12), dp(24), dp(32))
        }

        // 주 버튼: 통화 중이면 "전화 앱으로 이동"(showInCallScreen + dismiss),
        // 아니면 "일단 닫기"(dismiss만, 세션 유지 — 안전 확인은 홈/쿨다운에서).
        val cornerPx = dp(8).toFloat()
        val inCall = callEndHelper.isInCall()
        val primaryBtn = Button(context).apply {
            text = if (inCall) "전화 앱으로 이동" else "일단 닫기"
            textSize = 18f
            setTextColor(bgColor)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setOnClickListener {
                if (callEndHelper.isInCall()) {
                    Log.d(TAG, "opening in-call screen")
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                    telecom?.showInCallScreen(false)
                    // 전화 앱이 foreground로 올라올 시간을 확보한 뒤 오버레이 제거
                    mainHandler.postDelayed({ dismiss() }, SHOW_IN_CALL_DELAY_MS)
                } else {
                    dismiss()
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(Color.WHITE)
                    if (hasFocus) setStroke(dp(4), Color.YELLOW)
                }
            }
        }
        buttonArea.addView(primaryBtn)

        // 보조 CTA 라벨은 렌더 시점 통화 여부로 분기. 실제 call-scope 부수효과 적용 여부는
        // 클릭 시점 shouldApplyCallSafeEffects(...) predicate가 결정한다 (위 클래스 주석 참조).
        val safeCtaText = if (inCall) "통화 경고 닫기" else "위험 경고 해제"
        buttonArea.addView(Button(context).apply {
            text = safeCtaText
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                // 클릭 시점에 callId + signals 재조회. 부수효과는 두 축으로 분리:
                //   - snooze (respawn 억제): liveCallId != null 이면 항상 적용
                //   - anchor suppression (clearTelebankingAnchor + markCurrentCallConfirmedSafe):
                //     shouldApplyCallSafeEffects(...)가 true일 때만 적용 (provenance allowlist)
                val liveCallId = callRiskMonitor.currentCallId()
                val callSafe = shouldApplyCallSafeEffects(
                    inCall = liveCallId != null,
                    signals = event.signals.toSet(),
                )
                performSafeCtaSideEffects(
                    liveCallId = liveCallId,
                    callSafe = callSafe,
                    reset = { sessionTracker.resetAfterUserConfirmedSafe() },
                    clearEvent = { eventSink.clearCurrentRiskEvent() },
                    snooze = { sessionTracker.snoozeForCall(it) },
                    clearAnchor = { callRiskMonitor.clearTelebankingAnchor() },
                    markSafe = { callRiskMonitor.markCurrentCallConfirmedSafe(it) },
                )
                when {
                    callSafe && liveCallId != null ->
                        Log.d(TAG, "safe CTA → anchor suppression + snooze (callId=$liveCallId, signals=${event.signals})")
                    liveCallId != null ->
                        Log.d(TAG, "safe CTA → snooze only, anchor preserved (callId=$liveCallId, callSafe=$callSafe, signals=${event.signals})")
                    else ->
                        Log.d(TAG, "safe CTA → session reset only (inCall=false, signals=${event.signals})")
                }
                dismiss()
            }
            setOnFocusChangeListener { _, hasFocus ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(if (hasFocus) 4 else 2), if (hasFocus) Color.YELLOW else Color.WHITE)
                }
            }
        })

        // 보조 버튼: 보호자에게 도움 요청 (guardian이 설정된 경우에만 표시)
        if (guardian != null) {
            buttonArea.addView(Button(context).apply {
                text = "등록된 보호자에게 문자 보내기"
                textSize = 16f
                setTextColor(Color.WHITE)
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(8)
                }
                setOnClickListener {
                    val smsUri = Uri.parse("smsto:${guardian.phoneNumber}")
                    val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                        putExtra("sms_body", "[시니어쉴드] 위험 경고가 떠서 연락드립니다. 송금이나 인증 전에 같이 확인해주세요.")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (smsIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(smsIntent)
                    } else {
                        Toast.makeText(context, "이 기기에서는 문자 전송을 지원하지 않습니다", Toast.LENGTH_SHORT).show()
                    }
                    dismiss()
                }
                setOnFocusChangeListener { _, hasFocus ->
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = cornerPx
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(if (hasFocus) 4 else 2), if (hasFocus) Color.YELLOW else Color.WHITE)
                    }
                }
            })
        }

        // 보조 버튼: 앱 열어서 확인하기
        buttonArea.addView(Button(context).apply {
            text = "앱 열어서 확인하기"
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            }
            setOnClickListener {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                )
                dismiss()
            }
            setOnFocusChangeListener { _, hasFocus ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(if (hasFocus) 4 else 2), if (hasFocus) Color.YELLOW else Color.WHITE)
                }
            }
        })

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(contentArea)
        }
        root.addView(scrollView)
        root.addView(buttonArea)
        return OverlayViews(root, primaryBtn)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * **텔레뱅킹 anchor 억제** 두 층위([CallRiskMonitor.clearTelebankingAnchor] +
         * [CallRiskMonitor.markCurrentCallConfirmedSafe]) 적용 자격을 판단하는 predicate.
         *
         * 의미 계약: "현재 overlay를 닫는 행위가 해당 통화를 안전 확인하는 뜻으로
         * 해석 가능한 경우에만" true.
         *
         * **[RiskSessionTracker.snoozeForCall]은 이 predicate와 무관하다** — respawn 억제 목적이라
         * `liveCallId != null` 이면 항상 적용 ([performSafeCtaSideEffects] 참조).
         *
         * 정책: **positive allowlist**.
         * - `inCall == true` 는 필요조건이지만 충분조건이 아니다
         * - 세션의 모든 signal이 [AlertStateResolver.CALL_SIGNALS]에 속할 때만 허용
         * - [AlertStateResolver.CALL_SIGNALS] 는 원래 call-based session 판별용 상수이지만
         *   여기서는 "call-scope anchor 억제 허용 신호 집합"이라는 정책 의미도 가진다
         *
         * deny되는 케이스:
         * - `inCall == false`
         * - app-derived signal 포함 (예: `REMOTE_CONTROL_APP_OPENED`, `BANKING_APP_OPENED_AFTER_REMOTE_APP`)
         * - `TELEBANKING_AFTER_SUSPICIOUS` 포함 (텔레뱅킹 자체가 해당 통화 — "이 통화 안전" 의미 모순)
         * - mixed (call + app-derived 병존)
         * - empty signals (방어 가드)
         */
        @VisibleForTesting
        internal fun shouldApplyCallSafeEffects(
            inCall: Boolean,
            signals: Set<RiskSignal>,
        ): Boolean {
            if (!inCall) return false
            if (signals.isEmpty()) return false
            return signals.all { it in AlertStateResolver.CALL_SIGNALS }
        }

        /**
         * 보조 CTA 클릭 시 부수효과 순서. 두 축 분리:
         *
         * - **snooze 축**: `liveCallId != null` 이면 [snooze] 호출 (respawn 억제 목적)
         * - **anchor 억제 축**: `callSafe && liveCallId != null` 이면 [clearAnchor] + [markSafe]
         *   (텔레뱅킹 anchor 억제 두 층위, 동일 조건으로 묶음 — 반쪽 실행 금지)
         *
         * 호출 순서 (비즈니스 규칙):
         *   1. [reset]        (세션 종료 + α arm)
         *   2. [clearEvent]   (UI 반영)
         *   3. [snooze]       (해당 통화의 call-derived signal 필터, 축 1)
         *   4. [clearAnchor] + [markSafe]   (축 2, 묶음)
         *
         * reset이 snooze보다 먼저여야 session reset 이후 등록된 snooze가 살아남는다.
         */
        @VisibleForTesting
        internal fun performSafeCtaSideEffects(
            liveCallId: Long?,
            callSafe: Boolean,
            reset: () -> Unit,
            clearEvent: () -> Unit,
            snooze: (Long) -> Unit,
            clearAnchor: () -> Unit,
            markSafe: (Long) -> Unit,
        ) {
            reset()
            clearEvent()
            if (liveCallId != null) {
                snooze(liveCallId)
            }
            if (callSafe && liveCallId != null) {
                clearAnchor()
                markSafe(liveCallId)
            }
        }
    }
}
