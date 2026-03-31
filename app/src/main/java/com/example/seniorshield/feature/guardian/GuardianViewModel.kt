package com.example.seniorshield.feature.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.repository.GuardianRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GuardianViewModel @Inject constructor(
    private val guardianRepository: GuardianRepository,
) : ViewModel() {

    private val _showAddDialog = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GuardianUiState> = combine(
        guardianRepository.observeGuardians(),
        _showAddDialog,
        _message,
    ) { guardians, showDialog, msg ->
        GuardianUiState(
            guardians = guardians,
            canAddMore = guardians.size < Guardian.MAX_COUNT,
            showAddDialog = showDialog,
            message = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GuardianUiState(),
    )

    fun showAddDialog() { _showAddDialog.value = true }
    fun hideAddDialog() { _showAddDialog.value = false }
    fun clearMessage() { _message.value = null }

    fun addGuardian(name: String, phone: String, relationship: String) {
        viewModelScope.launch {
            val normalized = phone.replace("-", "").replace(" ", "").trim()

            val validationError = validatePhone(normalized)
            if (validationError != null) {
                _message.value = validationError
                return@launch
            }

            val duplicate = guardianRepository.getGuardians().any { it.phoneNumber == normalized }
            if (duplicate) {
                _message.value = "이미 등록된 번호입니다."
                return@launch
            }

            val guardian = Guardian(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                phoneNumber = normalized,
                relationship = relationship.trim(),
            )
            val success = guardianRepository.addGuardian(guardian)
            _showAddDialog.value = false
            _message.value = if (success) "${name.trim()}님이 등록되었습니다."
                             else "최대 ${Guardian.MAX_COUNT}명까지 등록할 수 있습니다."
        }
    }

    private fun validatePhone(normalized: String): String? {
        if (normalized.isEmpty()) return "전화번호를 입력해주세요."
        if (!normalized.all { it.isDigit() }) return "전화번호는 숫자만 입력해주세요."
        if (normalized.length !in 10..11) return "전화번호 자릿수를 확인해주세요."
        return null
    }

    fun removeGuardian(id: String) {
        viewModelScope.launch {
            guardianRepository.removeGuardian(id)
            _message.value = "보호자가 삭제되었습니다."
        }
    }
}
