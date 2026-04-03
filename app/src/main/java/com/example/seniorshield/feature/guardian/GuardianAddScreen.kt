package com.example.seniorshield.feature.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.navigation.SeniorShieldDestination

fun NavGraphBuilder.guardianAddScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    composable(route = SeniorShieldDestination.GUARDIAN_ADD) {
        val viewModel: GuardianAddViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(viewModel) {
            viewModel.savedEvent.collect { onSaved() }
        }

        GuardianAddContent(
            uiState = uiState,
            onNameChange = viewModel::updateName,
            onPhoneChange = viewModel::updatePhone,
            onRelationshipChange = viewModel::updateRelationship,
            onSave = viewModel::save,
            onBack = onBack,
        )
    }
}

@Composable
private fun GuardianAddContent(
    uiState: GuardianAddUiState,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onRelationshipChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val nameFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val relationshipFocus = remember { FocusRequester() }

    fun dpadNavigation(
        down: FocusRequester?,
        up: FocusRequester?,
    ): (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> { down?.requestFocus(); true }
                Key.DirectionUp -> { up?.requestFocus(); true }
                else -> false
            }
        } else false
    }

    com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold(
        title = "보호자 추가",
        onBackClick = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                text = "보호자 정보를 입력해주세요.",
                style = MaterialTheme.typography.bodyLarge,
            )

            OutlinedTextField(
                value = uiState.name,
                onValueChange = onNameChange,
                label = { Text("이름") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { phoneFocus.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocus)
                    .onPreviewKeyEvent(dpadNavigation(down = phoneFocus, up = null)),
            )

            OutlinedTextField(
                value = uiState.phone,
                onValueChange = onPhoneChange,
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
                    .focusRequester(phoneFocus)
                    .onPreviewKeyEvent(dpadNavigation(down = relationshipFocus, up = nameFocus)),
            )

            OutlinedTextField(
                value = uiState.relationship,
                onValueChange = onRelationshipChange,
                label = { Text("관계 (선택)") },
                placeholder = { Text("예: 아들, 딸") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (uiState.canSave) onSave() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(relationshipFocus)
                    .onPreviewKeyEvent(dpadNavigation(down = null, up = phoneFocus)),
            )

            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            PrimaryButton(
                text = "등록",
                onClick = onSave,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
