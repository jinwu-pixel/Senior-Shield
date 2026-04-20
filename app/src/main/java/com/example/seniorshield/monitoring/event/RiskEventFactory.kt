package com.example.seniorshield.monitoring.event

import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RiskEventFactory @Inject constructor() {

    fun create(score: RiskScore, triggerSignals: Set<RiskSignal>? = null): RiskEvent {
        val messageSignals = if (triggerSignals != null) {
            triggerSignals.toList()
        } else {
            score.signals
        }
        val (title, description) = buildMessage(messageSignals)
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
            "은행 전화 유도 의심" to
                "확인되지 않은 번호의 반복 전화 이후 은행 ARS 번호 발신이 감지되었습니다. 누군가의 지시라면 즉시 중단하고 절대 송금하지 마세요."

        RiskSignal.TELEBANKING_AFTER_SUSPICIOUS in signals ->
            "은행 ARS 발신 감지" to
                "위험 신호가 감지된 뒤 은행 ARS 번호로 발신하려고 합니다. 지시를 받고 전화하는 것이라면 즉시 중단하세요."

        RiskSignal.REPEATED_CALL_THEN_LONG_TALK in signals ->
            "반복 전화 경고" to
                "확인되지 않은 번호에서 반복 전화와 장시간 연결이 감지되었습니다. 금융 관련 요청에는 응하지 마세요."

        RiskSignal.REPEATED_UNKNOWN_CALLER in signals ->
            "의심스러운 반복 호출" to
                "확인되지 않은 번호에서 반복적으로 전화가 오고 있습니다. 금융기관이나 공공기관을 사칭한 전화일 수 있으니 주의하세요."

        RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP in signals ->
            "원격제어 중 금융 앱 실행 감지" to
                "원격제어 앱 실행 중 금융 앱이 열렸습니다. 송금이나 인증을 진행하지 마세요."

        RiskSignal.SUSPICIOUS_APP_INSTALLED in signals ->
            "의심 앱 설치 감지" to
                "출처를 알 수 없는 앱이 설치되었습니다. 누군가의 지시로 설치한 앱이라면 즉시 삭제하세요."

        RiskSignal.REMOTE_CONTROL_APP_OPENED in signals ->
            "원격제어 앱 실행 감지" to
                "원격제어 앱이 실행되었습니다. 본인이 직접 실행한 것이 아니라면 즉시 종료하세요."

        RiskSignal.UNKNOWN_CALLER in signals && RiskSignal.LONG_CALL_DURATION in signals ->
            "장시간 의심 통화 감지" to
                "확인되지 않은 번호와 장시간 연결이 감지되었습니다. 금융 관련 요청에는 응하지 마세요."

        RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT in signals ->
            "위험 기기 환경 감지" to
                "기기 보안 상태가 안전하지 않을 수 있습니다. 금융 앱 사용과 개인정보 입력에 주의하세요."

        else ->
            "금융사기 의심 흐름 감지" to
                "위험 패턴이 감지되었습니다. 안내 내용을 확인하고 송금이나 앱 설치를 잠시 멈추세요."
    }
}
