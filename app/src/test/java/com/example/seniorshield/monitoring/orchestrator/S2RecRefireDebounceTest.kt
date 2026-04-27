package com.example.seniorshield.monitoring.orchestrator

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
 *   2. TTL boundary: 30,001ms expires                   → T3
 *   3. dismiss-only CTA is not an S2 gate input         → T4
 *   4. safe-confirm CTA is not an S2 gate input         → T5
 *   5. REC-REFIRE = orchestration debounce, not CTA     → T6
 *   6. S2 debounce is separate from α debounce         → T6
 *   7. same REC-REFIRE within TTL suppress             → T1
 *   8. REC-REFIRE after TTL may re-fire                → T3
 *
 * **격리 원칙 (PR1 §5.0 + §7.4 정적 규칙 + 04_step3_impl_plan.md §5.6, §11.2):**
 * - 본 클래스는 `RiskSessionTracker` 또는 그 내부 변수를 import/read 하지 않는다.
 * - 본 클래스는 CTA handler(`HomeViewModel.confirmSafe`, `WarningViewModel.confirmSafe`,
 *   `RiskOverlayManager`, `BankingCooldownManager` 등)를 **import하거나 호출하지 않는다**.
 * - dismiss-only / safe-confirm production wiring 검증은 `RiskOverlayManagerSafeEffectsTest` 등
 *   별도 CTA-쪽 테스트의 책임이며 본 클래스 범위 외다.
 *
 * **본 클래스의 책임:**
 * `shouldSuppressS2RecRefire`의 pure-function boundary 검증뿐이다 — 즉 **"S2 gate의 입력
 * surface는 (state, thisTickSignals, now) 3종이며 CTA/α는 입력이 아니다"**의 자동 자기증명.
 * T4/T5는 production CTA side effect를 직접 검증하지 않는다.
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
    // dismiss-only CTA is not an S2 gate input (boundary 검증)

    @Test
    fun `T4_dismissOnlyCta_isNotAnS2GateInput`() {
        // `shouldSuppressS2RecRefire`의 시그니처는 (state, thisTickSignals, now) 3종뿐이다.
        // dismiss-only 이벤트(예: BankingCooldownManager "일단 닫기" 버튼, RiskOverlayManager view dismiss 등)는
        // 본 함수의 입력 자리에 존재하지 않는다 — 따라서 dismiss 발생 여부와 관계없이 동일 (state, thisTick, now)
        // 입력은 항상 동일 출력을 만든다. 그 동일성이 곧 "dismiss-only CTA는 S2 gate에 영향 없다"의
        // 자동 자기증명이다.
        //
        // production wiring("BankingCooldownManager 일단 닫기는 view dismiss 외 부수효과 0건")의
        // 직접 검증은 본 클래스 범위 외 — 별도 CTA-쪽 테스트에서 다룬다.
        val state = S2RecRefireDebounceState(
            lastFiredAt = 1_000L,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val now = 5_000L  // TTL 내

        val first = shouldSuppressS2RecRefire(state, thisTick, now)
        // 가상의 dismiss 이벤트가 그 사이에 발생했다고 narrate (실제로는 본 함수의 입력 자리에 dismiss가
        // 들어갈 수 없어 simulation 자체가 불가능 — 그 불가능성이 boundary의 본질).
        val second = shouldSuppressS2RecRefire(state, thisTick, now)

        assertEquals(
            "동일 (state, thisTick, now) 입력은 동일 출력 — dismiss-only 이벤트는 S2 gate 입력 surface 외부",
            first, second,
        )
        assertTrue("TTL 내 동일 scope signal → suppress (이번 케이스의 정답)", first)
    }

    // ── PR1-T5 ─────────────────────────────────────────────────────────
    // safe-confirm CTA is not an S2 gate input (boundary 검증, escape 시나리오)

    @Test
    fun `T5_safeConfirmCta_isNotAnS2GateInput`() {
        // T4와 동일 boundary 원리 — safe-confirm 이벤트(예: 홈 "안전 확인했어요", Warning "안전 확인",
        // 또는 팝업 보조 safe-CTA가 호출하는 부수효과 함수)도 본 함수의 입력 자리에 존재하지 않는다.
        //
        // 본 테스트는 escape 시나리오(snapshot 외 새 UPGRADE trigger 도래)로 boundary를 한 번 더 확인 —
        // escape 결정이 일어나야 하는 상황에서도 safe-confirm 발생 여부는 결정에 관여하지 않는다.
        //
        // safe-confirm production wiring(safe-CTA 부수효과 함수가 reset → clearEvent → snooze →
        // clearAnchor → markSafe 를 순서대로 호출하는 동작)의 직접 검증은 별도 CTA-쪽 테스트의
        // 책임이며 본 클래스 범위 외.
        val state = S2RecRefireDebounceState(
            lastFiredAt = 1_000L,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        val thisTick = setOf(
            RiskSignal.REMOTE_CONTROL_APP_OPENED,
            RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP,  // snapshot 외 새 UPGRADE trigger
        )
        val now = 5_000L  // TTL 내

        val first = shouldSuppressS2RecRefire(state, thisTick, now)
        val second = shouldSuppressS2RecRefire(state, thisTick, now)

        assertEquals(
            "동일 (state, thisTick, now) 입력은 동일 출력 — safe-confirm 이벤트는 S2 gate 입력 surface 외부",
            first, second,
        )
        assertFalse(
            "snapshot 외 새 UPGRADE trigger 도래 → escape (suppress 해제) — safe-confirm 발생 여부와 무관",
            first,
        )
    }

    // ── PR1-T6 ─────────────────────────────────────────────────────────
    // REC-REFIRE suppression is orchestration-layer debounce, not CTA behavior (불변 회귀)
    // S2 debounce is separate from α debounce (구조적 분리)

    @Test
    fun `T6_recRefireSuppression_isOrchestrationDebounce_notCta_andSeparateFromAlpha`() {
        // T4/T5의 boundary를 일반화 — S2 gate의 입력은 (state, thisTickSignals, now) 3종이며
        // CTA / α 어떤 layer의 이벤트도 입력 자리에 존재하지 않는다. 본 테스트는 orchestration-layer
        // 결정성을 한 번 더 확인하고, 더해서 S2 prefix 식별자 분리(§5.0)와 set 정의 관계(scope ⊊ UPGRADE)를
        // 검증한다.
        val state = S2RecRefireDebounceState(
            lastFiredAt = 1_000L,
            snapshot = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED),
        )
        val thisTick = setOf(RiskSignal.REMOTE_CONTROL_APP_OPENED)
        val now = 5_000L

        val first = shouldSuppressS2RecRefire(state, thisTick, now)
        val second = shouldSuppressS2RecRefire(state, thisTick, now)

        assertEquals(
            "S2 gate는 동일 입력에 동일 출력 — CTA / α 이벤트는 입력에 관여하지 않음 (orchestration-layer 분리)",
            first, second,
        )
        assertTrue("orchestration-layer debounce는 CTA 무관하게 결정적", first)

        // S2 prefix 식별자 분리 (§5.0): S2_REC_REFIRE_TTL_MS는 S2 prefix가 강제된 식별자.
        // α의 ALPHA_TTL_MS(`RiskSessionTracker.kt:22`)는 `private`이므로 import 자체가 금지된다(§7.4 규칙 1) —
        // 본 테스트는 그 import를 의도적으로 하지 않음으로써 분리를 표현한다.
        @Suppress("UNUSED_VARIABLE")
        val s2Ttl: Long = S2_REC_REFIRE_TTL_MS  // 30_000L

        // S2 입력 set 정의(scope ⊊ UPGRADE_TRIGGERS) — Step 2 #3 + #4 정의의 직접 결과.
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
