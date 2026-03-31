package com.example.seniorshield.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.seniorshield.domain.model.RiskEvent
import com.example.seniorshield.domain.repository.RiskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HistoryViewModel @Inject constructor(
    riskRepository: RiskRepository,
) : ViewModel() {

    val events: StateFlow<List<RiskEvent>> = riskRepository.getRecentRiskEvents()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}