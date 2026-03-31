package com.example.seniorshield.monitoring.call

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 주요 은행 ARS 번호 레지스트리.
 * 번호 비교 시 하이픈, 공백, 괄호를 제거한 후 exact match한다.
 *
 * 초기 목록: 국민, 신한, 우리, 하나, 농협, 기업, 카카오뱅크, 토스뱅크
 */
@Singleton
class BankArsRegistry @Inject constructor() {

    private val arsNumbers: Set<String> = setOf(
        // KB국민은행
        "1588-9999", "1599-9999",
        // 신한은행
        "1577-8000", "1599-8000",
        // 우리은행
        "1588-5000", "1599-5000",
        // 하나은행
        "1588-1111", "1599-1111",
        // NH농협은행
        "1588-2100", "1661-3000",
        // IBK기업은행
        "1588-2588", "1566-2566",
        // 카카오뱅크
        "1599-3333",
        // 토스뱅크
        "1661-7654",
    ).map { normalize(it) }.toSet()

    /** 전화번호가 은행 ARS 번호와 일치하면 true. */
    fun matches(phoneNumber: String): Boolean {
        val normalized = normalize(phoneNumber)
        return normalized in arsNumbers
    }

    private fun normalize(number: String): String =
        number.replace(Regex("[\\s\\-()]"), "")
}
