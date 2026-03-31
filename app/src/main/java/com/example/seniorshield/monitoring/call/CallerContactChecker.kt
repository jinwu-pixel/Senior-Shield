package com.example.seniorshield.monitoring.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SeniorShield-ContactChecker"

/**
 * 발신자 번호에 대한 연락처 조회 결과.
 *
 * - NOT_IN_CONTACTS  : 연락처에 없는 미저장 번호
 * - NEW_CONTACT      : 연락처에 있으나 [NEW_CONTACT_THRESHOLD_MS] 이내 저장/수정된 신규 연락처
 * - VERIFIED_CONTACT : 연락처에 있고 임계값보다 오래된 기존 연락처
 * - UNAVAILABLE      : 권한 없음·번호 없음 등 판단 불가
 */
enum class CallerCheckResult {
    NOT_IN_CONTACTS,
    NEW_CONTACT,
    VERIFIED_CONTACT,
    UNAVAILABLE,
}

/**
 * 발신자 번호를 연락처와 대조하여 [CallerCheckResult]를 반환한다.
 *
 * ## 판정 기준
 * - 연락처에 없음 → NOT_IN_CONTACTS
 * - 연락처에 있으나 [NEW_CONTACT_THRESHOLD_MS] 이내 저장/수정됨 → NEW_CONTACT
 *   보이스피싱범이 "나중에 연락할 테니 저장해두세요"라고 유도한 뒤
 *   다음 날 전화하는 패턴을 차단한다.
 * - 연락처에 있고 오래된 번호 → VERIFIED_CONTACT
 * - READ_CONTACTS 권한 없음 또는 번호 없음 → UNAVAILABLE
 */
@Singleton
class CallerContactChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * 발신자 번호의 연락처 상태를 [CallerCheckResult]로 반환한다.
     * UNKNOWN_CALLER/UNVERIFIED_CALLER 신호를 분리 판정하기 위해 사용한다.
     */
    fun checkCaller(phoneNumber: String?): CallerCheckResult {
        if (phoneNumber.isNullOrBlank()) return CallerCheckResult.UNAVAILABLE
        if (!hasContactsPermission()) {
            Log.w(TAG, "READ_CONTACTS 권한 없음 — 연락처 조회 불가")
            return CallerCheckResult.UNAVAILABLE
        }
        return try {
            queryWithTimestampResult(phoneNumber)
        } catch (e: Exception) {
            // CONTACT_LAST_UPDATED_TIMESTAMP 미지원 단말(일부 제조사 커스텀 ROM)에서 발생
            Log.w(TAG, "타임스탬프 조회 실패: ${e.message} — 단순 존재 여부로 폴백")
            try {
                queryExistenceOnlyResult(phoneNumber)
            } catch (e2: Exception) {
                Log.w(TAG, "폴백 조회도 실패: ${e2.message}")
                CallerCheckResult.UNAVAILABLE
            }
        }
    }

    /** 연락처 존재 여부 + 신규 저장 여부를 함께 확인한다 (표준 경로). */
    private fun queryWithTimestampResult(phoneNumber: String): CallerCheckResult {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        return context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.count == 0) {
                Log.d(TAG, "미저장 번호 — NOT_IN_CONTACTS")
                CallerCheckResult.NOT_IN_CONTACTS
            } else {
                cursor.moveToFirst()
                val colIdx = cursor.getColumnIndex(
                    ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
                )
                val updatedAt = if (colIdx >= 0) cursor.getLong(colIdx) else 0L
                val isNew = updatedAt > System.currentTimeMillis() - NEW_CONTACT_THRESHOLD_MS
                if (isNew) {
                    val daysAgo = (System.currentTimeMillis() - updatedAt) / DAY_MS
                    Log.d(TAG, "신규 저장 연락처 (${daysAgo}일 전) — NEW_CONTACT")
                    CallerCheckResult.NEW_CONTACT
                } else {
                    Log.d(TAG, "기존 연락처 — VERIFIED_CONTACT")
                    CallerCheckResult.VERIFIED_CONTACT
                }
            }
        } ?: CallerCheckResult.NOT_IN_CONTACTS
    }

    /**
     * CONTACT_LAST_UPDATED_TIMESTAMP 미지원 단말 폴백.
     * 연락처 존재 여부만 확인한다.
     * 저장된 번호이면 VERIFIED_CONTACT(타임스탬프 판별 불가이므로 안전 측으로),
     * 미저장이면 NOT_IN_CONTACTS.
     *
     * **알려진 제한**: 이 경로에서는 7일 이내 신규 저장 연락처를 구별할 수 없어
     * NEW_CONTACT 판정이 불가하다. 오탐 방지를 위해 의도적으로 VERIFIED_CONTACT로
     * 처리한다 (B4 정책 결정 2026-03-31).
     */
    private fun queryExistenceOnlyResult(phoneNumber: String): CallerCheckResult {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null,
        )?.use { cursor ->
            if (cursor.count == 0) {
                Log.d(TAG, "미저장 번호 (폴백) — NOT_IN_CONTACTS")
                CallerCheckResult.NOT_IN_CONTACTS
            } else {
                Log.d(TAG, "저장된 번호 (폴백, 신규 여부 미판별) — VERIFIED_CONTACT")
                CallerCheckResult.VERIFIED_CONTACT
            }
        } ?: CallerCheckResult.NOT_IN_CONTACTS
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        /** 이 기간 이내에 저장·수정된 연락처는 미확인으로 간주한다. */
        private const val NEW_CONTACT_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
