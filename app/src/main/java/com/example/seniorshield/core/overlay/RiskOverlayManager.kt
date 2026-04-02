package com.example.seniorshield.core.overlay

import android.content.Context
import android.content.Intent
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
import com.example.seniorshield.MainActivity
import com.example.seniorshield.core.util.CallEndHelper
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-Overlay"

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

    fun show(event: RiskEvent) {
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
            val (view, primaryButton) = buildView(event)
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

    // ── 레이아웃 ─────────────────────────────────────────────────

    private data class OverlayViews(
        val root: LinearLayout,
        val primaryButton: Button,
    )

    private fun buildView(event: RiskEvent): OverlayViews {
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
                callEndHelper.endCurrentCall()
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
        buttonArea.addView(primaryBtn)

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
