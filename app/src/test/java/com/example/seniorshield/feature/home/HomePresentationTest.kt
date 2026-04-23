package com.example.seniorshield.feature.home

import com.example.seniorshield.domain.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HomeStatus 전이 고정 테스트.
 * decideHomePresentation은 HomeViewModel combine 내부의 UI 결정 로직을 순수 함수로 추출한 것.
 *
 * 전이표:
 *   currentEvent  anchorHot  → status
 *   null          false      → SAFE
 *   null          true       → GUARDED_ANCHOR  ("주의 — 확인 필요")
 *   HIGH/CRITICAL *          → WARNING ("위험 감지", level pass-through)
 */
class HomePresentationTest {

    // ── 기본 3분기 ─────────────────────────────────────────────────────

    @Test
    fun `SAFE — currentEvent null, anchorHot false`() {
        val p = decideHomePresentation(null, null, anchorHot = false)
        assertEquals(HomeStatus.SAFE, p.status)
        assertEquals("현재 보호 상태", p.title)
        assertEquals("안전합니다. 감지된 위험이 없습니다.", p.baseBody)
        assertEquals(RiskLevel.LOW, p.level)
    }

    @Test
    fun `GUARDED_ANCHOR — currentEvent null, anchorHot true`() {
        val p = decideHomePresentation(null, null, anchorHot = true)
        assertEquals(HomeStatus.GUARDED_ANCHOR, p.status)
        assertEquals("주의 — 확인 필요", p.title)
        assertEquals("최근 의심 통화 맥락이 남아 있습니다", p.baseBody)
        // StatusCard 노란색 축(MEDIUM) — 팝업 CRITICAL 축과 분리.
        assertEquals(RiskLevel.MEDIUM, p.level)
    }

    @Test
    fun `WARNING — currentEvent HIGH, anchor 무관`() {
        val p = decideHomePresentation("이벤트 제목", RiskLevel.HIGH, anchorHot = false)
        assertEquals(HomeStatus.WARNING, p.status)
        assertEquals("위험 감지", p.title)
        assertEquals("이벤트 제목", p.baseBody)
        assertEquals(RiskLevel.HIGH, p.level)
    }

    @Test
    fun `WARNING — currentEvent CRITICAL 우선 (anchorHot true여도 WARNING)`() {
        val p = decideHomePresentation("텔레뱅킹 발신 감지", RiskLevel.CRITICAL, anchorHot = true)
        assertEquals(HomeStatus.WARNING, p.status)
        assertEquals("위험 감지", p.title)
        assertEquals("텔레뱅킹 발신 감지", p.baseBody)
        assertEquals(RiskLevel.CRITICAL, p.level)
    }

    // ── 전이 경계 검증 ─────────────────────────────────────────────────

    @Test
    fun `WARNING 우선순위 — currentEvent 있으면 anchorHot 값 무관`() {
        val withAnchor = decideHomePresentation("x", RiskLevel.HIGH, anchorHot = true)
        val withoutAnchor = decideHomePresentation("x", RiskLevel.HIGH, anchorHot = false)
        assertEquals(HomeStatus.WARNING, withAnchor.status)
        assertEquals(HomeStatus.WARNING, withoutAnchor.status)
        assertEquals(withAnchor.level, withoutAnchor.level)
    }

    @Test
    fun `anchorHot false → SAFE (TTL 만료 후 자연 복귀 시나리오)`() {
        // P2 시나리오: GUARDED_ANCHOR에서 5분 TTL 지나 anchorHot=false가 mirror되면 SAFE로 복귀.
        val p = decideHomePresentation(null, null, anchorHot = false)
        assertEquals(HomeStatus.SAFE, p.status)
    }
}
