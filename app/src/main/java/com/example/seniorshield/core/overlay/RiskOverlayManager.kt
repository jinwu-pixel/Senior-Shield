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
import com.example.seniorshield.MainActivity
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Overlay"

/** dismiss() 후 endCall() 호출까지 지연 시간. 오버레이 제거가 완료된 뒤 통화 종료. */
private const val END_CALL_AFTER_DISMISS_DELAY_MS = 150L

/** IDLE 감지 후 suppression 유지 시간. 다이얼러 정리 시퀀스 안정화 대기. */
private const val POST_CALL_UI_STABILIZATION_MS = 500L

/** IDLE이 감지되지 않을 경우 suppression 강제 해제 (안전 타임아웃). */
private const val MAX_END_CALL_SUPPRESSION_MS = 3_000L

/**
 * 위험 감지 시 화면 전체를 덮는 경고 팝업을 표시한다.
 *
 * TYPE_APPLICATION_OVERLAY + MATCH_PARENT 높이로 다른 앱 위, 통화 중에도 표시된다.
 * SYSTEM_ALERT_WINDOW 권한이 없으면 조용히 생략한다.
 *
 * 주 버튼은 "지금 전화 끊기" — 피해자에게 가장 먼저 필요한 행동을 직접 실행한다.
 * ANSWER_PHONE_CALLS 권한이 없으면 버튼 탭 시 자동 종료 없이 팝업만 닫힌다.
 */
@Singleton
class RiskOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callEndHelper: CallEndHelper,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: LinearLayout? = null

    // ── end-call suppression state ──────────────────────────────────
    @Volatile private var endCallSuppressionActive = false
    @Volatile private var idleStabilizationScheduled = false
    private var suppressionReleaseRunnable: Runnable? = null

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
                primaryButton.requestFocus()
                Log.d(TAG, "전체화면 팝업 표시: level=${event.level}, title=${event.title}")
            } catch (e: Exception) {
                Log.e(TAG, "팝업 addView 실패: ${e.message}")
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            val view = overlayView ?: return@post
            try {
                windowManager.removeView(view)
                Log.d(TAG, "팝업 닫힘")
            } catch (e: Exception) {
                Log.e(TAG, "팝업 removeView 실패: ${e.message}")
            } finally {
                overlayView = null
            }
        }
    }

    // ── end-call suppression ───────────────────────────────────────

    /** coordinator가 UI 억제 여부를 확인할 때 사용. */
    fun isEndCallSuppressed(): Boolean = endCallSuppressionActive

    /**
     * "지금 전화 끊기" 버튼 탭 시 호출.
     * 안전 타임아웃을 건 뒤, IDLE 감지 또는 타임아웃 도래 시 해제된다.
     */
    private fun startEndCallSuppression() {
        endCallSuppressionActive = true
        idleStabilizationScheduled = false
        suppressionReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        val safetyRelease = Runnable {
            endCallSuppressionActive = false
            idleStabilizationScheduled = false
            suppressionReleaseRunnable = null
            Log.d(TAG, "suppression force-released (safety timeout)")
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
            setPadding(dp(32), dp(56), dp(32), dp(0))
        }

        contentArea.addView(TextView(context).apply {
            text = "⚠"
            textSize = 80f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })

        contentArea.addView(TextView(context).apply {
            text = levelLabel
            textSize = 18f
            setTextColor(bgColor)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(6), dp(20), dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        })

        contentArea.addView(TextView(context).apply {
            text = event.title
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(24)
            }
        })

        contentArea.addView(TextView(context).apply {
            text = event.description
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        })

        // ── 버튼 영역 ─────────────────────────────────────────────
        val buttonArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(dp(24), dp(16), dp(24), dp(48))
        }

        // 주 버튼: 지금 전화 끊기
        val cornerPx = dp(8).toFloat()
        val primaryBtn = Button(context).apply {
            text = "지금 전화 끊기"
            textSize = 18f
            setTextColor(bgColor)
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(60))
            setOnClickListener {
                Log.d(TAG, "end-call button clicked")
                dismiss()
                Log.d(TAG, "overlay dismissed before endCall")
                startEndCallSuppression()
                mainHandler.postDelayed({
                    Log.d(TAG, "delayed endCall executed")
                    callEndHelper.endCurrentCall()
                }, END_CALL_AFTER_DISMISS_DELAY_MS)
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

        // 보조 버튼: 보호자에게 도움 요청 (guardian이 설정된 경우에만 표시)
        if (guardian != null) {
            buttonArea.addView(Button(context).apply {
                text = "보호자에게 도움 요청"
                textSize = 16f
                setTextColor(Color.WHITE)
                isFocusable = true
                isFocusableInTouchMode = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = cornerPx
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply {
                    topMargin = dp(12)
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
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply {
                topMargin = dp(12)
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
}
