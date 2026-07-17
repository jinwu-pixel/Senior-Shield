package com.example.seniorshield.monitoring.model

/**
 * monitor 방출의 생산 시점 provenance envelope (A안, 2026-07-16 승인).
 *
 * [producedAtEpoch]는 값이 **생산된 순간**의 사용자 reset 세대
 * ([com.example.seniorshield.monitoring.session.ResetEpochProvider.userResetEpoch])다.
 * 각 Real monitor가 **조회·분류를 시작하기 전**에 캡처해 스탬프하므로, 생산과 수신이
 * flowOn/shareIn/callbackFlow 버퍼로 분리되어도 reset 이전 생산 데이터가 이후에
 * 신선한 것으로 위장될 수 없다 (조회 도중 reset이 끼면 보수적으로 stale 판정).
 *
 * ## 동등성 계약 (라운드 9-11 확정)
 * data class 기본 equals는 epoch를 **포함**한다. distinctUntilChanged 적용 시:
 * - 상태 flow (CALL/APP_USAGE/BANKING/DEVICE_ENV): `{ a, b -> a.value == b.value }` —
 *   value만 비교해 "같은 값 + 새 epoch 재방출 억제" 계약(latched·α·snooze 의미 불변)을 유지한다.
 * - 사건 flow (APP_INSTALL): 기본 동등성 그대로 — reset 후 5초 hold 내 실제 신규 설치는
 *   같은 값이어도 새 epoch이므로 통과해야 한다. 같은 epoch의 중복 설치는 기존처럼 합쳐진다.
 */
data class Produced<out T>(
    val value: T,
    val producedAtEpoch: Long,
)
