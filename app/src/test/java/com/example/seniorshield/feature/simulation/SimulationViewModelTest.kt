package com.example.seniorshield.feature.simulation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimulationViewModel] 순수 Real-Object 단위 테스트.
 *
 * ## 하네스
 * Hilt·mockk·Robolectric 일절 없음. 실 `SavedStateHandle(mapOf(...))` + 실 `FRAUD_SCENARIOS`
 * 데이터 + 실 VM의 상태 전이만 검증한다. VM에 `viewModelScope`/Flow collect가 없어 `runTest`
 * 불요 — `uiState.value`를 동기적으로 직접 읽어 단언한다.
 *
 * 대상 시나리오: `telebanking`(3단계, 각 단계 정답 선택지 = index 1).
 */
class SimulationViewModelTest {

    private fun telebankingVm() =
        SimulationViewModel(SavedStateHandle(mapOf("scenarioId" to "telebanking")))

    /** T-SIM-1: telebanking 주입 시 초기 UiState 5필드. */
    @Test
    fun `T_SIM_1 초기 상태 - telebanking 시나리오 5필드`() {
        val state = telebankingVm().uiState.value

        assertEquals("telebanking", state.scenario.id)
        assertEquals(0, state.currentStepIndex)
        assertNull(state.selectedChoiceIndex)
        assertEquals(0, state.correctCount)
        assertFalse(state.isCompleted)
    }

    /** T-SIM-2: 정답 선택 후 오답 중복 선택은 차단(상태 보존)된다. */
    @Test
    fun `T_SIM_2 정답 선택 후 오답 중복 선택 차단`() {
        val vm = telebankingVm()

        // 1단계 정답(index 1 "끊겠습니다") 선택
        vm.selectChoice(1)
        val afterCorrect = vm.uiState.value
        assertEquals(1, afterCorrect.selectedChoiceIndex)
        assertEquals(1, afterCorrect.correctCount)

        // 직후 오답(index 0) 중복 선택 시도 → selectedChoiceIndex ≠ null이라 무시됨
        vm.selectChoice(0)
        val afterDup = vm.uiState.value
        assertEquals("중복 선택 차단 — selectedChoiceIndex 보존", 1, afterDup.selectedChoiceIndex)
        assertEquals("중복 선택 차단 — correctCount 보존", 1, afterDup.correctCount)
    }

    /** T-SIM-3: 3단계 전부 정답 진행 → 완료(isCompleted) + 누적 정답 correctCount=3. */
    @Test
    fun `T_SIM_3 전 단계 정답 진행 후 완료 시 correctCount 3`() {
        val vm = telebankingVm()

        // 1단계 정답 → 다음 단계(선택 리셋 확인)
        vm.selectChoice(1)
        vm.nextStep()
        vm.uiState.value.let {
            assertEquals(1, it.currentStepIndex)
            assertNull("nextStep 시 selectedChoiceIndex 리셋", it.selectedChoiceIndex)
        }

        // 2단계 정답 → 다음 단계
        vm.selectChoice(1)
        vm.nextStep()
        assertEquals(2, vm.uiState.value.currentStepIndex)

        // 3단계(마지막) 정답 → nextStep → 완료 전이
        vm.selectChoice(1)
        vm.nextStep()
        val finalState = vm.uiState.value
        assertTrue("마지막 단계 nextStep → isCompleted", finalState.isCompleted)
        assertEquals("3단계 전부 정답 → correctCount=3", 3, finalState.correctCount)
    }

    /** T-SIM-4: 시나리오 데이터 무결성 — 신규 4종 존재·정확히 3스텝, 전 시나리오 정답 보장, id 중복 없음. */
    @Test
    fun `T_SIM_4 시나리오 데이터 무결성 - 신규 4종 및 전체 규약`() {
        // 신규 4종 id 존재 + 정확히 3스텝
        val newIds = listOf("ai_voice_clone", "cash_pickup", "remote_otp_combo", "romance_investment")
        newIds.forEach { id ->
            val scenario = FRAUD_SCENARIOS.find { it.id == id }
            assertNotNull("신규 시나리오 존재: $id", scenario)
            assertEquals("신규 시나리오는 정확히 3스텝: $id", 3, scenario!!.steps.size)
        }

        // 전체: 모든 step은 choices 비어있지 않음 + 정답(isCorrect=true) 최소 1개
        FRAUD_SCENARIOS.forEach { scenario ->
            scenario.steps.forEachIndexed { i, step ->
                assertTrue("choices 비어있지 않음: ${scenario.id}[$i]", step.choices.isNotEmpty())
                assertTrue("정답 최소 1개: ${scenario.id}[$i]", step.choices.any { it.isCorrect })
            }
        }

        // 전체 FRAUD_SCENARIOS id 중복 없음
        assertEquals(
            "FRAUD_SCENARIOS id 중복 없음",
            FRAUD_SCENARIOS.size,
            FRAUD_SCENARIOS.map { it.id }.toSet().size,
        )
    }
}
