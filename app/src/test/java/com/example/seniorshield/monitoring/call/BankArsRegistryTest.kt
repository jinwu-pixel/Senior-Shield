package com.example.seniorshield.monitoring.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankArsRegistryTest {

    private val registry = BankArsRegistry()

    // ── 정확한 번호 매칭 ─────────────────────────────────────────────

    @Test
    fun `KB국민은행 번호 매칭`() {
        assertTrue(registry.matches("15889999"))
        assertTrue(registry.matches("15999999"))
    }

    @Test
    fun `신한은행 번호 매칭`() {
        assertTrue(registry.matches("15778000"))
        assertTrue(registry.matches("15998000"))
    }

    @Test
    fun `우리은행 번호 매칭`() {
        assertTrue(registry.matches("15885000"))
    }

    @Test
    fun `하나은행 번호 매칭`() {
        assertTrue(registry.matches("15881111"))
    }

    @Test
    fun `NH농협은행 번호 매칭`() {
        assertTrue(registry.matches("15882100"))
        assertTrue(registry.matches("16613000"))
    }

    @Test
    fun `IBK기업은행 번호 매칭`() {
        assertTrue(registry.matches("15882588"))
    }

    @Test
    fun `카카오뱅크 번호 매칭`() {
        assertTrue(registry.matches("15993333"))
    }

    @Test
    fun `토스뱅크 번호 매칭`() {
        assertTrue(registry.matches("16617654"))
    }

    @Test
    fun `SC제일은행 번호 매칭`() {
        assertTrue(registry.matches("15881599"))
    }

    @Test
    fun `케이뱅크 번호 매칭`() {
        assertTrue(registry.matches("15221000"))
    }

    @Test
    fun `수협은행 번호 매칭`() {
        assertTrue(registry.matches("15881515"))
    }

    // ── 번호 정규화 ─────────────────────────────────────────────────

    @Test
    fun `하이픈 포함 번호 매칭`() {
        assertTrue(registry.matches("1588-9999"))
    }

    @Test
    fun `공백 포함 번호 매칭`() {
        assertTrue(registry.matches("1588 9999"))
    }

    @Test
    fun `괄호 포함 번호 매칭`() {
        assertTrue(registry.matches("(1588)9999"))
    }

    @Test
    fun `하이픈과 공백 혼합 번호 매칭`() {
        assertTrue(registry.matches("1588 - 9999"))
    }

    // ── 비매칭 ──────────────────────────────────────────────────────

    @Test
    fun `등록되지 않은 번호 비매칭`() {
        assertFalse(registry.matches("01012345678"))
    }

    @Test
    fun `유사하지만 다른 번호 비매칭`() {
        assertFalse(registry.matches("15889998"))
    }

    @Test
    fun `빈 문자열 비매칭`() {
        assertFalse(registry.matches(""))
    }

    // ── 국가코드 정규화 ─────────────────────────────────────────────

    @Test
    fun `+82 국가코드 포함 번호 매칭`() {
        assertTrue(registry.matches("+8215882100"))
    }

    @Test
    fun `+82 하이픈 포함 번호 매칭`() {
        assertTrue(registry.matches("+82-1588-2100"))
    }

    @Test
    fun `82 국가코드 숫자만 매칭`() {
        assertTrue(registry.matches("8215882100"))
    }

    @Test
    fun `82로 시작하는 짧은 번호는 국가코드 아님`() {
        // "8215881515" 길이=10 → 국가코드 제거 대상 (길이>10이 아님)
        // 하지만 이 번호 자체가 등록되지 않았으므로 false
        assertFalse(registry.matches("8200"))
    }
}
