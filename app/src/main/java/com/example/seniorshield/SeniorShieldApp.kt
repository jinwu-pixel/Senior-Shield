package com.example.seniorshield

import android.app.Application
import com.example.seniorshield.core.notification.RiskNotificationManager
import com.example.seniorshield.monitoring.service.MonitoringForegroundService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 정책 예외: 구현 규칙("빈 상태 유지")과 충돌하지만,
 * 앱 시작 시 즉시 보호를 활성화하기 위해 의도적으로 허용한다.
 * 새 로직 추가는 금지 — 기존 서비스 시작 + 알림 채널 생성만 유지.
 * (CLAUDE.md "승인된 예외" 참조, 2026-03-31 확정)
 */
@HiltAndroidApp
class SeniorShieldApp : Application() {

    @Inject lateinit var notificationManager: RiskNotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannel()
        // 위험 감지 코디네이터는 Foreground Service 내부에서 시작한다.
        // 백그라운드 kill 후 START_STICKY로 자동 재시작된다.
        MonitoringForegroundService.start(this)
    }
}
