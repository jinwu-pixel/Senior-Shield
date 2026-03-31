package com.example.seniorshield.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.designsystem.theme.StatusGreen
import com.example.seniorshield.core.designsystem.theme.StatusOrange
import com.example.seniorshield.core.designsystem.theme.StatusRed
import com.example.seniorshield.core.designsystem.theme.StatusYellow
import com.example.seniorshield.domain.model.RiskLevel

@Composable
fun StatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    riskLevel: RiskLevel = RiskLevel.LOW,
) {
    val (icon, color, containerColor) = when (riskLevel) {
        RiskLevel.LOW -> Triple(Icons.Rounded.CheckCircle, StatusGreen, StatusGreen.copy(alpha = 0.05f))
        RiskLevel.MEDIUM -> Triple(Icons.Rounded.Info, StatusYellow, StatusYellow.copy(alpha = 0.05f))
        RiskLevel.HIGH -> Triple(Icons.Rounded.Warning, StatusOrange, StatusOrange.copy(alpha = 0.05f))
        RiskLevel.CRITICAL -> Triple(Icons.Rounded.Error, StatusRed, StatusRed.copy(alpha = 0.05f))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "상태 아이콘",
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview
@Composable
private fun StatusCardPreview() {
    SeniorShieldTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(title = "안전", body = "감지된 위험 없음", riskLevel = RiskLevel.LOW)
            StatusCard(title = "주의", body = "알 수 없는 번호와 통화", riskLevel = RiskLevel.MEDIUM)
            StatusCard(title = "경고", body = "원격 제어 앱 실행 감지", riskLevel = RiskLevel.HIGH)
            StatusCard(title = "심각", body = "원격 제어 중 금융 앱 실행", riskLevel = RiskLevel.CRITICAL)
        }
    }
}
