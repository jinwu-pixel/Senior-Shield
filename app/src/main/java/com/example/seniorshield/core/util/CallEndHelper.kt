package com.example.seniorshield.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-CallEnd"

/**
 * 현재 진행 중인 통화를 종료하는 공유 유틸리티.
 *
 * 1. TelecomManager.endCall() — API 28+, ANSWER_PHONE_CALLS 권한
 * 2. AudioManager HEADSETHOOK 키 이벤트 — 권한 불필요 폴백
 */
@Singleton
class CallEndHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun endCurrentCall() {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && tryEndCallApi28(telecom)) return
        dispatchHeadsetHookKey()
        bringInCallScreenToFront(telecom)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun tryEndCallApi28(telecom: TelecomManager?): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "ANSWER_PHONE_CALLS 권한 없음")
            return false
        }
        return try {
            @Suppress("DEPRECATION")
            val result = telecom?.endCall() ?: false
            if (result) Log.d(TAG, "통화 종료 완료 (TelecomManager)")
            else Log.w(TAG, "TelecomManager.endCall() returned false")
            result
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.endCall() 실패: ${e.message}")
            false
        }
    }

    private fun dispatchHeadsetHookKey() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            Log.d(TAG, "HEADSETHOOK 이벤트 전달 완료")
        } catch (e: Exception) {
            Log.w(TAG, "HEADSETHOOK 전달 실패: ${e.message}")
        }
    }

    private fun bringInCallScreenToFront(telecom: TelecomManager?) {
        try {
            telecom?.showInCallScreen(false)
            Log.d(TAG, "통화 화면 포그라운드 이동 요청")
        } catch (e: Exception) {
            Log.e(TAG, "통화 화면 이동 실패: ${e.message}")
            Toast.makeText(context, "지금 바로 전화를 끊어 주세요", Toast.LENGTH_LONG).show()
        }
    }
}
