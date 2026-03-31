package com.example.seniorshield.monitoring.model

data class CallContext(
    val state: CallState,
    val phoneNumber: String?,       // 수집 실패 시 null
    val startedAtMillis: Long?,     // OFFHOOK 기준 실제 통화 시작 시각. RINGING→IDLE(부재중/거절)이면 null
    val endedAtMillis: Long?,       // IDLE 전환 시점
    val durationSec: Long,          // RINGING→IDLE(부재중/거절)이면 0
    val isUnknownCaller: Boolean?,  // true=미확인, false=확인됨, null=판단 불가
    val isVerifiedCaller: Boolean?, // true=검증됨, false=미검증, null=판단 불가
)
