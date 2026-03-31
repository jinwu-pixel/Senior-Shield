package com.example.seniorshield.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SecondaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.component.StatusCard
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.RiskLevel

fun NavGraphBuilder.homeScreen(
    onNavigateHistory: () -> Unit,
    onNavigateWarning: () -> Unit,
    onNavigatePermissions: () -> Unit,
    onNavigatePolicy: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateSimulation: () -> Unit,
) {
    composable(route = SeniorShieldDestination.HOME) {
        val viewModel: HomeViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(viewModel) {
            viewModel.navigateToWarning.collect { onNavigateWarning() }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        HomeContent(
            uiState = uiState,
            onNavigateHistory = onNavigateHistory,
            onNavigateWarning = onNavigateWarning,
            onNavigatePermissions = onNavigatePermissions,
            onNavigatePolicy = onNavigatePolicy,
            onNavigateSettings = onNavigateSettings,
            onNavigateSimulation = onNavigateSimulation,
        )
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onNavigateHistory: () -> Unit,
    onNavigateWarning: () -> Unit,
    onNavigatePermissions: () -> Unit,
    onNavigatePolicy: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateSimulation: () -> Unit,
) {
    val hasActiveRisk = uiState.currentRiskLevel.ordinal >= RiskLevel.HIGH.ordinal

    SeniorShieldScaffold(title = "시니어쉴드") { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 24.dp),
        ) {
            if (!uiState.hasCriticalPermissions) {
                item {
                    PermissionsWarningBanner(
                        onNavigatePermissions = onNavigatePermissions,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    StatusCard(
                        title = uiState.currentRiskTitle,
                        body = uiState.currentRiskBody,
                        riskLevel = uiState.currentRiskLevel,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .padding(horizontal = 24.dp)
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (hasActiveRisk) {
                        PrimaryButton(text = "가족에게 바로 연락하기", onClick = onNavigateWarning)
                    }
                    if (uiState.recentEventCount > 0) {
                        SecondaryButton(text = "전체 감지 기록 보기", onClick = onNavigateHistory)
                    }
                    SecondaryButton(text = "보이스피싱 대응 연습", onClick = onNavigateSimulation)
                }
            }

            if (uiState.weeklyTip.isNotEmpty()) {
                item {
                    WeeklyTipCard(
                        tip = uiState.weeklyTip,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }

            item {
                HorizontalDivider(
                    thickness = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                )
            }

            item {
                Column(modifier = Modifier.focusGroup()) {
                    SettingsListItem(text = "권한 설정 및 안내", onClick = onNavigatePermissions)
                    SettingsListItem(text = "서비스 원칙 및 정책", onClick = onNavigatePolicy)
                    SettingsListItem(text = "앱 설정", onClick = onNavigateSettings)
                }
            }
        }
    }
}

@Composable
private fun WeeklyTipCard(tip: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        Text(
            text = "이번 주 예방 팁",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = tip,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PermissionsWarningBanner(
    onNavigatePermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = "권한 경고",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = "필수 권한이 허용되지 않았습니다",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "'전화 상태 읽기'와 '다른 앱 위에 표시' 권한이 없으면 위험 감지가 작동하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onNavigatePermissions) {
                    Text(
                        text = "권한 설정하기",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsListItem(
    text: String,
    onClick: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val containerColor = if (hasFocus) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }
    ListItem(
        headlineContent = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
            )
        },
        modifier = Modifier
            .onFocusChanged { hasFocus = it.hasFocus }
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = containerColor),
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SeniorShieldTheme {
        HomeContent(
            uiState = HomeUiState(
                currentRiskBody = "안전합니다. 감지된 위험이 없습니다.\n최근 24시간: 0건 · 이번 주: 0건",
                currentRiskLevel = RiskLevel.LOW,
                weeklyTip = "모르는 번호로 온 전화에서 '검찰', '경찰'을 언급하면 100% 사기입니다.",
            ),
            onNavigateHistory = {},
            onNavigateWarning = {},
            onNavigatePermissions = {},
            onNavigatePolicy = {},
            onNavigateSettings = {},
            onNavigateSimulation = {},
        )
    }
}
