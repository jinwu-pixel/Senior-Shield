package com.example.seniorshield.feature.warning

import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskLevel

data class WarningUiState(
    val guardians: List<Guardian> = emptyList(),
    val showGuardianPicker: Boolean = false,
    val message: String? = null,
    val detectedEventTitle: String? = null,
    val detectedEventDescription: String? = null,
    val detectedEventLevel: RiskLevel? = null,
    val smsMenuEnabled: Boolean = false,
    val showSmsPicker: Boolean = false,
    /**
     * Behavior Check(자가확인) 응답. key=문항 index, value=예(true)/아니요(false), 미응답=키 없음.
     * 메모리 전용(휘발성) — 영속 저장·외부 전송·monitoring 피드백 금지.
     */
    val behaviorCheckAnswers: Map<Int, Boolean> = emptyMap(),
) {
    /** 하나라도 "예"면 사기 정황 — 화면 내 경고 문구 강화 전용 (감지/세션 축과 무관). */
    val behaviorCheckAnyYes: Boolean get() = behaviorCheckAnswers.containsValue(true)
}
