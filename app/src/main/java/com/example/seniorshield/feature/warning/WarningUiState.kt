package com.example.seniorshield.feature.warning

import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskLevel

data class WarningUiState(
    val guardians: List<Guardian> = emptyList(),
    val showGuardianPicker: Boolean = false,
    val message: String? = null,
    val detectedEventTitle: String? = null,
    val detectedEventDescription: String? = null,
    val detectedEventLevel: RiskLevel? = null,
    val smsMenuEnabled: Boolean = false,
    val showSmsPicker: Boolean = false,
)
