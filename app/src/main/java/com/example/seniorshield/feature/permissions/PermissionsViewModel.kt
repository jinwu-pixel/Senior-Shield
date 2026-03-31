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
            name = "전화 상태 읽기",
            description = "통화 시작·종료 감지를 통해 금융사기 위험을 탐지하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_PHONE_STATE),
        ),
        PermissionStatus(
            name = "앱 사용 기록",
            description = "원격제어 앱·뱅킹 앱 연계 사용 패턴을 감지하기 위해 필요합니다. " +
                    "시스템 설정 > 사용량 데이터 접근에서 허용하세요.",
            granted = isUsageAccessGranted(),
        ),
        PermissionStatus(
            name = "알림",
            description = "위험 경고를 제때 알려드리기 위해 필요합니다.",
            granted = isNotificationPermissionGranted(),
        ),
        PermissionStatus(
            name = "다른 앱 위에 표시",
            description = "통화 중이나 다른 앱 사용 중에도 위험 경고 팝업을 즉시 표시하기 위해 필요합니다.",
            granted = Settings.canDrawOverlays(context),
        ),
        PermissionStatus(
            name = "전화 끊기",
            description = "위험 경고 팝업에서 '지금 전화 끊기' 버튼으로 통화를 즉시 종료하기 위해 필요합니다. " +
                    "일부 기기에서는 허용해도 동작하지 않을 수 있습니다 — 그 경우 수동으로 통화를 끊어 주세요.",
            granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                isGranted(Manifest.permission.ANSWER_PHONE_CALLS) else true,
        ),
        PermissionStatus(
            name = "연락처 읽기",
            description = "저장된 번호에서 걸려온 전화는 위험 신호에서 제외하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_CONTACTS),
        ),
        PermissionStatus(
            name = "통화 기록",
            description = "최신 Android에서 수신 번호를 파악해 연락처 대조를 하기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.READ_CALL_LOG),
        ),
        PermissionStatus(
            name = "문자 메시지 전송",
            description = "위험 감지 시 등록된 보호자에게 자동으로 SMS를 보내기 위해 필요합니다.",
            granted = isGranted(Manifest.permission.SEND_SMS),
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
