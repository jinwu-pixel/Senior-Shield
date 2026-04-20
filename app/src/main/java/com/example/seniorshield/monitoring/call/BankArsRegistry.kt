package com.example.seniorshield.monitoring.call

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 주요 은행·금융기관 ARS 번호 레지스트리.
 * 번호 비교 시 하이픈, 공백, 괄호를 제거한 후 exact match한다.
 */
@Singleton
class BankArsRegistry @Inject constructor() {

    private val arsNumbers: Set<String> = setOf(
        // ── 시중·특수은행 ──────────────────────────────────────────
        // KB국민은행
        "1588-9999", "1599-9999", "1644-9999",
        // 신한은행
        "1577-8000", "1599-8000", "1544-8000",
        // 우리은행
        "1588-5000", "1599-5000", "1533-5000",
        // 하나은행
        "1588-1111", "1599-1111",
        // NH농협은행 (중앙회)
        "1588-2100", "1661-3000", "1522-3000",
        // 지역농축협
        "1661-2100", "1522-2100",
        // IBK기업은행
        "1588-2588", "1566-2566",
        // KDB산업은행
        "1588-1500", "1668-1500",
        // SC제일은행
        "1588-1599",
        // 한국씨티은행
        "1588-7000",
        // Sh수협은행
        "1588-1515", "1644-1515",

        // ── 인터넷전문은행 ─────────────────────────────────────────
        // 카카오뱅크
        "1599-3333",
        // 토스뱅크
        "1661-7654",
        // 케이뱅크
        "1522-1000",

        // ── 지방은행 ──────────────────────────────────────────────
        // iM뱅크 (구 대구은행)
        "1566-5050", "1588-5050",
        // BNK부산은행
        "1588-6200", "1544-6200",
        // BNK경남은행
        "1600-8585", "1588-8585",
        // 광주은행
        "1588-3388", "1600-4000",
        // 전북은행
        "1588-4477",
        // 제주은행
        "1588-0079",

        // ── 상호금융·우체국 ────────────────────────────────────────
        // 새마을금고
        "1588-8801", "1599-9000",
        // 신협
        "1566-6000", "1644-6000",
        // 우체국예금
        "1599-1900", "1588-1900",
    ).map { normalize(it) }.toSet()

    /** 전화번호가 은행 ARS 번호와 일치하면 true. */
    fun matches(phoneNumber: String): Boolean {
        val normalized = normalize(phoneNumber)
        return normalized in arsNumbers
    }

    /** 비숫자 문자 제거 + 한국 국가코드(+82) 처리. */
    private fun normalize(number: String): String {
        val digitsOnly = number.replace(Regex("[^0-9]"), "")
        // +82 국가코드 제거: 82XXXXXXXX → XXXXXXXX (은행 ARS 번호는 8자리)
        return if (digitsOnly.startsWith("82") && digitsOnly.length > 8) {
            digitsOnly.removePrefix("82")
        } else {
            digitsOnly
        }
    }
}
