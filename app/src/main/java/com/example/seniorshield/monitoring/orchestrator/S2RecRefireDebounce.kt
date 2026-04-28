package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.domain.model.RiskSignal

/**
 * S2 REC-REFIRE debounce gate.
 *
 * Step 2 잠금 (#1, #2, #3, #4, #5, #C7-1) 반영. 설계 문서는
 * `investigations/2026-04-24-cta-semantics/03_step2_design.md` 와
 * `04_step3_impl_plan.md` 참조.
 *
 * ## 책임
 * orchestration-layer debounce gate. monitor가 5s tick으로 같은 scope signal을
 * 재emit할 때 30s window 내 modal(popup) 재발화를 억제한다.
 *
 * ## CTA layer 분리 (Step 2 #6 잠금)
 * 본 게이트는 monitor signal과 clock만으로 결정한다. CTA(dismiss / safe-confirm)는
 * 본 함수의 입력에 일절 관여하지 않는다.
 *
 * ## α 분리 (Step 2 #5 + 04_step3_impl_plan.md §5.0 잠금)
 * α(`RiskSessionTracker.lastResetAt` 등)와 본 게이트는 5축 disjoint이며 같은
 * `UPGRADE_TRIGGERS` set만 의미상 공유한다. 상수/상태/함수 이름은 분리되어 있으며
 * 공용화 리팩터링은 금지된다.
 */

/**
 * S2 REC-REFIRE TTL: 마지막 modal 발동 시각으로부터 30,000ms 경과 후 만료.
 *
 * **TTL 경계 잠금 (Step 2 #C7-1):**
 * - 식: `(now - lastFiredAt) > S2_REC_REFIRE_TTL_MS`
 * - 정확히 30,000ms 일치 시점은 **미만료** (TTL 유지)
 * - 30,001ms부터 만료 (재발동 가능)
 */
internal const val S2_REC_REFIRE_TTL_MS = 30_000L

/**
 * REC-REFIRE 패턴이 구조적으로 발생하는 신호 집합 (Step 2 #3 잠금).
 * `RealAppUsageRiskMonitor`가 5s tick × 30s window 동안 반복 emit하는 2종.
 */
internal val S2_REC_REFIRE_SCOPE: Set<RiskSignal> = setOf(
    RiskSignal.REMOTE_CONTROL_APP_OPENED,
    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
)

/**
 * S2 escape 기준 (Step 2 #5 §5.5).
 *
 * **3중 참조 상태 (의도된 임시 상태):**
 * 동일 의미 set이 현재 다음 3곳에 분산되어 있다:
 *   1. `RiskSessionTracker.kt:28-32` — α arm escape
 *   2. `DefaultRiskDetectionCoordinator.kt:79-83` — same-call snooze upgrade trigger
 *   3. 본 파일 `S2_UPGRADE_TRIGGERS` — S2 REC-REFIRE debounce escape
 *
 * **PR1 범위 결정:**
 * PR1에서는 S2 REC-REFIRE debounce 구현 범위를 유지하기 위해 trigger set 단일화를
 * 수행하지 않는다. 본 PR1은 **의미 일치만 유지**한다. 즉 세 set의 원소는 동일해야 한다.
 *
 * **Follow-up 추적:**
 * 단일 truth source 통합 또는 equivalence guard(컴파일/런타임 동등성 검증) 도입은
 * **follow-up / PR2 이후 항목**으로 추적된다. 자세한 추적은
 * `investigations/2026-04-24-cta-semantics/04_step3_impl_plan.md` §11 참조.
 *
 * **drift 금지:**
 * 본 복제는 의도된 임시 상태이며 **조용한 drift는 허용되지 않는다**. 본 set 또는 위 1·2의
 * 어느 한쪽이라도 원소가 변경될 경우, 같은 PR에서 나머지 두 곳도 동시에 동일 변경되어야 한다.
 * 통합 전까지 본 주석이 그 동기성의 운영 규칙이다.
 *
 * **분리 원칙 (불변, §5.0 + Step 2 #5):**
 * α debounce와 S2 debounce는 같은 set 의미를 공유하지만 상수/상태/함수/테스트 클래스의
 * 이름은 분리되어 있다. 본 set의 통합 ≠ debounce layer의 통합. 통합 follow-up이 진행되더라도
 * α/S2의 5축 disjoint 공존(§5)은 유지되어야 한다.
 */
internal val S2_UPGRADE_TRIGGERS: Set<RiskSignal> = setOf(
    RiskSignal.REMOTE_CONTROL_APP_OPENED,
    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,
    RiskSignal.TELEBANKING_AFTER_SUSPICIOUS,
)

/**
 * S2 게이트 상태. Coordinator collect 블록 내부에서만 read/write되며 외부 노출 없음.
 *
 * @property lastFiredAt 직전 modal 발동 시각. null = 미발동(억제 안 함).
 * @property snapshot 직전 발동 시점의 `this_tick.signals ∩ S2_REC_REFIRE_SCOPE`.
 */
internal data class S2RecRefireDebounceState(
    val lastFiredAt: Long? = null,
    val snapshot: Set<RiskSignal> = emptySet(),
)

/**
 * S2 게이트 pure decision function — Step 2 #4 escape predicate 그대로.
 *
 * ```
 * suppress  =  lastFiredAt != null
 *           && (now - lastFiredAt) <= TTL                              // TTL 미만료
 *           && this_tick ∩ scope ≠ ∅                                   // REC-REFIRE 상황
 *           && (this_tick \ snapshot) ∩ UPGRADE_TRIGGERS = ∅            // escape 미발생
 * ```
 *
 * @return true = modal 억제, false = 허용
 */
internal fun shouldSuppressS2RecRefire(
    state: S2RecRefireDebounceState,
    thisTickSignals: Set<RiskSignal>,
    now: Long,
): Boolean {
    val lastFiredAt = state.lastFiredAt ?: return false

    if (now - lastFiredAt > S2_REC_REFIRE_TTL_MS) return false

    val scopeIntersection = thisTickSignals.intersect(S2_REC_REFIRE_SCOPE)
    if (scopeIntersection.isEmpty()) return false

    val delta = thisTickSignals - state.snapshot
    val upgradeInDelta = delta.intersect(S2_UPGRADE_TRIGGERS)
    if (upgradeInDelta.isNotEmpty()) return false

    return true
}

/**
 * 발동 직후 즉시 호출하여 snapshot을 재무장한다 (Step 2 #4 §4.4 불변 — escape 후 즉시 재무장).
 */
internal fun s2RecRefireStateAfterFiring(
    thisTickSignals: Set<RiskSignal>,
    now: Long,
): S2RecRefireDebounceState = S2RecRefireDebounceState(
    lastFiredAt = now,
    snapshot = thisTickSignals.intersect(S2_REC_REFIRE_SCOPE),
)
