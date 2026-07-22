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
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.seniorshield.BuildConfig
import com.example.seniorshield.MainActivity
import com.example.seniorshield.core.navigation.NavigationEventBus
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.monitoring.call.CallRiskMonitor
import com.example.seniorshield.monitoring.orchestrator.SafeConfirmationOverlayBinding
import com.example.seniorshield.monitoring.session.ResetEpochProvider
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
 * Safe-confirm route postprocessing (notably Warning onBack) runs on main. Remove an existing
 * surface in that same call stack so step 7 completes before step 8; preserve Handler dispatch for
 * the defensive off-main case.
 */
internal fun dispatchSafeCleanup(
    isMainThread: Boolean,
    post: (Runnable) -> Unit,
    cleanup: () -> Unit,
) {
    val task = Runnable { cleanup() }
    if (isMainThread) task.run() else post(task)
}

/** Business reset epoch plus a manager-local surface generation. */
internal data class PresentationGenerationToken(
    val businessEpoch: Long,
    val generation: Long,
)

/** Exact cleanup boundary captured when safe-confirm invalidates prior-epoch work. */
internal data class PresentationInvalidationTicket(
    val beforeBusinessEpoch: Long,
    val issuedThroughGeneration: Long,
)

/**
 * Pure, thread-safe gate shared by the two presentation managers through independent instances.
 *
 * Safe-confirm advances only [minimumBusinessEpoch]. Each business epoch owns its latest-token and
 * dismissal boundary, so a late reservation from the old epoch cannot supersede or invalidate a
 * token from the new epoch that was reserved after reset but before manager invalidation. Normal UI
 * dismissal invalidates the relevant epoch through the captured token; a later same-epoch threat
 * receives a larger generation and remains eligible.
 */
internal class PresentationGenerationGate {
    private var nextGeneration = 0L
    private var latestReservedGeneration = 0L
    private var minimumBusinessEpoch = Long.MIN_VALUE
    private val latestReservedGenerationByEpoch = mutableMapOf<Long, Long>()
    private val invalidThroughGenerationByEpoch = mutableMapOf<Long, Long>()

    @Synchronized
    fun reserve(businessEpoch: Long): PresentationGenerationToken? {
        if (businessEpoch < minimumBusinessEpoch) return null
        nextGeneration += 1
        latestReservedGeneration = nextGeneration
        latestReservedGenerationByEpoch[businessEpoch] = nextGeneration
        return PresentationGenerationToken(businessEpoch, nextGeneration)
    }

    @Synchronized
    fun invalidateBeforeEpoch(businessEpoch: Long): PresentationInvalidationTicket {
        minimumBusinessEpoch = maxOf(minimumBusinessEpoch, businessEpoch)
        latestReservedGenerationByEpoch.keys.removeAll { it < minimumBusinessEpoch }
        invalidThroughGenerationByEpoch.keys.removeAll { it < minimumBusinessEpoch }
        return PresentationInvalidationTicket(
            beforeBusinessEpoch = businessEpoch,
            issuedThroughGeneration = latestReservedGeneration,
        )
    }

    @Synchronized
    fun invalidateThrough(token: PresentationGenerationToken): PresentationGenerationToken {
        val invalidThrough = invalidThroughGenerationByEpoch[token.businessEpoch] ?: 0L
        invalidThroughGenerationByEpoch[token.businessEpoch] = maxOf(
            invalidThrough,
            token.generation,
        )
        return token
    }

    @Synchronized
    fun invalidateThroughLatest(businessEpoch: Long): PresentationGenerationToken? {
        val latestForEpoch = latestReservedGenerationByEpoch[businessEpoch] ?: return null
        return invalidateThrough(PresentationGenerationToken(businessEpoch, latestForEpoch))
    }

    @Synchronized
    fun isCurrent(token: PresentationGenerationToken, liveBusinessEpoch: Long): Boolean =
        token.businessEpoch == liveBusinessEpoch &&
            token.businessEpoch >= minimumBusinessEpoch &&
            token.generation == latestReservedGenerationByEpoch[token.businessEpoch] &&
            token.generation > (invalidThroughGenerationByEpoch[token.businessEpoch] ?: 0L)

    @Synchronized
    fun runIfCurrent(
        token: PresentationGenerationToken,
        liveBusinessEpoch: Long,
        action: () -> Unit,
    ): Boolean {
        if (!isCurrent(token, liveBusinessEpoch)) return false
        action()
        return true
    }

