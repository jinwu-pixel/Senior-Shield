package com.example.seniorshield.feature.simulation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class SimulationUiState(
    val scenario: FraudScenario,
    val currentStepIndex: Int = 0,
    val selectedChoiceIndex: Int? = null,
    val correctCount: Int = 0,
    val isCompleted: Boolean = false,
)

@HiltViewModel
class SimulationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scenarioId: String = savedStateHandle["scenarioId"] ?: FRAUD_SCENARIOS.first().id
    private val scenario = FRAUD_SCENARIOS.first { it.id == scenarioId }

    private val _uiState = MutableStateFlow(SimulationUiState(scenario = scenario))
    val uiState: StateFlow<SimulationUiState> = _uiState.asStateFlow()

    fun selectChoice(choiceIndex: Int) {
        val state = _uiState.value
        if (state.selectedChoiceIndex != null) return // 이미 선택됨
        val choice = state.scenario.steps[state.currentStepIndex].choices[choiceIndex]
        _uiState.value = state.copy(
            selectedChoiceIndex = choiceIndex,
            correctCount = if (choice.isCorrect) state.correctCount + 1 else state.correctCount,
        )
    }

    fun nextStep() {
        val state = _uiState.value
        val nextIndex = state.currentStepIndex + 1
        if (nextIndex >= state.scenario.steps.size) {
            _uiState.value = state.copy(isCompleted = true)
        } else {
            _uiState.value = state.copy(
                currentStepIndex = nextIndex,
                selectedChoiceIndex = null,
            )
        }
    }
}
