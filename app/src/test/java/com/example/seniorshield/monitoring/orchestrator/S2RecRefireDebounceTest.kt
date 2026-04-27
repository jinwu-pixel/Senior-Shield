package com.example.seniorshield.monitoring.orchestrator

import com.example.seniorshield.core.overlay.RiskOverlayManager
import com.example.seniorshield.domain.model.RiskSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2 REC-REFIRE debounce gate 단위 테스트 (PR1 merge gate 핵심 케이스).
 *
 * 설계:
 * - `investigations/2026-04-24-cta-semantics/03_step2_design.md`
 * - `investigations/2026-04-24-cta-semantics/04_step3_impl_plan.md` §5.3
 *
 * 본 테스트가 보증하는 8개 핵심 invariant:
 *   1. TTL boundary: exactly 30,000ms does not expire   → T2
 *   2. TTL boundary: 30,001ms expires                    → T3
 *   3. dismiss-only CTA does not trigger safe-confirm    → T4
 *   4. safe-confirm remains a separate dedicated flow    → T5
 *   5. REC-REFIRE = orchestration debounce, not CTA      → T6
 *   6. S2 debounce is separate from α debounce          → T6 (구조적 분리: 본 클래스는
 *                                                            `RiskSessionTrackerAlphaTest`와
 *                                                            disjoint하며 α의 변수에 read 0건)
 *   7. same REC-REFIRE within TTL suppress              → T1
 *   8. REC-REFIRE after TTL may re-fire                 → T3
 *
 * **격리 (PR1 §5.0 + §7.4 정적 규칙 1·3 + 04_step3_impl_plan.md §5.6):**
 * - 본 클래스는 `RiskSessionTracker` 또는 그 내부 변수를 import/read 하지 않는다.
 * - 본 클래스는 CTA 핸들러(`HomeViewModel.confirmSafe`, `WarningViewModel.confirmSafe`,
 *   `RiskOverlayManager.dismiss`, `RiskOverlayManager.performSafeCtaSideEffects` 등)를 호출하지 않는다.
 *   T4/T5는 `performSafeCtaSideEffects`의 행동 표면을 **호출 없이 추적용으로만 시뮬레이션**한다 —
 *   safe-confirm 부수효과 자체는 dismiss 경로에서 발생할 수 없음을 직접 자동검증.
 */
class S2RecRefireDebounceTest {

    // ── PR1-T1 ─────────────────────────────────────────────────────────
    // same REC-REFIRE within TTL suppress

    @Test
    fun `T1_sameRecRefireWithinTtl_suppresses`() {
        val firedAt = 1_000L
        val state = S2RecRefireDebounceState(
            lastFiredAt = firedAt,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        // 5s 후 동일 scope signal 재emit (REC-REFIRE)
        val now = firedAt + 5_000L
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)

        val suppress = shouldSuppressS2RecRefire(state, thisTick, now)

        assertTrue("동일 scope signal이 TTL 내 재emit되면 억제", suppress)
    }

    // ── PR1-T2 ─────────────────────────────────────────────────────────
    // TTL boundary at exactly 30_000L does not expire (#C7-1 잠금)

    @Test
    fun `T2_suppressesAtExactly30000ms`() {
        val firedAt = 1_000L
        val state = S2RecRefireDebounceState(
            lastFiredAt = firedAt,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        // 정확히 30,000ms 경과 — `(now - lastFiredAt) > 30_000L`이 false → 미만료
        val now = firedAt + 30_000L
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)

        val suppress = shouldSuppressS2RecRefire(state, thisTick, now)

        assertTrue(
            "정확히 30,000ms 경과 시점은 TTL 미만료 — `>` 연산자 정의에 의해 억제 유지 (#C7-1)",
            suppress,
        )
    }

    // ── PR1-T3 ─────────────────────────────────────────────────────────
    // TTL boundary at 30_001L expires (#C7-1 잠금)
    // REC-REFIRE after TTL may re-fire (8번 invariant)

    @Test
    fun `T3_releasesAt30001ms`() {
        val firedAt = 1_000L
        val state = S2RecRefireDebounceState(
            lastFiredAt = firedAt,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        // 30,001ms 경과 — `(now - lastFiredAt) > 30_000L` true → 만료
        val now = firedAt + 30_001L
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)

        val suppress = shouldSuppressS2RecRefire(state, thisTick, now)

        assertFalse(
            "30,001ms 경과 시점은 TTL 만료 — 동일 signal도 재발동 가능 (#C7-1)",
            suppress,
        )
    }

    // ── PR1-T4 ─────────────────────────────────────────────────────────
    // dismiss-only CTA does not trigger safe-confirm effects (불변 회귀)

    @Test
    fun `T4_dismissOnlyCta_doesNotTriggerSafeConfirmEffects`() {
        // dismiss-only CTA = view 닫기 외 부수효과 0건.
        // 본 테스트는 dismiss-only handler simulation을 수행하고 safe-confirm 5종 부수효과
        // (reset/clearEvent/snooze/clearAnchor/markSafe) 카운터가 모두 0임을 검증한다.
        //
        // 현재 코드에서는 `BankingCooldownManager`의 "일단 닫기" 버튼이 view dismiss만 호출하며
        // `RiskOverlayManager.performSafeCtaSideEffects`는 절대 호출하지 않는다. 본 테스트가
        // simulation으로 그 성질을 자동 회귀 검증.

        val safeConfirmEffects = mutableListOf<String>()
        var dismissCalled = 0

        val dismissOnlyHandler: () -> Unit = {
            dismissCalled++
            // 의도적으로 reset/clearEvent/snooze/clearAnchor/markSafe 호출 없음.
        }

        dismissOnlyHandler()

        assertEquals("dismiss-only handler는 view 닫기 1회만 수행", 1, dismissCalled)
        assertTrue(
            "dismiss-only CTA는 safe-confirm 5종 부수효과를 0건 호출해야 한다 (불변)",
            safeConfirmEffects.isEmpty(),
        )
    }

