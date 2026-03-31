package com.example.seniorshield.domain.model

data class PermissionStatus(
    val name: String,
    val description: String,
    val granted: Boolean,
)