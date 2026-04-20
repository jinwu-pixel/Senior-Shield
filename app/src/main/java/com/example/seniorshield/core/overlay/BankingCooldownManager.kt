package com.example.seniorshield.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Cooldown"

/** showInCallScreen() 후 dismiss 지연. 전화 앱이 foreground로 올라올 시간 확보. */
private const val SHOW_IN_CALL_DELAY_MS = 500L


/**
 * HIGH+ 위험 세션 중 뱅킹 앱이 포그라운드로 올라오면
 * 위험 수준에 따라 차등적으로 화면 전체를 막는 강제 대기 화면을 표시한다.
 *
 * - HIGH:     30초 카운트다운 + 주 버튼(통화 중: "전화 앱으로 이동" / 아니면: "일단 닫기")
 * - CRITICAL: 60초 카운트다운 + 주 버튼(통화 중: "전화 앱으로 이동" / 아니면: "일단 닫기")
 *
 * 카운트다운 종료 시 자동 dismiss. 주 버튼으로 early dismiss 가능.
 */
@Singleton
class BankingCooldownManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callEndHelper: CallEndHelper,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var overlayView: LinearLayout? = null
    private var countdownJob: Job? = null

    /** 쿨다운 오버레이가 화면에 표시된 시각 (epoch ms). 0이면 미표시. */
    @Volatile var showedAtMillis: Long = 0L
        private set

    /** 쿨다운 오버레이가 dismiss된 시각 (epoch ms). 0이면 미발생. */
    @Volatile var dismissedAtMillis: Long = 0L
        private set

    /** 마지막 쿨다운의 카운트다운 시간 (초). fallback 판별에 사용. */
    @Volatile var lastCountdownSec: Int = 0
        private set

    fun isShowing(): Boolean = overlayView != null

    /** 세션 종료(안전 확인, TTL 만료) 시 표시 중인 쿨다운을 즉시 닫는다. */
    fun dismissIfShowing() {
        if (!isShowing()) return
        mainHandler.post { dismiss() }
        Log.d(TAG, "dismissIfShowing — 세션 종료로 쿨다운 해제")
    }

    /** 디버그/미리보기 전용. 지정된 초 수만큼 HIGH 레벨로 쿨다운을 표시한다. */
    fun triggerPreview(countdownSec: Int) {
        if (isShowing()) return
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post { startCooldown(countdownSec, RiskLevel.HIGH, null, isCallActive = false) }
    }

    /**
     * 위험 수준에 따라 카운트다운 시간을 결정하고, 쿨다운을 시작한다.
     * 이미 표시 중이거나 SYSTEM_ALERT_WINDOW 권한이 없으면 생략한다.
     *
     * @param reason 쿨다운 이유 설명 — 현재 세션 컨텍스트에 맞는 사용자 설명형 문구.
     * @param isCallActive true이면 주 버튼 "전화 앱으로 이동"(showInCallScreen 경로), false이면 "일단 닫기"(dismiss).
     */
    fun triggerIfNotActive(level: RiskLevel, reason: String? = null, isCallActive: Boolean = true) {
        if (isShowing()) {
            Log.d(TAG, "already showing — skipped")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음 — 쿨다운 생략")
            return
        }
        val countdownSec = when (level) {
            RiskLevel.CRITICAL -> 60
            RiskLevel.HIGH -> 30
            else -> 10
        }
        Log.d(TAG, "쿨다운 발동: level=$level, countdown=${countdownSec}초, reason=$reason, isCallActive=$isCallActive")
        mainHandler.post { startCooldown(countdownSec, level, reason, isCallActive) }
    }

    private fun startCooldown(countdownSec: Int, level: RiskLevel, reason: String?, isCallActive: Boolean) {
        // main thread 직렬 실행 보장: triggerIfNotActive의 isShowing() 체크는
        // mainHandler.post 전에 실행되므로 2개 이상의 startCooldown이 동시 enqueue될 수 있다.
        // 여기서 한 번 더 체크하여 뷰 중복 생성을 방지한다.
        if (overlayView != null) {
            Log.d(TAG, "startCooldown 중복 방지 — 이미 표시 중")
            return
        }
        val (root, countdownText, bottomText, endCallButton) = buildView(countdownSec, level, reason, isCallActive)
        val params = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        )
        try {
            windowManager.addView(root, params)
            overlayView = root
            showedAtMillis = System.currentTimeMillis()
            lastCountdownSec = countdownSec
            endCallButton.requestFocus()
            Log.d(TAG, "쿨다운 시작: ${countdownSec}초, showedAt=$showedAtMillis")
        } catch (e: Exception) {
            Log.e(TAG, "쿨다운 addView 실패: ${e.message}")
            return
        }

        countdownJob = scope.launch {
            for (remaining in countdownSec downTo 1) {
                mainHandler.post {
                    countdownText.text = remaining.toString()
                    bottomText.text = "${remaining}초 후 앱 사용 가능합니다"
                }
                delay(1_000L)
            }
            mainHandler.post { dismiss() }
        }
    }

    private fun dismiss() {
        countdownJob?.cancel()
        countdownJob = null
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
            Log.d(TAG, "쿨다운 종료")
        } catch (e: Exception) {
            Log.e(TAG, "쿨다운 removeView 실패: ${e.message}")
        } finally {
            overlayView = null
            dismissedAtMillis = System.currentTimeMillis()
            Log.d(TAG, "dismissedAt=$dismissedAtMillis")
        }
    }

    // ── 레이아웃 ────────────────────────────────────────────────────────────

    private data class CooldownViews(
        val root: LinearLayout,
        val countdownText: TextView,
        val bottomText: TextView,
        val endCallButton: Button,
    )

    private fun buildView(countdownSec: Int, level: RiskLevel, reason: String?, isCallActive: Boolean): CooldownViews {
        val bg = when (level) {
            RiskLevel.CRITICAL -> Color.parseColor("#B71C1C")
            else -> Color.parseColor("#BF360C")
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // ── 본문 영역 ────────────────────────────────────────────────
        val contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(16), dp(20), dp(16), dp(0))
        }

        // "⚠ 잠깐!"
        contentArea.addView(TextView(context).apply {
            text = "⚠ 잠깐!"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })

        // 카운트다운 숫자
        val countdownText = TextView(context).apply {
            text = countdownSec.toString()
            textSize = 56f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        contentArea.addView(countdownText)

        // 안내 메인
        contentArea.addView(TextView(context).apply {
            text = "지금 은행 앱을 사용하려고 하고 있습니다."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        })

        // 경고 문구 — 핵심 메시지, 화면 폭에 가깝게 크게 표시
        val warningText = if (level == RiskLevel.CRITICAL) {
            "매우 위험한 상황입니다!\n절대 송금하지 마시고\n가족에게 먼저 확인하세요."
        } else {
            "의심스러운 활동이\n감지된 상태입니다.\n잠시 멈추고 확인해 주세요."
        }
        contentArea.addView(TextView(context).apply {
            text = warningText
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })

        // 이유 설명 — 세션 컨텍스트에 맞는 구체적 이유
        if (reason != null) {
            contentArea.addView(TextView(context).apply {
                text = reason
                textSize = 15f
                setTextColor(Color.parseColor("#FFCDD2"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
            })
        }

        // 남은 시간
        val bottomText = TextView(context).apply {
            text = "${countdownSec}초 후 앱 사용 가능합니다"
            textSize = 14f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        contentArea.addView(bottomText)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(contentArea)
        }
        root.addView(scrollView)

        // ── 버튼 영역 ──────────────────────────────────────────────
        val buttonArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(24), dp(12), dp(24), dp(32))
        }

        val cornerPx = dp(8).toFloat()
        // 통화 중: "전화 앱으로 이동" — 사용자가 직접 종료
        // 통화 종료 후: "일단 닫기" — 즉시 dismiss (세션 종료는 하단 "안전 확인했어요" 버튼이 담당)
        val actionBtn = Button(context).apply {
            text = if (isCallActive) "전화 앱으로 이동" else "일단 닫기"
            textSize = 18f
            setTextColor(bg)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setOnClickListener {
                countdownJob?.cancel()
                countdownJob = null
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
        buttonArea.addView(actionBtn)

        root.addView(buttonArea)

        return CooldownViews(root, countdownText, bottomText, actionBtn)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
