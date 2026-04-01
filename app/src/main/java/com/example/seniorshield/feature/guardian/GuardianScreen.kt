package com.example.seniorshield.feature.guardian

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SecondaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.core.util.ContactIntentHelper
import com.example.seniorshield.domain.model.Guardian

fun NavGraphBuilder.guardianScreen(onBack: () -> Unit) {
    composable(route = SeniorShieldDestination.GUARDIAN) {
        val viewModel: GuardianViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val context = LocalContext.current

        GuardianContent(
            uiState = uiState,
            onAddClick = viewModel::showAddDialog,
            onDismissDialog = viewModel::hideAddDialog,
            onConfirmAdd = viewModel::addGuardian,
            onRemove = viewModel::removeGuardian,
            onBack = onBack,
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
    onSmsClick: () -> Unit,
    onDismissSmsPicker: () -> Unit,
    onSmsGuardianSelected: (Guardian) -> Unit,
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

            if (uiState.smsMenuEnabled && uiState.guardians.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    SecondaryButton(
                        text = "보호자에게 문자 보내기",
                        onClick = onSmsClick,
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

    if (uiState.showSmsPicker) {
        SmsGuardianPickerDialog(
            guardians = uiState.guardians,
            onDismiss = onDismissSmsPicker,
            onSelect = onSmsGuardianSelected,
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

@Composable
private fun AddGuardianDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }

    val phoneFocus = remember { FocusRequester() }
    val relationshipFocus = remember { FocusRequester() }

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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { phoneFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("전화번호") },
                    placeholder = { Text("010-1234-5678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { relationshipFocus.requestFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(phoneFocus),
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("관계 (선택)") },
                    placeholder = { Text("예: 아들, 딸") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (name.isNotBlank() && phone.isNotBlank()) onConfirm(name, phone, relationship)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(relationshipFocus),
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
