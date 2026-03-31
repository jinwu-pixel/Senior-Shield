/*
 * GuardianSmsManager — 비활성화됨 (2026-03-31, P0 SMS 정책 정리)
 *
 * 이 파일은 가족판(Family Edition) 복원을 위해 코드를 그대로 보존한다.
 * 공개판(self-protection only)에서는 SEND_SMS 권한 및 자동 SMS 발송이 금지된다.
 * 복원 시: GuardianSmsManager 주석 해제 + AndroidManifest SEND_SMS 권한 추가 +
 *          DefaultRiskDetectionCoordinator smsManager 파라미터 복원 필요.
 */

/*
package com.example.seniorshield.core.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.repository.GuardianRepository
import com.example.seniorshield.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-SmsManager"

/**
 * HIGH+ 위험 이벤트 발생 시 등록된 보호자에게 SMS를 전송한다.
 *
 * - [SettingsRepository.observeSmsAlertEnabled]가 false면 전송하지 않는다.
 * - SEND_SMS 권한이 없으면 로그만 남기고 조용히 종료한다.
 * - 보호자가 등록되어 있지 않으면 아무 동작도 하지 않는다.
 */
@Singleton
class GuardianSmsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guardianRepository: GuardianRepository,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * 설정이 켜져 있을 때 등록된 모든 보호자에게 위험 알림 SMS를 전송한다.
     * suspend 함수이므로 코루틴 컨텍스트에서 호출해야 한다.
     */
    suspend fun notifyGuardiansIfEnabled(event: RiskEvent) {
        val enabled = settingsRepository.observeSmsAlertEnabled().first()
        if (!enabled) {
            Log.d(TAG, "SMS 알림 꺼짐 — 전송 생략")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "SEND_SMS 권한 없음 — 전송 생략")
            return
        }

        val guardians = guardianRepository.getGuardians()
        if (guardians.isEmpty()) {
            Log.d(TAG, "등록된 보호자 없음 — 전송 생략")
            return
        }

        val message = buildMessage(event)
        val smsManager = context.getSystemService(SmsManager::class.java)
        var sentCount = 0

        for (guardian in guardians) {
            try {
                smsManager.sendTextMessage(guardian.phoneNumber, null, message, null, null)
                Log.d(TAG, "SMS 전송: ${guardian.name}(${guardian.phoneNumber})")
                sentCount++
            } catch (e: Exception) {
                Log.e(TAG, "SMS 전송 실패 (${guardian.name}): ${e.message}")
            }
        }

        Log.d(TAG, "SMS 전송 완료: $sentCount/${guardians.size}명")
    }

    private fun buildMessage(event: RiskEvent): String {
        val levelLabel = when (event.level.name) {
            "CRITICAL" -> "매우 위험"
            "HIGH"     -> "위험"
            else       -> event.level.name
        }
        return "[시니어쉴드] ${levelLabel} 수준의 금융사기 위험이 감지되었습니다.\n" +
                "내용: ${event.title}\n" +
                "보호자 앱에서 확인해 주세요."
    }
}
*/
