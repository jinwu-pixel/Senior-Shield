package com.example.seniorshield.feature.guardian

import com.example.seniorshield.domain.model.Guardian

data class GuardianUiState(
    val guardians: List<Guardian> = emptyList(),
    val canAddMore: Boolean = true,
    val showAddDialog: Boolean = false,
    val message: String? = null,
    val smsMenuEnabled: Boolean = false,
    val showSmsPicker: Boolean = false,
)
