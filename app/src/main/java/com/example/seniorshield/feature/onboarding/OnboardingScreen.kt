package com.example.seniorshield.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldDestination

fun NavGraphBuilder.onboardingScreen(onComplete: () -> Unit) {
    composable(route = SeniorShieldDestination.ONBOARDING) {
        val viewModel: OnboardingViewModel = hiltViewModel()
        OnboardingContent(onStartClick = { viewModel.completeOnboarding(onComplete) })
    }
}

@Composable
private fun OnboardingContent(onStartClick: () -> Unit) {
    SeniorShieldScaffold(title = "시니어쉴드 시작하기") { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(
                text = "어르신의 소중한 자산을\n안전하게 지켜드려요",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "시니어쉴드는 금융사기 위험이 감지되면, 중요한 행동을 하기 전에 잠시 멈추고 다시 확인할 수 있도록 돕는 앱입니다.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "이 앱은 다른 사람을 감시하거나 나의 활동 정보를 다른 사람에게 자동으로 보내지 않으니 안심하고 사용하세요.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(
                text = "시작하기",
                onClick = onStartClick,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    SeniorShieldTheme {
        OnboardingContent(onStartClick = {})
    }
}
