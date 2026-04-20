package com.example.seniorshield.domain.model

enum class PermissionType {
    PHONE_STATE,
    USAGE_ACCESS,
    NOTIFICATION,
    OVERLAY,
    ANSWER_CALLS,
    READ_CONTACTS,
    READ_CALL_LOG,
    OUTGOING_CALLS,
}

data class PermissionStatus(
    val type: PermissionType,
    val name: String,
    val description: String,
    val granted: Boolean,
)