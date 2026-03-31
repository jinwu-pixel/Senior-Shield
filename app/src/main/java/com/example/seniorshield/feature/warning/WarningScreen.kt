package com.example.seniorshield.feature.warning

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.designsystem.theme.StatusRed
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.core.util.ContactIntentHelper
import com.example.seniorshield.domain.model.Guardian

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
            onDismissGuardianPicker = viewModel::dismissGuardianPicker,
            onGuardianSelected = { guardian ->
                viewModel.dismissGuardianPicker()
                context.startActivity(ContactIntentHelper.dialIntent(guardian.phoneNumber))
            },
        )
    }
}

@Composable
private fun WarningContent(
    uiState: WarningUiState,
    onFamilyCallClick: () -> Unit,
    onInstitutionCall: (String) -> Unit,
    onBack: () -> Unit,
    onDismissGuardianPicker: () -> Unit,
    onGuardianSelected: (Guardian) -> Unit,
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
            )
            Spacer(modifier = Modifier.height(32.dp))
            Checklist()
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(text = "가족에게 전화하기", onClick = onFamilyCallClick)
            Spacer(modifier = Modifier.height(32.dp))
            InstitutionSection(onCall = onInstitutionCall)
            Spacer(modifier = Modifier.height(24.dp))
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
}

@Composable
private fun WarningCard(
    eventTitle: String?,
    eventDescription: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.05f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = "경고",
                tint = StatusRed,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = eventTitle ?: "금융사기 위험이 감지되었습니다",
                style = MaterialTheme.typography.headlineMedium,
                color = StatusRed,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = eventDescription
                    ?: "상대방의 지시에 따라 앱을 설치하거나 돈을 보내지 마세요. 즉시 중단해야 합니다.",
                style = MaterialTheme.typography.bodyLarge,
            )
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

@Preview(showBackground = true)
@Composable
private fun WarningScreenPreview() {
    SeniorShieldTheme {
        WarningContent(
            uiState = WarningUiState(
                detectedEventTitle = "의심 통화 후 원격제어 앱 실행",
                detectedEventDescription = "알 수 없는 번호와 통화 직후 원격제어 앱이 실행되었습니다.",
            ),
            onFamilyCallClick = {},
            onInstitutionCall = {},
            onBack = {},
            onDismissGuardianPicker = {},
            onGuardianSelected = {},
        )
    }
}
