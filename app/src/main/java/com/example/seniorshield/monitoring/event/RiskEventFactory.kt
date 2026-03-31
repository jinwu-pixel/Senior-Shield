package com.example.seniorshield.monitoring.event

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RiskEventFactory @Inject constructor() {

    fun create(score: RiskScore): RiskEvent {
        val (title, description) = buildMessage(score.signals)
        return RiskEvent(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            occurredAtMillis = System.currentTimeMillis(),
            level = score.level,
            signals = score.signals,
        )
    }

    private fun buildMessage(signals: List<RiskSignal>): Pair<String, String> = when {
        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in signals &&
                (RiskSignal.REPEATED_UNKNOWN_CALLER in signals || RiskSignal.REPEATED_CALL_THEN_LONG_TALK in signals) ->
            "반복 호출 후 텔레뱅킹 시도 감지" to
                "반복적인 의심 전화 후 은행 ARS 번호로 발신이 감지되었습니다. 보이스피싱이 강력히 의심됩니다. 절대 송금하지 마세요."

        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in signals ->
            "의심 통화 후 텔레뱅킹 시도 감지" to
                "수상한 통화 직후 은행 ARS 번호로 전화하려는 것이 감지되었습니다. 상대방의 지시로 전화하는 것이라면 즉시 중단해 주세요."

        RiskSignal.REPEATED_CALL_THEN_LONG_TALK in signals ->
            "반복 호출 후 장시간 통화 감지" to
                "같은 유형의 번호에서 반복 전화 후 장시간 통화가 진행되고 있습니다. 금융사기 수법일 수 있으니 주의해 주세요."

        RiskSignal.REPEATED_UNKNOWN_CALLER in signals ->
            "의심스러운 반복 호출 감지" to
                "확인되지 않은 번호에서 반복적으로 전화가 오고 있습니다. 금융기관을 사칭한 전화일 수 있으니 주의해 주세요."

        RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP in signals ->
            "원격제어 중 금융 앱 실행 감지" to
                "원격제어 앱이 실행된 직후 금융 앱이 열렸습니다. 즉시 통화를 종료하고 주의해 주세요."

        RiskSignal.SUSPICIOUS_APP_INSTALLED in signals ->
            "통화 중 의심스러운 앱 설치 감지" to
                "통화 중에 출처를 알 수 없는 앱이 설치되었습니다. 상대방의 지시로 설치한 앱이라면 즉시 삭제해 주세요."

        RiskSignal.REMOTE_CONTROL_APP_OPENED in signals && RiskSignal.UNKNOWN_CALLER in signals ->
            "의심 통화 후 원격제어 앱 실행" to
                "알 수 없는 번호와 통화 직후 원격제어 앱이 실행되었습니다. 개인정보 피해가 우려됩니다."

        RiskSignal.REMOTE_CONTROL_APP_OPENED in signals ->
            "원격제어 앱 실행 감지" to
                "원격제어 앱이 실행된 것이 확인되었습니다. 본인이 실행한 것이 아니라면 즉시 종료해 주세요."

        RiskSignal.UNKNOWN_CALLER in signals && RiskSignal.LONG_CALL_DURATION in signals ->
            "장시간 의심 통화 감지" to
                "확인되지 않은 번호와 장시간 통화가 감지되었습니다. 금융 관련 요청에는 응하지 마세요."

        RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT in signals ->
            "위험 기기 환경 감지" to
                "기기 보안 환경이 위험 상태입니다. 금융 앱 사용에 주의해 주세요."

        else ->
            "금융사기 의심 흐름 감지" to
                "위험 패턴이 감지되었습니다. 상황을 확인해 주세요."
    }
}
