package com.example.seniorshield.feature.warning

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.BasicTextButton
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SecondaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.designsystem.theme.StatusGreen
import com.example.seniorshield.core.designsystem.theme.StatusRed
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.core.util.ContactIntentHelper
import com.example.seniorshield.domain.model.Guardian
import com.example.seniorshield.domain.model.RiskLevel

private data class InstitutionInfo(
    val name: String,
    val number: String,
    val description: String,
)

private val institutions = listOf(
    InstitutionInfo("경찰청", "112", "금융사기 피해 신고"),
    InstitutionInfo("금융감독원", "1332", "금융사기 피해 상담 및 신고"),
    InstitutionInfo("한국인터넷진흥원", "118", "개인정보 침해, 보이스피싱 신고"),
    InstitutionInfo("금융결제원", "1577-5500", "계좌 지급정지 요청"),
)

/**
 * Behavior Check(자가확인) 문항 — 앱이 관측할 수 없는 피해 경로(현금 대면 전달·인증번호
 * 구두 유출·상품권 등)를 사용자 스스로 점검하게 한다. 응답은 화면 문구 강화에만 쓰인다.
 */
private val behaviorCheckQuestions = listOf(
    "지금 돈을 보내라고 했나요?",
    "인증번호를 알려달라고 했나요?",
    "앱 설치나 화면 공유를 요구했나요?",
    "은행·검찰·금감원·자녀라며 급하다고 했나요?",
    "현금을 찾아서 누군가에게 전달하라고 했나요?",
)

fun NavGraphBuilder.warningScreen(
    onBack: () -> Unit,
    onNavigateGuardian: () -> Unit,
) {
    composable(route = SeniorShieldDestination.WARNING) {
        val viewModel: WarningViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val context = LocalContext.current

        WarningContent(
            uiState = uiState,
            onFamilyCallClick = {
                when {
                    uiState.guardians.isEmpty() -> onNavigateGuardian()
                    uiState.guardians.size == 1 -> {
                        context.startActivity(
                            ContactIntentHelper.dialIntent(uiState.guardians.first().phoneNumber)
                        )
                    }
                    else -> viewModel.showGuardianPicker()
                }
            },
            onInstitutionCall = { number ->
                context.startActivity(ContactIntentHelper.dialIntent(number))
            },
            onBack = onBack,
            onConfirmSafe = {
                // 책임 분리: confirmSafe()는 상태 종료(세션 + anchor + currentEvent),
                //            onBack()은 화면 종료(네비게이션 복귀). 둘은 별개 의도.
                viewModel.confirmSafe()
                onBack()
            },
            onDismissGuardianPicker = viewModel::dismissGuardianPicker,
            onGuardianSelected = { guardian ->
                viewModel.dismissGuardianPicker()
                context.startActivity(ContactIntentHelper.dialIntent(guardian.phoneNumber))
            },
            onSmsClick = {
                when {
                    uiState.guardians.size == 1 -> {
                        val intent = ContactIntentHelper.smsIntent(uiState.guardians.first().phoneNumber)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "문자 앱을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                        }
                    }
                    uiState.guardians.size > 1 -> viewModel.showSmsPicker()
                    else -> Unit
                }
            },
            onDismissSmsPicker = viewModel::dismissSmsPicker,
            onSmsGuardianSelected = { guardian ->
                viewModel.dismissSmsPicker()
                val intent = ContactIntentHelper.smsIntent(guardian.phoneNumber)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "문자 앱을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            },
            onBehaviorAnswer = viewModel::answerBehaviorCheck,
        )
    }
}

@Composable
private fun WarningContent(
    uiState: WarningUiState,
    onFamilyCallClick: () -> Unit,
    onInstitutionCall: (String) -> Unit,
    onBack: () -> Unit,
    onConfirmSafe: () -> Unit,
    onDismissGuardianPicker: () -> Unit,
    onGuardianSelected: (Guardian) -> Unit,
    onSmsClick: () -> Unit,
    onDismissSmsPicker: () -> Unit,
    onSmsGuardianSelected: (Guardian) -> Unit,
    onBehaviorAnswer: (Int, Boolean) -> Unit,
) {
    SeniorShieldScaffold(title = "위험 경고", onBackClick = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WarningCard(
                eventTitle = uiState.detectedEventTitle,
                eventDescription = uiState.detectedEventDescription,
                hasActiveEvent = uiState.detectedEventLevel != null,
            )
            Spacer(modifier = Modifier.height(24.dp))
            BehaviorCheckSection(
                answers = uiState.behaviorCheckAnswers,
                anyYes = uiState.behaviorCheckAnyYes,
                onAnswer = onBehaviorAnswer,
            )
            Spacer(modifier = Modifier.height(32.dp))
            Checklist()
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(text = "보호자에게 전화하기", onClick = onFamilyCallClick)
            if (uiState.smsMenuEnabled && uiState.guardians.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SecondaryButton(text = "보호자에게 문자 보내기", onClick = onSmsClick)
            }
            Spacer(modifier = Modifier.height(32.dp))
            InstitutionSection(onCall = onInstitutionCall)
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onConfirmSafe,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StatusGreen),
            ) {
                Text(
                    text = "안전 확인 — 위험하지 않습니다",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            BasicTextButton(text = "닫기", onClick = onBack)
        }
    }

    if (uiState.showGuardianPicker) {
        GuardianPickerDialog(
            guardians = uiState.guardians,
            onDismiss = onDismissGuardianPicker,
            onSelect = onGuardianSelected,
        )
    }

    if (uiState.showSmsPicker) {
        SmsGuardianPickerDialog(
            guardians = uiState.guardians,
            onDismiss = onDismissSmsPicker,
            onSelect = onSmsGuardianSelected,
        )
    }
}

