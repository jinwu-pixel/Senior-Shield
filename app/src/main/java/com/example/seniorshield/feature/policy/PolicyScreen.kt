package com.example.seniorshield.feature.policy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.PolicySummary

fun NavGraphBuilder.policyScreen(onBack: () -> Unit) {
    composable(route = SeniorShieldDestination.POLICY) {
        val viewModel: PolicyViewModel = hiltViewModel()
        val policy by viewModel.policy.collectAsStateWithLifecycle()

        PolicyContent(
            policy = policy,
            onBack = onBack
        )
    }
}

@Composable
private fun PolicyContent(
    policy: PolicySummary,
    onBack: () -> Unit,
) {
    SeniorShieldScaffold(title = policy.title, onBackClick = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(policy.items) { item ->
                Row {
                    Text("• ", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Text(item, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PolicyScreenPreview() {
    SeniorShieldTheme {
        PolicyContent(
            policy = PolicySummary(
                "서비스 원칙",
                listOf("원칙 1", "원칙 2")
            ),
            onBack = {}
        )
    }
}
