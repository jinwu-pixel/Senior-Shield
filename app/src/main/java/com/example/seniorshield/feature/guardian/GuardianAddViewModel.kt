package com.example.seniorshield.feature.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.repository.GuardianRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class GuardianAddUiState(
    val name: String = "",
    val phone: String = "",
    val relationship: String = "",
    val error: String? = null,
    val canSave: Boolean = false,
)

@HiltViewModel
class GuardianAddViewModel @Inject constructor(
    private val guardianRepository: GuardianRepository,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    private val _phone = MutableStateFlow("")
    private val _relationship = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    private val _savedEvent = Channel<Unit>(Channel.BUFFERED)
    val savedEvent = _savedEvent.receiveAsFlow()

    val uiState: StateFlow<GuardianAddUiState> = combine(
        _name, _phone, _relationship, _error,
    ) { name, phone, relationship, error ->
        GuardianAddUiState(
            name = name,
            phone = phone,
            relationship = relationship,
            error = error,
            canSave = name.isNotBlank() && phone.isNotBlank(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GuardianAddUiState(),
    )

    fun updateName(value: String) { _name.value = value }
    fun updatePhone(value: String) { _phone.value = value }
    fun updateRelationship(value: String) { _relationship.value = value }

    fun save() {
        viewModelScope.launch {
            val name = _name.value.trim()
            val normalized = _phone.value.replace("-", "").replace(" ", "").trim()
            val relationship = _relationship.value.trim()

            val validationError = validatePhone(normalized)
            if (validationError != null) {
                _error.value = validationError
                return@launch
            }

            val duplicate = guardianRepository.getGuardians().any { it.phoneNumber == normalized }
            if (duplicate) {
                _error.value = "이미 등록된 번호입니다."
                return@launch
            }

            val guardian = Guardian(
                id = UUID.randomUUID().toString(),
                name = name,
                phoneNumber = normalized,
                relationship = relationship,
            )
            val success = guardianRepository.addGuardian(guardian)
            if (success) {
                _savedEvent.send(Unit)
            } else {
                _error.value = "최대 ${Guardian.MAX_COUNT}명까지 등록할 수 있습니다."
            }
        }
    }

    private fun validatePhone(normalized: String): String? {
        if (normalized.isEmpty()) return "전화번호를 입력해주세요."
        if (!normalized.all { it.isDigit() }) return "전화번호는 숫자만 입력해주세요."
        if (normalized.length !in 10..11) return "전화번호 자릿수를 확인해주세요."
        return null
    }
}