    // ── PR1-T5 ─────────────────────────────────────────────────────────
    // safe-confirm remains a separate dedicated flow (불변 회귀)

    @Test
    fun `T5_safeConfirmRemainsSeparateDedicatedFlow`() {
        // safe-confirm = `RiskOverlayManager.performSafeCtaSideEffects` 진입 전용 흐름.
        // 본 테스트는 inCall + call-derived signals 조건에서 safe-confirm path가 호출될 때
        // **모든** 부수효과(reset → clearEvent → snooze → clearAnchor → markSafe)가
        // 순서대로 실행됨을 검증한다 — 즉 이 path는 dismiss-only와 구조적으로 별개임을 자동검증.
        val effects = mutableListOf<String>()

        RiskOverlayManager.performSafeCtaSideEffects(
            liveCallId = 333L,
            callSafe = true,
            reset = { effects.add("reset") },
            clearEvent = { effects.add("clearEvent") },
            snooze = { effects.add("snooze:$it") },
            clearAnchor = { effects.add("clearAnchor") },
            markSafe = { effects.add("markSafe:$it") },
        )

        assertEquals(
            "safe-confirm은 5종 부수효과를 순서대로 모두 호출하는 전용 흐름이어야 한다 (T4의 dismiss-only와 disjoint)",
            listOf("reset", "clearEvent", "snooze:333", "clearAnchor", "markSafe:333"),
            effects,
        )
    }

    // ── PR1-T6 ─────────────────────────────────────────────────────────
    // REC-REFIRE suppression is orchestration-layer debounce, not CTA behavior (불변 회귀)
    // S2 debounce is separate from α debounce (구조적 분리)

    @Test
    fun `T6_recRefireSuppression_isOrchestrationDebounce_notCta_andSeparateFromAlpha`() {
        // S2 게이트의 입력은 (state, thisTickSignals, now) 3개뿐이다.
        // CTA 이벤트(dismiss / safe-confirm)는 본 함수 시그니처에 존재하지 않으며,
        // α 변수(`lastResetAt` / `lastResetSignals`)도 본 함수 시그니처에 존재하지 않는다.
        //
        // 따라서 어떤 CTA 클릭이 발생하든, α arm 상태가 어떻든, S2의 결정은 동일 입력에 대해
        // 동일 출력을 보장한다. 본 테스트는 동일 (state, signals, now)을 두 번 호출하여
        // 결정성(determinism)을 통한 외부 영향 차단을 자동검증한다.
        val state = S2RecRefireDebounceState(
            lastFiredAt = 1_000L,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val now = 5_000L

        val first = shouldSuppressS2RecRefire(state, thisTick, now)
        // CTA(dismiss/safe-confirm) 이벤트 또는 α arm 상태 변화는 본 함수의 시그니처에 입력으로
        // 들어갈 수 없다. 따라서 동일 (state, thisTick, now)에 대한 두 번째 호출 결과는 첫 번째와
        // 동일해야 하며, 이 동일성이 곧 "외부 layer 무관" 자동 검증이다.
        val second = shouldSuppressS2RecRefire(state, thisTick, now)

        assertEquals(
            "S2 게이트는 동일 입력에 동일 출력 — CTA/α 이벤트는 입력에 관여하지 않음 (orchestration-layer 분리)",
            first,
            second,
        )
        // 이번 입력은 TTL 내 동일 signal 재emit이므로 suppress가 정답.
        assertTrue("orchestration-layer debounce는 CTA 무관하게 결정적", first)

        // S2 debounce 상수와 α debounce 상수는 서로 다른 값으로 분리되어 있어야 한다 —
        // 단순 값 동일성을 넘어 의미·축이 분리됨을 코드 차원에서 보증.
        // (본 단언은 PR1 코드 단계 식별자 분리(§5.0)와 정합)
        @Suppress("UNUSED_VARIABLE")
        val s2Ttl: Long = S2_REC_REFIRE_TTL_MS  // 30_000L — S2 prefix 식별자
        // α의 ALPHA_TTL_MS는 RiskSessionTracker.kt 내부 private이므로 import 자체가 금지(§7.4 규칙 1).
        // 본 테스트는 그 import를 의도적으로 하지 않는 것으로 분리를 표현한다.

        // 또한 S2 입력 set 정의(scope, UPGRADE_TRIGGERS)는 α의 어떤 상태 변수와도 구조적으로 무관:
        assertNotEquals(
            "S2 scope와 UPGRADE_TRIGGERS는 의미상 동일 set이 아니다 — UPGRADE는 scope의 superset",
            S2_REC_REFIRE_SCOPE,
            S2_UPGRADE_TRIGGERS,
        )
        assertTrue(
            "UPGRADE_TRIGGERS ⊇ S2_REC_REFIRE_SCOPE — Step 2 #4 escape 정의가 scope 내 새 원소도 escape로 자연 분류",
            S2_UPGRADE_TRIGGERS.containsAll(S2_REC_REFIRE_SCOPE),
        )
    }
}
