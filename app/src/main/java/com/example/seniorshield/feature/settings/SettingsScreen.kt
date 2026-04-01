package com.example.seniorshield.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.BuildConfig
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.RiskLevel
import com.example.seniorshield.domain.model.RiskScore
import com.example.seniorshield.domain.model.RiskSignal
import com.example.seniorshield.monitoring.session.RiskSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun NavGraphBuilder.settingsScreen(
    onBack: () -> Unit,
    onNavigatePolicy: () -> Unit,
    onNavigateGuardian: () -> Unit,
) {
    composable(route = SeniorShieldDestination.SETTINGS) {
        val debugVm: DebugViewModel = hiltViewModel()
        val session by debugVm.session.collectAsStateWithLifecycle()
        val score by debugVm.score.collectAsStateWithLifecycle()
        val testModeEnabled by debugVm.testModeEnabled.collectAsStateWithLifecycle()
        val smsMenuEnabled by debugVm.smsMenuEnabled.collectAsStateWithLifecycle()

        SettingsContent(
            session = session,
            score = score,
            testModeEnabled = testModeEnabled,
            smsMenuEnabled = smsMenuEnabled,
            onNavigatePolicy = onNavigatePolicy,
            onNavigateGuardian = onNavigateGuardian,
            onBack = onBack,
            onResetAll = debugVm::resetAll,
            onShowTestOverlay = debugVm::showTestOverlay,
            onShowTestCooldown = debugVm::showTestCooldown,
            onTestModeToggle = debugVm::setTestModeEnabled,
            onSmsMenuToggle = debugVm::setSmsMenuEnabled,
        )
    }
}

@Composable
private fun SettingsContent(
    session: RiskSession?,
    score: RiskScore?,
    testModeEnabled: Boolean,
    smsMenuEnabled: Boolean,
    onNavigatePolicy: () -> Unit,
    onNavigateGuardian: () -> Unit,
    onBack: () -> Unit,
    onResetAll: () -> Unit,
    onShowTestOverlay: () -> Unit,
    onShowTestCooldown: () -> Unit,
    onTestModeToggle: (Boolean) -> Unit,
    onSmsMenuToggle: (Boolean) -> Unit,
) {
    SeniorShieldScaffold(title = "앱 설정", onBackClick = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsListItem(text = "보호자 연락처 관리", onClick = onNavigateGuardian)
            SettingsListItem(text = "서비스 원칙 및 개인정보 처리방침", onClick = onNavigatePolicy)
            SmsMenuToggleItem(enabled = smsMenuEnabled, onToggle = onSmsMenuToggle)

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()

                // ── 테스트 도구 ───────────────────────────────────────────────
                DebugPanel(
                    session = session,
                    score = score,
                    testModeEnabled = testModeEnabled,
                    onResetAll = onResetAll,
                    onShowTestOverlay = onShowTestOverlay,
                    onShowTestCooldown = onShowTestCooldown,
                    onTestModeToggle = onTestModeToggle,
                )
            }
        }
    }
}

// ── 테스트 도구 패널 ─────────────────────────────────────────────────────────

@Composable
private fun DebugPanel(
    session: RiskSession?,
    score: RiskScore?,
    testModeEnabled: Boolean,
    onResetAll: () -> Unit,
    onShowTestOverlay: () -> Unit,
    onShowTestCooldown: () -> Unit,
    onTestModeToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "🛠 테스트 도구",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 테스트 모드 토글
        TestModeToggleItem(enabled = testModeEnabled, onToggle = onTestModeToggle)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 현재 세션 상태 카드
        SessionStateCard(session = session, score = score)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 초기화 버튼
        Button(
            onClick = onResetAll,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("전체 상태 초기화 (세션 + 이력)")
        }

        // 미리보기 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onShowTestOverlay,
                modifier = Modifier.weight(1f),
            ) {
                Text("위험 팝업\n미리보기", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onShowTestCooldown,
                modifier = Modifier.weight(1f),
            ) {
                Text("뱅킹 쿨다운\n미리보기", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SessionStateCard(session: RiskSession?, score: RiskScore?) {
    val bgColor = if (session != null)
        MaterialTheme.colorScheme.surface
    else
        MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (session == null) {
            Text(
                "세션 없음 — 탐지 신호 없음",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val levelColor = when (score?.level) {
                RiskLevel.CRITICAL -> Color(0xFFB71C1C)
                RiskLevel.HIGH     -> Color(0xFFBF360C)
                RiskLevel.MEDIUM   -> Color(0xFFE65100)
                else               -> MaterialTheme.colorScheme.onSurface
            }
            SessionRow("점수", "${score?.total ?: 0}점 / ${score?.level?.name ?: "—"}", labelColor = levelColor)
            SessionRow("마지막 알림", session.notifiedLevel?.name ?: "없음")
            SessionRow("능동적 위협 알림", if (session.notifiedActiveThreats.isEmpty()) "없음" else session.notifiedActiveThreats.joinToString { it.name })
            SessionRow("세션 시작", formatTime(session.startedAt))
            SessionRow(
                "누적 신호",
                if (session.accumulatedSignals.isEmpty()) "없음"
                else session.accumulatedSignals.joinToString("\n") { it.toKorean() },
            )
        }
    }
}

@Composable
private fun SessionRow(label: String, value: String, labelColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (labelColor != Color.Unspecified) labelColor
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.65f),
        )
    }
}

// ── 유틸 ─────────────────────────────────────────────────────────────────────

private fun RiskSignal.toKorean(): String = when (this) {
    RiskSignal.UNKNOWN_CALLER                    -> "모르는 발신자"
    RiskSignal.LONG_CALL_DURATION                -> "장시간 통화"
    RiskSignal.UNVERIFIED_CALLER                 -> "미검증 발신자"
    RiskSignal.REMOTE_CONTROL_APP_OPENED         -> "원격제어 앱 실행"
    RiskSignal.BANKING_APP_OPENED_AFTER_REMOTE_APP -> "원격 후 뱅킹 앱"
    RiskSignal.HIGH_RISK_DEVICE_ENVIRONMENT      -> "고위험 기기 환경"
    RiskSignal.SUSPICIOUS_APP_INSTALLED          -> "의심 앱 설치"
    RiskSignal.REPEATED_UNKNOWN_CALLER           -> "반복 의심 호출"
    RiskSignal.REPEATED_CALL_THEN_LONG_TALK      -> "반복 호출 후 장시간 통화"
    RiskSignal.TELEBANKING_AFTER_SUSPICIOUS      -> "텔레뱅킹 유도"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(millis))

@Composable
private fun TestModeToggleItem(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = {
            Text("테스트 모드", style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
            Text(
                if (enabled) "장시간 통화 기준: 10초 (테스트용)" else "장시간 통화 기준: 3분 (프로덕션)",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ── 공통 컴포넌트 ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsListItem(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SmsMenuToggleItem(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = {
            Text("보호자에게 문자 보내기 메뉴", style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Text(
                "문자는 사용자가 직접 확인 후 전송합니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    SeniorShieldTheme {
        SettingsContent(
            session = null,
            score = null,
            testModeEnabled = false,
            smsMenuEnabled = false,
            onNavigatePolicy = {},
            onNavigateGuardian = {},
            onBack = {},
            onResetAll = {},
            onShowTestOverlay = {},
            onShowTestCooldown = {},
            onTestModeToggle = {},
            onSmsMenuToggle = {},
        )
    }
}
