package com.example.seniorshield.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 통화 상태 확인 유틸리티.
 *
 * RiskOverlayManager, BankingCooldownManager에서 현재 통화 중 여부를 판단할 때 사용한다.
 */
@Singleton
class CallEndHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 현재 통화 중인지 동기적으로 확인한다. */
    fun isInCall(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.isInCall ?: false
        } catch (_: SecurityException) {
            // 권한 검사 후 실제 통화 상태 조회 전에 권한이 철회될 수 있다.
            false
        }
    }

    /**
     * 사용자 클릭으로 현재 통화 화면 열기를 요청한다.
     * true는 API가 예외 없이 반환했다는 뜻이며, 전화 UI가 실제 표시됐음을 보장하지 않는다.
     */
    fun showInCallScreen(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            telecom.showInCallScreen(false)
            true
        } catch (_: SecurityException) {
            // 권한 검사와 전화 화면 요청 사이의 권한 철회도 호출 실패로 처리한다.
            false
        }
    }
}
