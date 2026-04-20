package com.example.seniorshield.core.util

import android.content.Context
import android.telecom.TelecomManager
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
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return telecom?.isInCall ?: false
    }
}
