package com.example.seniorshield.feature.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.Guardian

fun NavGraphBuilder.guardianScreen(onBack: () -> Unit) {
    composable(route = SeniorShieldDestination.GUARDIAN) {
        val viewModel: GuardianViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        GuardianContent(
            uiState = uiState,
            onAddClick = viewModel::showAddDialog,
            onDismissDialog = viewModel::hideAddDialog,
            onConfirmAdd = viewModel::addGuardian,
            onRemove = viewModel::removeGuardian,
            onBack = onBack,
        )

        uiState.message?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                viewModel.clearMessage()
            }
        }
    }
}

@Composable
private fun GuardianContent(
    uiState: GuardianUiState,
    onAddClick: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmAdd: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    SeniorShieldScaffold(title = "보호자 연락처 관리", onBackClick = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "위험 감지 시 전화 연락할 보호자를 등록하세요.\n최대 ${Guardian.MAX_COUNT}명까지 가능합니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            items(uiState.guardians, key = { it.id }) { guardian ->
                GuardianCard(guardian = guardian, onRemove = { onRemove(guardian.id) })
            }

            if (uiState.canAddMore) {
                item {
                    PrimaryButton(
                        text = "보호자 추가",
                        onClick = onAddClick,
                    )
                }
            }

            uiState.message?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (uiState.showAddDialog) {
        AddGuardianDialog(
            onDismiss = onDismissDialog,
            onConfirm = onConfirmAdd,
        )
    }
}

@Composable
private fun GuardianCard(guardian: Guardian, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${guardian.name} ${if (guardian.relationship.isNotBlank()) "(${guardian.relationship})" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = guardian.phoneNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddGuardianDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("보호자 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("전화번호") },
                    placeholder = { Text("010-1234-5678") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("관계 (선택)") },
                    placeholder = { Text("예: 아들, 딸") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, phone, relationship) },
                enabled = name.isNotBlank() && phone.isNotBlank(),
            ) { Text("등록") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
