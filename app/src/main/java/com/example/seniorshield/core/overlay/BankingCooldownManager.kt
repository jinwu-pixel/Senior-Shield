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

/**
 * HIGH+ 위험 세션 중 뱅킹 앱이 포그라운드로 올라오면
 * 위험 수준에 따라 차등적으로 화면 전체를 막는 강제 대기 화면을 표시한다.
 *
 * - HIGH:     30초 카운트다운 + "전화 끊기" 버튼
 * - CRITICAL: 60초 카운트다운 + "전화 끊기" 버튼
 *
 * 해제 버튼 없음 — 타이머 종료 시 자동으로 닫힌다.
 */
@Singleton
class BankingCooldownManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callEndHelper: CallEndHelper,
    private val overlayManager: RiskOverlayManager,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var overlayView: LinearLayout? = null
    private var countdownJob: Job? = null

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
     * @param isCallActive true이면 "전화 끊기" CTA 포함, false이면 "확인했습니다" CTA로 다운그레이드.
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
            endCallButton.requestFocus()
            Log.d(TAG, "쿨다운 시작: ${countdownSec}초")
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
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setPadding(dp(32), dp(48), dp(32), dp(0))
        }

        // "⚠ 잠깐!"
        contentArea.addView(TextView(context).apply {
            text = "⚠ 잠깐!"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })

        // 카운트다운 숫자
        val countdownText = TextView(context).apply {
            text = countdownSec.toString()
            textSize = 96f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }
        contentArea.addView(countdownText)

        // 안내 메인
        contentArea.addView(TextView(context).apply {
            text = "지금 은행 앱을\n사용하려고 하고 있습니다."
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(24)
            }
        })

        // 경고 문구 — CRITICAL일 때 더 강한 메시지
        val warningText = if (level == RiskLevel.CRITICAL) {
            "매우 위험한 상황입니다!\n절대 송금하지 마시고\n가족에게 먼저 확인하세요."
        } else {
            "의심스러운 활동이 감지된 상태입니다.\n잠시 멈추고 확인해 주세요."
        }
        contentArea.addView(TextView(context).apply {
            text = warningText
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(16)
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
                    topMargin = dp(12)
                }
            })
        }

        // 남은 시간
        val bottomText = TextView(context).apply {
            text = "${countdownSec}초 후 앱 사용 가능합니다"
            textSize = 15f
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(24)
            }
        }
        contentArea.addView(bottomText)

        root.addView(contentArea)

        // ── 버튼 영역 ──────────────────────────────────────────────
        val buttonArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(24), dp(16), dp(24), dp(48))
        }

        val cornerPx = dp(8).toFloat()
        // 통화 중: "전화 앱으로 이동" — 사용자가 직접 종료
        // 통화 종료 후: "확인했습니다" — 즉시 dismiss
        val actionBtn = Button(context).apply {
            text = if (isCallActive) "전화 앱으로 이동" else "확인했습니다"
            textSize = 18f
            setTextColor(bg)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(60))
            setOnClickListener {
                countdownJob?.cancel()
                countdownJob = null
                if (callEndHelper.isInCall()) {
                    Log.d(TAG, "opening dialer for manual call end")
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                    telecom?.showInCallScreen(false)
                }
                dismiss()
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
