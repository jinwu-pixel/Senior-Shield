package com.example.seniorshield.feature.history

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.component.StatusCard
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.RiskEvent

fun NavGraphBuilder.historyScreen(onBack: () -> Unit) {
    composable(route = SeniorShieldDestination.HISTORY) {
        val viewModel: HistoryViewModel = hiltViewModel()
        val events by viewModel.events.collectAsStateWithLifecycle()

        HistoryContent(
            title = "전체 감지 기록",
            events = events,
            onBack = onBack
        )
    }
}

@Composable
private fun HistoryContent(
    title: String,
    events: List<RiskEvent>,
    onBack: () -> Unit,
) {
    SeniorShieldScaffold(title = title, onBackClick = onBack) { padding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "아직 감지된 위험 기록이 없습니다.\n앱이 위험 상황을 감지하면 이곳에 자동으로 기록됩니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(events) { event ->
                    val body =
                        "${DateFormat.format("yyyy-MM-dd HH:mm", event.occurredAtMillis)}\n${event.description}"
                    StatusCard(
                        title = event.title,
                        body = body,
                        riskLevel = event.level
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    SeniorShieldTheme {
        HistoryContent(title = "전체 감지 기록", events = emptyList(), onBack = {})
    }
}
