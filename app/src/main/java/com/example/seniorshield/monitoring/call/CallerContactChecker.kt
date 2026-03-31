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
 * 발신자 번호를 연락처와 대조하여 미확인 여부를 반환한다.
 *
 * ## 판정 기준
 * - 연락처에 없음 → **미확인** (true)
 * - 연락처에 있으나 [NEW_CONTACT_THRESHOLD_MS] 이내 저장/수정됨 → **미확인** (true)
 *   보이스피싱범이 "나중에 연락할 테니 저장해두세요"라고 유도한 뒤
 *   다음 날 전화하는 패턴을 차단한다.
 * - 연락처에 있고 오래된 번호 → **확인됨** (false)
 * - READ_CONTACTS 권한 없음 또는 번호 없음 → **판단 불가** (null)
 *
 * @return true=미확인, false=확인됨, null=판단 불가
 */
@Singleton
class CallerContactChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isUnknownNumber(phoneNumber: String?): Boolean? {
        if (phoneNumber.isNullOrBlank()) return null
        if (!hasContactsPermission()) {
            Log.w(TAG, "READ_CONTACTS 권한 없음 — 연락처 조회 불가")
            return null
        }
        return try {
            queryWithTimestamp(phoneNumber)
        } catch (e: Exception) {
            // CONTACT_LAST_UPDATED_TIMESTAMP 미지원 단말(일부 제조사 커스텀 ROM)에서 발생
            Log.w(TAG, "타임스탬프 조회 실패: ${e.message} — 단순 존재 여부로 폴백")
            try {
                queryExistenceOnly(phoneNumber)
            } catch (e2: Exception) {
                Log.w(TAG, "폴백 조회도 실패: ${e2.message}")
                null
            }
        }
    }

    /** 연락처 존재 여부 + 신규 저장 여부를 함께 확인한다 (표준 경로). */
    private fun queryWithTimestamp(phoneNumber: String): Boolean? {
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
                Log.d(TAG, "미저장 번호 — 미확인")
                true
            } else {
                cursor.moveToFirst()
                val colIdx = cursor.getColumnIndex(
                    ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
                )
                val updatedAt = if (colIdx >= 0) cursor.getLong(colIdx) else 0L
                val isNew = updatedAt > System.currentTimeMillis() - NEW_CONTACT_THRESHOLD_MS
                if (isNew) {
                    val daysAgo = (System.currentTimeMillis() - updatedAt) / DAY_MS
                    Log.d(TAG, "신규 저장 연락처 (${daysAgo}일 전) — 미확인 처리")
                } else {
                    Log.d(TAG, "기존 연락처 — 확인됨")
                }
                isNew
            }
        } ?: true
    }

    /**
     * CONTACT_LAST_UPDATED_TIMESTAMP 미지원 단말 폴백.
     * 연락처 존재 여부만 확인한다.
     * 저장된 번호이면 신뢰(false), 미저장이면 미확인(true).
     */
    private fun queryExistenceOnly(phoneNumber: String): Boolean? {
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
                Log.d(TAG, "미저장 번호 (폴백) — 미확인")
                true
            } else {
                Log.d(TAG, "저장된 번호 (폴백, 신규 여부 미판별) — 확인됨 처리")
                false
            }
        } ?: true
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
