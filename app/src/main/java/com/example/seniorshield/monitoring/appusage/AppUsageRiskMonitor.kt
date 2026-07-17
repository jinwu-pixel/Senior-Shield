package com.example.seniorshield.monitoring.appusage

import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.model.Produced
import kotlinx.coroutines.flow.Flow

interface AppUsageRiskMonitor {
    /**
     * 앱 사용 신호. [Produced.producedAtEpoch]는 poll 반복의 **조회 시작 전**(루프 헤드)에
     * 캡처된다. 복합 신호(BANKING_APP_OPENED_AFTER_REMOTE_APP)가 포함된 방출은 원격앱
     * 앵커가 (재)장전된 시점의 epoch를 승계한다 (min 규칙).
     */
    fun observeAppUsageSignals(): Flow<Produced<List<RiskSignal>>>

    /**
     * 뱅킹 앱이 현재 포그라운드에 있으면 true, 아니면 false를 방출한다.
     * 값이 바뀔 때만 방출한다 (distinctUntilChanged — value만 비교, epoch 제외).
     * epoch는 조회 시작 전 캡처. 조회 실패(Unknown) tick은 방출을 건너뛴다
     * (일시 장애가 "뱅킹 종료"로 위장되는 것 차단; 최초 1회는 combine 충전용 중립 방출).
     */
    fun observeBankingAppForeground(): Flow<Produced<Boolean>>

    /**
     * 최근 [windowMs] 이내에 발생한 뱅킹 앱 MOVE_TO_FOREGROUND 이벤트 중
     * 가장 최근 타임스탬프(epoch ms)를 반환한다.
     * 이벤트가 없거나 조회 실패 시 null.
     */
    fun latestBankingForegroundEventTimestamp(windowMs: Long = 30_000L): Long?
}