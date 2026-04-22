package com.example.seniorshield.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BankingCooldownManager.secondaryCtaLabel] 순수 함수 검증.
 *
 * 실제 보조 버튼의 렌더/클릭/dismiss 경로는 Android View + WindowManager 의존이라
 * 단위 테스트에서 직접 검증할 수 없다 → 실기 Check A/B(`02_patch_plan.md §7`)로 커버한다.
 * 여기서는 5초 가드 동안 라벨이 카운트다운과 함께 갱신되고, 활성화 시점에 평문으로 바뀌는
 * 로직만 분리해 검증한다.
 */
class BankingCooldownManagerLabelTest {

    @Test
    fun `label_whenActivated_returnsPlainLabel`() {
        val label = BankingCooldownManager.secondaryCtaLabel(
            activated = true,
            remainingGuardSec = 0,
        )
        assertEquals("위험 경고 해제", label)
    }

    @Test
    fun `label_whenNotActivated_includesCountdown`() {
        assertEquals(
            "위험 경고 해제 (5초 후 가능)",
            BankingCooldownManager.secondaryCtaLabel(activated = false, remainingGuardSec = 5),
        )
        assertEquals(
            "위험 경고 해제 (3초 후 가능)",
            BankingCooldownManager.secondaryCtaLabel(activated = false, remainingGuardSec = 3),
        )
        assertEquals(
            "위험 경고 해제 (1초 후 가능)",
            BankingCooldownManager.secondaryCtaLabel(activated = false, remainingGuardSec = 1),
        )
    }

    /**
     * 보조 CTA 클릭 시 3단계가 cancel → arm → dismiss 순서로, 각 1회씩 호출되는지 검증.
     *
     * 실제 클릭 핸들러는 이 pure function을 경유하므로 순서가 여기서 보장되면
     * `sessionTracker.resetAfterUserConfirmedSafe()`가 `dismiss()` 전에 1회 호출된다.
     * (click handler의 람다 매핑은 `BankingCooldownManager.kt:buildView()` 참조)
     */
    @Test
    fun `clickSequence_invokesCancelThenArmThenDismiss_eachOnce`() {
        val calls = mutableListOf<String>()
        BankingCooldownManager.secondaryCtaClickSequence(
            cancelJob = { calls.add("cancel") },
            armAlpha = { calls.add("arm") },
            removeOverlay = { calls.add("dismiss") },
        )
        assertEquals(listOf("cancel", "arm", "dismiss"), calls)
    }
}