    fun shouldRemoveThrough(
        token: PresentationGenerationToken,
        cutoff: PresentationGenerationToken,
    ): Boolean = token.businessEpoch == cutoff.businessEpoch &&
        token.generation <= cutoff.generation

    fun shouldRemoveBeforeEpoch(token: PresentationGenerationToken, businessEpoch: Long): Boolean =
        token.businessEpoch < businessEpoch

    fun shouldRemove(
        token: PresentationGenerationToken,
        ticket: PresentationInvalidationTicket,
    ): Boolean = token.businessEpoch < ticket.beforeBusinessEpoch &&
        token.generation <= ticket.issuedThroughGeneration
}

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
 * 보조 CTA는 클릭 시점 callId와 표시 신호만 [SafeConfirmationOverlayBinding]에 전달한다.
 * 세션 reset, snooze, call-safe allowlist, anchor, current event, surface cleanup의 자격과
 * 순서는 모두 Coordinator command가 검증·소유한다. callback이 false면 현재 표면은 유지된다.
 */
@Singleton
class RiskOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callEndHelper: CallEndHelper,
    private val callRiskMonitor: CallRiskMonitor,
    private val navigationEventBus: NavigationEventBus,
    private val resetEpochProvider: ResetEpochProvider,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: LinearLayout? = null
    @Volatile private var overlayPresentationToken: PresentationGenerationToken? = null
    @Volatile private var isDismissing = false
    private var currentParams: WindowManager.LayoutParams? = null
    private val presentationGeneration = PresentationGenerationGate()
    @Volatile private var pendingSafeCleanup: PresentationInvalidationTicket? = null

    // ── end-call suppression state ──────────────────────────────────
    @Volatile private var endCallSuppressionActive = false
    @Volatile private var idleStabilizationScheduled = false
    private var suppressionReleaseRunnable: Runnable? = null
    private var onSuppressionReleased: (() -> Unit)? = null
    private val suppressionGeneration = PresentationGenerationGate()
    @Volatile private var suppressionPresentationToken: PresentationGenerationToken? = null

    fun show(
        event: RiskEvent,
        guardian: Guardian?,
        safeConfirmation: SafeConfirmationOverlayBinding,
    ) {
        if (event.id != safeConfirmation.expectedEventId) {
            Log.w(TAG, "safe-confirm binding/event mismatch — 팝업 생략")
            return
        }
        if (safeConfirmation.expectedResetEpoch != resetEpochProvider.userResetEpoch) {
            Log.w(TAG, "stale safe-confirm epoch — 팝업 생략")
            return
        }
        if (isEndCallSuppressed()) {
            Log.d(TAG, "suppression active, skip risk popup")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음 — 팝업 생략")
            return
        }
        val presentationToken = presentationGeneration.reserve(
            safeConfirmation.expectedResetEpoch,
        ) ?: return
        postIfCurrent(presentationToken) showTask@{
            if (overlayView != null) {
                Log.d(TAG, "팝업 이미 표시 중 — 새 내용으로 갱신")
                try {
                    windowManager.removeView(overlayView)
                    overlayView = null
                    overlayPresentationToken = null
                } catch (e: Exception) {
                    Log.e(TAG, "기존 팝업 제거 실패, 갱신 중단: ${e.message}")
                    return@showTask
                }
            }
            val (view, primaryButton) = buildView(
                event,
                guardian,
                safeConfirmation,
                presentationToken,
            )
            val params = WindowManager.LayoutParams(
                MATCH_PARENT,
                MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            )
            try {
                windowManager.addView(view, params)
                if (!presentationGeneration.isCurrent(
                        presentationToken,
                        resetEpochProvider.userResetEpoch,
                    )
                ) {
                    windowManager.removeView(view)
                    Log.d(TAG, "팝업 add 직후 세대 무효 확인 — 즉시 제거")
                    return@showTask
                }
                overlayView = view
                overlayPresentationToken = presentationToken
                currentParams = params
                primaryButton.requestFocus()
                Log.d(TAG, "전체화면 팝업 표시: level=${event.level}, title=${event.title}")
            } catch (e: Exception) {
                Log.e(TAG, "팝업 addView 실패: ${e.message}")
            }
        }
    }

    fun dismiss() {
        val surfaceCutoff = overlayPresentationToken?.let(presentationGeneration::invalidateThrough)
        val pendingCutoff = presentationGeneration.invalidateThroughLatest(
            resetEpochProvider.userResetEpoch,
        )
        listOfNotNull(surfaceCutoff, pendingCutoff)
            .distinct()
            .forEach(::dismissThrough)
    }

    /** Safe-confirm step 3: synchronously invalidate only work from earlier business epochs. */
    internal fun invalidateBeforeEpoch(businessEpoch: Long) {
        pendingSafeCleanup = presentationGeneration.invalidateBeforeEpoch(businessEpoch)
        invalidateSuppressionBeforeEpoch(businessEpoch)
    }

    /** Safe-confirm step 7: remove only the surface covered by the earlier invalidation ticket. */
    internal fun dismissBeforeEpoch(businessEpoch: Long) {
        val ticket = pendingSafeCleanup
            ?.takeIf { it.beforeBusinessEpoch == businessEpoch }
            ?: PresentationInvalidationTicket(
                beforeBusinessEpoch = businessEpoch,
                issuedThroughGeneration = Long.MAX_VALUE,
            )
        dispatchSafeCleanup(
            isMainThread = Looper.myLooper() == mainHandler.looper,
            post = { task ->
                mainHandler.post(task)
                Unit
            },
        ) {
            val token = overlayPresentationToken
            if (token != null && presentationGeneration.shouldRemove(token, ticket)) {
                removeSurfaceIfExact(token)
                if (pendingSafeCleanup == ticket) pendingSafeCleanup = null
            }
        }
    }

    private fun dismiss(presentationToken: PresentationGenerationToken) {
        val generationCutoff = presentationGeneration.invalidateThrough(presentationToken)
        dismissThrough(generationCutoff)
    }

    private fun dismissThrough(generationCutoff: PresentationGenerationToken) {
        isDismissing = true
        mainHandler.post {
            val token = overlayPresentationToken ?: run {
                isDismissing = false
                return@post
            }
            if (!presentationGeneration.shouldRemoveThrough(token, generationCutoff)) {
                isDismissing = false
                return@post
            }
            removeSurfaceIfExact(token)
            isDismissing = false
        }
    }

    private fun removeSurfaceIfExact(token: PresentationGenerationToken) {
        if (overlayPresentationToken != token) return
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
            Log.d(TAG, "팝업 닫힘")
        } catch (e: Exception) {
            Log.e(TAG, "팝업 removeView 실패: ${e.message}")
        } finally {
            if (overlayPresentationToken == token) {
                overlayView = null
                overlayPresentationToken = null
                currentParams = null
            }
        }
    }

    private fun postIfCurrent(
        token: PresentationGenerationToken,
        action: () -> Unit,
    ) {
        mainHandler.post {
            presentationGeneration.runIfCurrent(
                token,
                resetEpochProvider.userResetEpoch,
                action,
            )
        }
    }

    fun ensureCriticalOnTop() {
        val token = overlayPresentationToken ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "ensureCriticalOnTop called off-main, posting to main")
            postIfCurrent(token) { ensureCriticalOnTop(token) }
            return
        }
        ensureCriticalOnTop(token)
    }

    private fun ensureCriticalOnTop(token: PresentationGenerationToken) {
        if (!presentationGeneration.isCurrent(token, resetEpochProvider.userResetEpoch)) return
        if (overlayPresentationToken != token) return
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
    fun isEndCallSuppressed(): Boolean {
        val token = suppressionPresentationToken ?: return false
        return endCallSuppressionActive &&
            suppressionGeneration.isCurrent(token, resetEpochProvider.userResetEpoch)
    }

    /**
     * 통화 종료 동작을 유도한 직후 호출되어 팝업 재표시를 억제한다.
     * 안전 타임아웃을 건 뒤, IDLE 감지 또는 타임아웃 도래 시 해제된다.
     * (현재 호출부 없음 — 팝업 주 버튼이 showInCallScreen 경로로 바뀌면서 유휴 상태.)
     */
    internal fun startEndCallSuppression(onReleased: (() -> Unit)? = null) {
        val businessEpoch = resetEpochProvider.userResetEpoch
        val token = suppressionGeneration.reserve(businessEpoch) ?: return
        endCallSuppressionActive = true
        idleStabilizationScheduled = false
        onSuppressionReleased = onReleased
        suppressionPresentationToken = token
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        val safetyRelease = Runnable {
            suppressionGeneration.runIfCurrent(
                token,
                resetEpochProvider.userResetEpoch,
            ) {
                releaseSuppressionIfExact(token, "suppression force-released (safety timeout)")
            }
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
        val token = suppressionPresentationToken ?: return
        if (!isEndCallSuppressed() || idleStabilizationScheduled) return
        idleStabilizationScheduled = true
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        val release = Runnable {
            suppressionGeneration.runIfCurrent(
                token,
                resetEpochProvider.userResetEpoch,
            ) {
                releaseSuppressionIfExact(token, "suppression released after stabilization")
            }
        }
        suppressionReleaseRunnable = release
        mainHandler.postDelayed(release, POST_CALL_UI_STABILIZATION_MS)
        Log.d(TAG, "call became IDLE, suppression release in ${POST_CALL_UI_STABILIZATION_MS}ms")
    }

    private fun invalidateSuppressionBeforeEpoch(businessEpoch: Long) {
        suppressionGeneration.invalidateBeforeEpoch(businessEpoch)
        val token = suppressionPresentationToken ?: return
        if (!suppressionGeneration.shouldRemoveBeforeEpoch(token, businessEpoch)) return
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        suppressionReleaseRunnable = null
        suppressionPresentationToken = null
        endCallSuppressionActive = false
        idleStabilizationScheduled = false
        onSuppressionReleased = null
    }

    private fun releaseSuppressionIfExact(
        token: PresentationGenerationToken,
        logMessage: String,
    ) {
        if (suppressionPresentationToken != token) return
        endCallSuppressionActive = false
        idleStabilizationScheduled = false
        suppressionReleaseRunnable = null
        suppressionPresentationToken = null
        Log.d(TAG, logMessage)
        onSuppressionReleased?.invoke()
        onSuppressionReleased = null
    }

    // ── 레이아웃 ─────────────────────────────────────────────────

    private data class OverlayViews(
        val root: LinearLayout,
        val primaryButton: Button,
    )

    private fun buildView(
        event: RiskEvent,
        guardian: Guardian?,
        safeConfirmation: SafeConfirmationOverlayBinding,
        presentationToken: PresentationGenerationToken,
    ): OverlayViews {
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

        // 뒤로가기 키 처리를 위해 LinearLayout을 anonymous subclass로 확장.
        // TYPE_APPLICATION_OVERLAY window는 Activity가 아니라서 뒤로가기 키를 기본 소비하지 않는다.
        // 과거 증상: 오버레이 위에서 뒤로가기 → 이벤트가 아래 Warning Activity로 새어 Warning만 pop,
        //          오버레이는 그대로 → "팝업이 안 사라짐"으로 보이거나 Warning + 오버레이가 중복 노출.
        //
        // 축 분리 원칙:
        //   뒤로가기 = 창닫기 (UI recovery)
        //   "안전 확인했어요" = 위험 종료 선언 (risk resolution)
        //
        // 뒤로가기 1회 동작:
        //   1) 오버레이 dismiss
        //   2) NavigationEventBus.popToHome() — Warning 등 하위 destination을 backstack에서 모두 pop
        //
        // 이 경로에서는 risk state를 절대 touch하지 않는다:
        //   - currentRiskEvent clear 금지
        //   - user-confirmed-safe session reset 금지
        //   - anchor clear (callRiskMonitor.clearTelebankingAnchor) 금지
        //   - safe-confirm 관련 부수효과 일체 금지
        // → Home 복귀 시 currentRiskEvent가 살아있어 카드는 "위험 감지"로 유지되고,
        //    SAFE 전환은 홈의 "안전 확인했어요" 버튼이 담당한다.
        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // 디버그: 어떤 key event가 오버레이 root까지 도달하는지 가시화 (검증 후 제거 예정)
                Log.d(TAG, "overlay dispatchKeyEvent: keyCode=${event.keyCode} action=${event.action}")
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    Log.d(TAG, "오버레이 뒤로가기 키 → UI recovery (dismiss + popToHome, risk state 보존)")
                    navigationEventBus.popToHome()
                    dismiss(presentationToken)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
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
                    mainHandler.postDelayed(
                        { dismiss(presentationToken) },
                        SHOW_IN_CALL_DELAY_MS,
                    )
                } else {
                    dismiss(presentationToken)
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
        // 클릭 시점의 liveCallId와 binding 신호를 받은 Coordinator 명령이 결정한다.
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
                val liveCallId = callRiskMonitor.currentCallId()
                val confirmed = safeConfirmation.confirm(
                    liveCallId = liveCallId,
                    signals = event.signals.toSet(),
                )
                if (confirmed) {
                    Log.d(TAG, "safe CTA consumed by Coordinator (callId=$liveCallId, signals=${event.signals})")
                } else {
                    Log.w(TAG, "safe CTA rejected; keep current overlay visible")
                }
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
                    dismiss(presentationToken)
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
                dismiss(presentationToken)
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
}
