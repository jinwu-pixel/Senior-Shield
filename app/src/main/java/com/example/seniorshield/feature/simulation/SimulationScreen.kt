package com.example.seniorshield.feature.simulation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.component.dpadFocusHighlight
import com.example.seniorshield.core.navigation.SeniorShieldDestination

fun NavGraphBuilder.simulationListScreen(
    onBack: () -> Unit,
    onSelectScenario: (String) -> Unit,
) {
    composable(route = SeniorShieldDestination.SIMULATION_LIST) {
        SeniorShieldScaffold(title = "보이스피싱 대응 연습", onBackClick = onBack) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "실제 보이스피싱 시나리오를 체험하고\n올바른 대응 방법을 연습해 보세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                for (scenario in FRAUD_SCENARIOS) {
                    ScenarioCard(scenario = scenario, onClick = { onSelectScenario(scenario.id) })
                }
            }
        }
    }
}

fun NavGraphBuilder.simulationPlayScreen(onBack: () -> Unit) {
    composable(route = "${SeniorShieldDestination.SIMULATION_PLAY}/{scenarioId}") {
        val viewModel: SimulationViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        SeniorShieldScaffold(
            title = uiState.scenario.title,
            onBackClick = onBack,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (uiState.isCompleted) {
                    CompletionContent(uiState = uiState, onBack = onBack)
                } else {
                    StepContent(
                        uiState = uiState,
                        onSelectChoice = viewModel::selectChoice,
                        onNext = viewModel::nextStep,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: FraudScenario, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusHighlight(shape = RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = scenario.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = scenario.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${scenario.steps.size}단계",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StepContent(
    uiState: SimulationUiState,
    onSelectChoice: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val step = uiState.scenario.steps[uiState.currentStepIndex]

    // 진행률
    val progress = (uiState.currentStepIndex + 1).toFloat() / uiState.scenario.steps.size
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${uiState.currentStepIndex + 1} / ${uiState.scenario.steps.size} 단계",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    // 사기꾼 메시지
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFEBEE))
            .padding(16.dp),
    ) {
        Text(
            text = "사기꾼",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB71C1C),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = step.fraudsterMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF212121),
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = "어떻게 대응하시겠습니까?",
        style = MaterialTheme.typography.titleSmall,
    )

    // 선택지
    step.choices.forEachIndexed { index, choice ->
        val isSelected = uiState.selectedChoiceIndex == index
        val hasAnswered = uiState.selectedChoiceIndex != null

        val containerColor = when {
            !hasAnswered -> MaterialTheme.colorScheme.surface
            isSelected && choice.isCorrect -> Color(0xFFE8F5E9)
            isSelected && !choice.isCorrect -> Color(0xFFFFEBEE)
            !isSelected && choice.isCorrect -> Color(0xFFE8F5E9).copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surface
        }

        Card(
            onClick = { if (!hasAnswered) onSelectChoice(index) },
            modifier = Modifier
                .fillMaxWidth()
                .dpadFocusHighlight(shape = RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = choice.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (hasAnswered && (isSelected || choice.isCorrect)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = choice.feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (choice.isCorrect) Color(0xFF2E7D32) else Color(0xFFB71C1C),
                    )
                }
            }
        }
    }

    // 다음 버튼
    if (uiState.selectedChoiceIndex != null) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (uiState.currentStepIndex + 1 >= uiState.scenario.steps.size) "결과 보기"
                else "다음 단계",
            )
        }
    }
}

@Composable
private fun CompletionContent(uiState: SimulationUiState, onBack: () -> Unit) {
    val total = uiState.scenario.steps.size
    val correct = uiState.correctCount
    val isPerfect = correct == total

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = if (isPerfect) "완벽합니다!" else "연습 완료!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isPerfect) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$correct / $total",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isPerfect) Color(0xFF2E7D32) else Color(0xFFE65100),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "정답",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // 요약 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(16.dp),
        ) {
            Text(
                text = "기억하세요!",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            val tips = listOf(
                "정부기관은 전화로 개인정보를 요구하지 않습니다.",
                "의심되면 바로 전화를 끊고 해당 기관에 직접 확인하세요.",
                "전화로 돈을 보내라는 요구는 모두 사기입니다.",
            )
            tips.forEach { tip ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = "\u2022  $tip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("목록으로 돌아가기")
        }
    }
}
