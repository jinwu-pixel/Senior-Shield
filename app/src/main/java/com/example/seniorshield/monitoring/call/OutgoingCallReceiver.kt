package com.example.seniorshield.monitoring.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 발신 전화 번호를 OFFHOOK 이전에 선캡처하는 BroadcastReceiver.
 *
 * ACTION_NEW_OUTGOING_CALL 브로드캐스트는 통화가 연결되기 전에 발신 번호를 전달한다.
 * 이를 통해 OFFHOOK 시점에서 CallLog 조회 없이 즉시 텔레뱅킹 번호 매칭이 가능하다.
 *
 * 주의:
 * - ACTION_NEW_OUTGOING_CALL은 API 29에서 deprecated되었으나 Android 14에서도 동작한다.
 * - PROCESS_OUTGOING_CALLS 권한이 필요하다.
 * - 번호는 60초 후 자동 만료되어 다음 통화에 잘못 매칭되는 것을 방지한다.
 * - [consumeIfValid]는 읽기 전용(clear 안 함). IDLE 처리 완료 후 [clear]를 명시적으로 호출해야 한다.
 */
class OutgoingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SeniorShield-CallMonitor"
        private const val EXPIRY_MS = 60_000L

        @Volatile var lastOutgoingNumber: String? = null
            private set
        @Volatile var lastOutgoingTimestamp: Long = 0L
            private set

        /**
         * 만료되지 않은 선캡처 번호를 반환한다.
         * clear하지 않으므로 OFFHOOK과 IDLE 양쪽에서 읽을 수 있다.
         */
        fun consumeIfValid(): String? {
            val number = lastOutgoingNumber ?: return null
            if (SystemClock.elapsedRealtime() - lastOutgoingTimestamp > EXPIRY_MS) {
                clear()
                return null
            }
            return number
        }

        /** IDLE 처리 완료 후 호출하여 다음 통화 오염을 방지한다. */
        fun clear() {
            lastOutgoingNumber = null
            lastOutgoingTimestamp = 0L
        }
    }

    @Suppress("DEPRECATION") // ACTION_NEW_OUTGOING_CALL — deprecated API 29, 동작 확인됨
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) return

        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            ?.takeIf { it.isNotBlank() }
            ?: return

        lastOutgoingNumber = number
        lastOutgoingTimestamp = SystemClock.elapsedRealtime()

        Log.d(TAG, "발신 번호 선캡처: $number")
    }
}
