package com.example.seniorshield.monitoring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.seniorshield.MainActivity
import com.example.seniorshield.monitoring.orchestrator.RiskDetectionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "SeniorShield-Service"

/**
 * 백그라운드에서도 위험 감지를 지속하기 위한 Foreground Service.
 *
 * Android 8.0+에서 백그라운드 앱은 수 분 이내 kill되므로,
 * startForeground()로 시스템에 지속 실행을 알린다.
 * START_STICKY 반환으로 시스템이 강제 종료 후 재시작되어도 감시가 복구된다.
 *
 * 정책 예외: 제품 원칙 2 + 구현 규칙("service 금지")과 충돌하지만,
 * 실시간 위험 감지에 필수적이므로 의도적으로 허용한다.
 * (CLAUDE.md "승인된 예외" 참조, 2026-03-31 확정)
 */
@AndroidEntryPoint
class MonitoringForegroundService : Service() {

    @Inject lateinit var coordinator: RiskDetectionCoordinator

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "service started")
        startForeground(NOTIFICATION_ID, buildNotification())
        coordinator.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed — stopping coordinator")
        coordinator.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannelCreated()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("시니어쉴드 보호 중")
            .setContentText("금융사기 위험을 실시간으로 감시하고 있습니다.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannelCreated() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "시니어쉴드 모니터링 서비스",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "앱이 백그라운드에서 실행 중임을 나타냅니다." }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "monitoring_foreground_channel"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, MonitoringForegroundService::class.java)
            )
        }
    }
}
