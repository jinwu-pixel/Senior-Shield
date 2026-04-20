package com.example.seniorshield.monitoring.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankArsRegistryTest {

    private val registry = BankArsRegistry()

    // ── 시중·특수은행 ──────────────────────────────────────────────

    @Test
    fun `KB국민은행 번호 매칭`() {
        assertTrue(registry.matches("15889999"))
        assertTrue(registry.matches("15999999"))
        assertTrue(registry.matches("16449999"))
    }

    @Test
    fun `신한은행 번호 매칭`() {
        assertTrue(registry.matches("15778000"))
        assertTrue(registry.matches("15998000"))
        assertTrue(registry.matches("15448000"))
    }

    @Test
    fun `우리은행 번호 매칭`() {
        assertTrue(registry.matches("15885000"))
        assertTrue(registry.matches("15995000"))
        assertTrue(registry.matches("15335000"))
    }

    @Test
    fun `하나은행 번호 매칭`() {
        assertTrue(registry.matches("15881111"))
        assertTrue(registry.matches("15991111"))
    }

    @Test
    fun `NH농협은행 번호 매칭`() {
        assertTrue(registry.matches("15882100"))
        assertTrue(registry.matches("16613000"))
        assertTrue(registry.matches("15223000"))
    }

    @Test
    fun `지역농축협 번호 매칭`() {
        assertTrue(registry.matches("16612100"))
        assertTrue(registry.matches("15222100"))
        assertTrue(registry.matches("1661-2100"))
    }

    @Test
    fun `IBK기업은행 번호 매칭`() {
        assertTrue(registry.matches("15882588"))
        assertTrue(registry.matches("15662566"))
    }

    @Test
    fun `KDB산업은행 번호 매칭`() {
        assertTrue(registry.matches("15881500"))
        assertTrue(registry.matches("16681500"))
    }

    @Test
    fun `SC제일은행 번호 매칭`() {
        assertTrue(registry.matches("15881599"))
    }

    @Test
    fun `한국씨티은행 번호 매칭`() {
        assertTrue(registry.matches("15887000"))
    }

    @Test
    fun `수협은행 번호 매칭`() {
        assertTrue(registry.matches("15881515"))
        assertTrue(registry.matches("16441515"))
    }

    // ── 인터넷전문은행 ─────────────────────────────────────────────

    @Test
    fun `카카오뱅크 번호 매칭`() {
        assertTrue(registry.matches("15993333"))
    }

    @Test
    fun `토스뱅크 번호 매칭`() {
        assertTrue(registry.matches("16617654"))
    }

    @Test
    fun `케이뱅크 번호 매칭`() {
        assertTrue(registry.matches("15221000"))
    }

    // ── 지방은행 ──────────────────────────────────────────────────

    @Test
    fun `iM뱅크 대구 번호 매칭`() {
        assertTrue(registry.matches("15665050"))
        assertTrue(registry.matches("15885050"))
    }

    @Test
    fun `BNK부산은행 번호 매칭`() {
        assertTrue(registry.matches("15886200"))
        assertTrue(registry.matches("15446200"))
    }

    @Test
    fun `BNK경남은행 번호 매칭`() {
        assertTrue(registry.matches("16008585"))
        assertTrue(registry.matches("15888585"))
    }

    @Test
    fun `광주은행 번호 매칭`() {
        assertTrue(registry.matches("15883388"))
        assertTrue(registry.matches("16004000"))
    }

    @Test
    fun `전북은행 번호 매칭`() {
        assertTrue(registry.matches("15884477"))
    }

    @Test
    fun `제주은행 번호 매칭`() {
        assertTrue(registry.matches("15880079"))
    }

    // ── 상호금융·우체국 ────────────────────────────────────────────

    @Test
    fun `새마을금고 번호 매칭`() {
        assertTrue(registry.matches("15888801"))
        assertTrue(registry.matches("15999000"))
    }

    @Test
    fun `신협 번호 매칭`() {
        assertTrue(registry.matches("15666000"))
        assertTrue(registry.matches("16446000"))
    }

    @Test
    fun `우체국예금 번호 매칭`() {
        assertTrue(registry.matches("15991900"))
        assertTrue(registry.matches("15881900"))
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

    // ── 커버리지 보완 ─────────────────────────────────────────────────

    @Test
    fun `length 8 초과 82 시작 번호 국가코드 제거 후 매칭`() {
        // "8215889999": 10자리, 82로 시작, length(10) > 8 → "82" 제거 → "15889999" → 매칭
        assertTrue(registry.matches("8215889999"))
    }

    @Test
    fun `82 시작이지만 제거 후 8자리 이하 - 국가코드 제거 안 함`() {
        // "82123456": 8자리, startsWith("82") && length(8) > 8 → false → 제거 안 함
        // "82123456" 그대로 → 등록 번호 없음 → false
        assertFalse(registry.matches("82123456"))
    }

    @Test
    fun `짧은 숫자 입력 - false`() {
        // "1234": 4자리 → 등록 번호 없음
        assertFalse(registry.matches("1234"))
    }

    @Test
    fun `전체 공백 입력 - false`() {
        // "   ": 비숫자만 → normalize 후 빈 문자열 → 등록 번호 없음
        assertFalse(registry.matches("   "))
    }

    @Test
    fun `비숫자 문자만 입력 - false`() {
        // "abc-def": 비숫자 제거 후 빈 문자열 → 등록 번호 없음
        assertFalse(registry.matches("abc-def"))
    }

    @Test
    fun `하이픈 포함 토스뱅크 번호 매칭`() {
        // "1661-7654": 하이픈 제거 후 "16617654" → 토스뱅크 등록 번호
        assertTrue(registry.matches("1661-7654"))
    }
}
