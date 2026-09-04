package com.example.seniorshield.domain.model

data class Guardian(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val relationship: String = "",
) {
    companion object {
        const val MAX_COUNT = 3
    }
}