@Composable
private fun WarningCard(
    eventTitle: String?,
    eventDescription: String?,
    hasActiveEvent: Boolean,
) {
    // 활성 위험 이벤트 없이 진입한 경우(홈 GUARDED 카드 탭 등)에는
    // 붉은 "감지" 문구 대신 중립 자가확인 안내로 분기한다.
    val accent = if (hasActiveEvent) StatusRed else MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.05f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = "경고",
                tint = accent,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = eventTitle
                    ?: if (hasActiveEvent) "금융사기 위험이 감지되었습니다"
                    else "지금 통화·요구 사항을 스스로 확인해 보세요",
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = eventDescription
                    ?: if (hasActiveEvent) "상대방의 지시에 따라 앱을 설치하거나 돈을 보내지 마세요. 즉시 중단해야 합니다."
                    else "의심스러운 전화나 요구가 있었다면 아래 질문에 답해 보세요.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun BehaviorCheckSection(
    answers: Map<Int, Boolean>,
    anyYes: Boolean,
    onAnswer: (Int, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "지금 상황을 확인해 보세요", style = MaterialTheme.typography.titleLarge)
        behaviorCheckQuestions.forEachIndexed { index, question ->
            BehaviorCheckItem(
                question = question,
                answer = answers[index],
                onAnswer = { yes -> onAnswer(index, yes) },
            )
        }
        if (anyYes) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.08f)),
            ) {
                Text(
                    text = "하나라도 \"예\"라면 사기일 가능성이 매우 높습니다.\n" +
                        "지금 하던 일을 멈추고, 아래 \"보호자에게 전화하기\"나 112·1332로 바로 확인하세요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = StatusRed,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BehaviorCheckItem(
    question: String,
    answer: Boolean?,
    onAnswer: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = question, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BehaviorAnswerButton(
                    text = "예",
                    selected = answer == true,
                    selectedColor = StatusRed,
                    onClick = { onAnswer(true) },
                    modifier = Modifier.weight(1f),
                )
                BehaviorAnswerButton(
                    text = "아니요",
                    selected = answer == false,
                    selectedColor = StatusGreen,
                    onClick = { onAnswer(false) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BehaviorAnswerButton(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(containerColor = selectedColor),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Checklist() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "지금 해야 할 일", style = MaterialTheme.typography.titleLarge)
        ChecklistItem("전화를 끊으세요.")
        ChecklistItem("앱을 더 설치하지 마세요.")
        ChecklistItem("가족이나 공식 기관에 먼저 확인하세요.")
    }
}

@Composable
private fun ChecklistItem(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = "확인",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InstitutionSection(onCall: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "공식 기관 연락처",
            style = MaterialTheme.typography.titleMedium,
        )
        for (inst in institutions) {
            InstitutionCard(info = inst, onCall = { onCall(inst.number) })
        }
    }
}

@Composable
private fun InstitutionCard(info: InstitutionInfo, onCall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${info.name}  ${info.number}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onCall) { Text("전화하기") }
        }
    }
}

@Composable
private fun GuardianPickerDialog(
    guardians: List<Guardian>,
    onDismiss: () -> Unit,
    onSelect: (Guardian) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("연락할 보호자 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                guardians.forEach { guardian ->
                    TextButton(
                        onClick = { onSelect(guardian) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${guardian.name}${if (guardian.relationship.isNotBlank()) " (${guardian.relationship})" else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun SmsGuardianPickerDialog(
    guardians: List<Guardian>,
    onDismiss: () -> Unit,
    onSelect: (Guardian) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("문자 보낼 보호자 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                guardians.forEach { guardian ->
                    TextButton(
                        onClick = { onSelect(guardian) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${guardian.name}${if (guardian.relationship.isNotBlank()) " (${guardian.relationship})" else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun WarningScreenPreview() {
    SeniorShieldTheme {
        WarningContent(
            uiState = WarningUiState(
                detectedEventTitle = "의심 통화 후 원격제어 앱 실행",
                detectedEventDescription = "알 수 없는 번호와 통화 직후 원격제어 앱이 실행되었습니다.",
                detectedEventLevel = RiskLevel.HIGH,
            ),
            onFamilyCallClick = {},
            onInstitutionCall = {},
            onBack = {},
            onConfirmSafe = {},
            onDismissGuardianPicker = {},
            onGuardianSelected = {},
            onSmsClick = {},
            onDismissSmsPicker = {},
            onSmsGuardianSelected = {},
            onBehaviorAnswer = { _, _ -> },
        )
    }
}
