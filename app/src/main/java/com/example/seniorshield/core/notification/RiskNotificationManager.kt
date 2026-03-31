package com.example.seniorshield.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.seniorshield.MainActivity
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 위험 이벤트 발생 시 로컬 푸시 알림을 표시한다.
 *
 * [createChannel]은 앱 시작 시 한 번만 호출하면 된다 (중복 호출 무해).
 * [notify]는 위험 수준이 MEDIUM 이상일 때 [DefaultRiskDetectionCoordinator]에서 호출된다.
 *
 * 정책 예외: 제품 원칙 2("백그라운드 알림 금지")와 충돌하지만,
 * 보호자가 아닌 본인에게만 표시하는 자기보호 기능이므로 의도적으로 허용한다.
 * (CLAUDE.md "승인된 예외" 참조, 2026-03-31 확정)
 */
@Singleton
class RiskNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 알림 채널을 생성한다. 앱 시작 시 한 번 호출한다. */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "금융사기 위험 경고",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "금융사기 위험이 감지될 때 즉시 알려드립니다."
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /** 위험 이벤트를 알림으로 표시한다. POST_NOTIFICATIONS 권한이 없으면 조용히 건너뛴다. */
    fun notify(event: RiskEvent) {
        if (!hasNotificationPermission()) return

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(event.title)
            .setContentText(event.description)
            .setPriority(event.level.toNotificationPriority())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(event.id.hashCode(), notification)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun RiskLevel.toNotificationPriority(): Int = when (this) {
        RiskLevel.CRITICAL -> NotificationCompat.PRIORITY_MAX
        RiskLevel.HIGH -> NotificationCompat.PRIORITY_HIGH
        RiskLevel.MEDIUM -> NotificationCompat.PRIORITY_DEFAULT
        RiskLevel.LOW -> NotificationCompat.PRIORITY_LOW
    }

    companion object {
        const val CHANNEL_ID = "senior_shield_risk_alert"
    }
}
