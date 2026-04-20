package com.example.seniorshield.feature.permissions

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.example.seniorshield.domain.model.PermissionStatus
import com.example.seniorshield.domain.model.PermissionType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _items = MutableStateFlow(buildItems())
    val items: StateFlow<List<PermissionStatus>> = _items.asStateFlow()

    /** 화면이 다시 보일 때(onResume) 호출해 권한 상태를 갱신한다. */
    fun refresh() {
        _items.value = buildItems()
    }

    private fun buildItems(): List<PermissionStatus> = listOf(
        PermissionStatus(
            type = PermissionType.PHONE_STATE,
            name = "전화 상태 읽기",
            description = "통화 시작·종료 감지를 통해 금융사기 위험을 탐지하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_PHONE_STATE),
        ),
        PermissionStatus(
            type = PermissionType.USAGE_ACCESS,
            name = "앱 사용 기록",
            description = "원격제어 앱·뱅킹 앱 연계 사용 패턴을 감지하기 위해 필요합니다. " +
                    "시스템 설정 > 사용량 데이터 접근에서 허용하세요.",
            granted = isUsageAccessGranted(),
        ),
        PermissionStatus(
            type = PermissionType.NOTIFICATION,
            name = "알림",
            description = "위험 경고를 제때 알려드리기 위해 필요합니다.",
            granted = isNotificationPermissionGranted(),
        ),
        PermissionStatus(
            type = PermissionType.OVERLAY,
            name = "다른 앱 위에 표시",
            description = "통화 중이나 다른 앱 사용 중에도 위험 경고 팝업을 즉시 표시하기 위해 필요합니다.",
            granted = Settings.canDrawOverlays(context),
        ),
        PermissionStatus(
            type = PermissionType.ANSWER_CALLS,
            name = "전화 상태 제어(예약)",
            description = "향후 통화 직접 제어 기능을 위해 예약된 권한입니다. " +
                    "현재 앱은 위험 통화 시 '전화 앱으로 이동' 경로로 동작하며, 이 권한 없이도 팝업은 정상 표시됩니다.",
            granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                isGranted(Manifest.permission.ANSWER_PHONE_CALLS) else true,
        ),
        PermissionStatus(
            type = PermissionType.READ_CONTACTS,
            name = "연락처 읽기",
            description = "저장된 번호에서 걸려온 전화는 위험 신호에서 제외하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_CONTACTS),
        ),
        PermissionStatus(
            type = PermissionType.READ_CALL_LOG,
            name = "통화 기록",
            description = "최신 Android에서 수신 번호를 파악해 연락처 대조를 하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_CALL_LOG),
        ),
        @Suppress("DEPRECATION") // PROCESS_OUTGOING_CALLS — 텔레뱅킹 발신 번호 선캡처 용도
        PermissionStatus(
            type = PermissionType.OUTGOING_CALLS,
            name = "발신 전화 감지",
            description = "은행 ARS 발신을 즉시 감지해 텔레뱅킹 유도 사기를 경고하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.PROCESS_OUTGOING_CALLS),
        ),
    )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isNotificationPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return isGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * PACKAGE_USAGE_STATS는 일반 런타임 권한 API로 확인할 수 없다.
     * UsageStatsManager 쿼리 결과가 비어 있으면 권한이 없는 것으로 간주한다.
     */
    private fun isUsageAccessGranted(): Boolean {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000L
        return try {
            val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
