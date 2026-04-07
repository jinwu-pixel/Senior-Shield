package com.example.seniorshield.monitoring.registry

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 원격제어 앱 패키지명 레지스트리.
 *
 * 접두사 매칭(TeamViewer 배포 채널 변형 포괄)과 정확한 패키지명 매칭을 병행한다.
 * AppUsageRiskMonitor, AppInstallRiskMonitor 등 여러 모니터에서 공유한다.
 */
@Singleton
class RemoteControlAppRegistry @Inject constructor() {

    fun matches(packageName: String): Boolean =
        packageName in PACKAGES || PREFIXES.any { packageName.startsWith(it) }

    companion object {
        /**
         * 접두사 매칭: 해당 prefix로 시작하는 모든 패키지를 원격제어 앱으로 간주한다.
         *
         * 배경: TeamViewer는 배포 채널에 따라 패키지명이 다르다.
         *   - Google Play:   com.teamviewer.teamviewer
         *   - Samsung Store: com.teamviewer.teamviewer.market.mobile
         *   - QuickSupport:  com.teamviewer.quicksupport.host
         *   → com.teamviewer. 접두사로 한 번에 포괄한다.
         */
        private val PREFIXES = setOf(
            "com.teamviewer.",   // TeamViewer 전 변형
            "net.anydesk.",      // AnyDesk 구버전
            "com.anydesk.",      // AnyDesk 신버전
        )

        /** 정확한 패키지명 매칭 목록 (접두사 매칭으로 포괄되지 않는 앱) */
        private val PACKAGES = setOf(
            // 알서포트 (국내 다수 사용)
            "com.rsupport.rs.activity.rsupport",              // MobileSupport - RemoteCall
            "com.rsupport.rs.activity.rsupport.sec",          // MobileSupport for SAMSUNG
            "com.rsupport.remotecall.rtc.host",               // RemoteCall

            // 기타
            "com.logmein.rescuemobile",                       // LogMeIn Rescue
            "com.splashtop.remote.pad.v2",                    // Splashtop Personal
            "com.realvnc.viewer.android",                     // RealVNC Viewer
        )
    }
}
