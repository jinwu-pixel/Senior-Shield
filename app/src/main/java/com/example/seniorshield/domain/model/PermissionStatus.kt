package com.example.seniorshield.domain.model

enum class PermissionType {
    PHONE_STATE,
    USAGE_ACCESS,
    NOTIFICATION,
    OVERLAY,
    ANSWER_CALLS,
    READ_CONTACTS,
    READ_CALL_LOG,
}

data class PermissionStatus(
    val type: PermissionType,
    val name: String,
    val description: String,
    val granted: Boolean,
)