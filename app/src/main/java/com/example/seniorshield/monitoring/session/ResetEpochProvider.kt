package com.example.seniorshield.monitoring.session

/**
 * 사용자 안전 확인(reset) 세대의 **read-only** 노출 (A안, 2026-07-16 승인).
 *
 * 각 Real monitor는 생산 경계 — 조회·분류를 **시작하기 전** — 에서 이 값을 읽어
 * [com.example.seniorshield.monitoring.model.Produced] 스탬프에 쓴다. 캡처 지점:
 * - CALL: telephony callback/receiver/seed 진입부 (연락처 조회·상태 변이 전)
 * - APP_USAGE/BANKING: poll 루프 헤드 (UsageStats 조회 전)
 * - APP_INSTALL: BroadcastReceiver.onReceive 진입부 (설치 출처 분류 전)
 * - DEVICE_ENV: init 검사 시작 전 1회
 *
 * 구현: [RiskSessionTracker] (쓰기 경로는 tracker의 reset 계열 메서드에만 있다).
 */
interface ResetEpochProvider {
    val userResetEpoch: Long
}
