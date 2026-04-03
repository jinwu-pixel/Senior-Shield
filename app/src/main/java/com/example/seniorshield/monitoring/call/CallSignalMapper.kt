package com.example.seniorshield.monitoring.call

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.CallContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallSignalMapper @Inject constructor() {

    fun map(context: CallContext, thresholdMs: Long = LONG_CALL_THRESHOLD_MS): List<RiskSignal> {
        // startedAtMillis == null: 실제 연결(OFFHOOK)이 없었던 불완전 컨텍스트 → 신호 없음
        if (context.startedAtMillis == null) return emptyList()
        // durationMs <= 0: 부재중·거절 또는 측정 불가 → 신호 없음
        if (context.durationMs <= 0L) return emptyList()

        return buildList {
            if (context.durationMs >= thresholdMs) {
                add(RiskSignal.LONG_CALL_DURATION)
            }

            // 호출자가 미확인으로 확정된 경우에만 생성 (null이면 판단 보류)
            if (context.isUnknownCaller == true) {
                add(RiskSignal.UNKNOWN_CALLER)
            }

            // 미검증 호출자임이 확정된 경우에만 생성 (null이면 판단 보류)
            if (context.isVerifiedCaller == false) {
                add(RiskSignal.UNVERIFIED_CALLER)
            }
        }
    }

    companion object {
        const val LONG_CALL_THRESHOLD_MS = 180_000L      // 프로덕션: 3분
        const val TEST_LONG_CALL_THRESHOLD_MS = 10_000L   // 테스트 모드: 10초
        const val LONG_CALL_THRESHOLD_SEC = 180L          // 표시/로깅용
        const val TEST_LONG_CALL_THRESHOLD_SEC = 10L      // 표시/로깅용
    }
}
