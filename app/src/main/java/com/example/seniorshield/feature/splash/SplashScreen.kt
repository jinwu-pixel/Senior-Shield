package com.example.seniorshield.feature.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.example.seniorshield.core.navigation.SeniorShieldDestination

fun NavGraphBuilder.splashScreen(onNavigate: (String) -> Unit) {
    composable(route = SeniorShieldDestination.SPLASH) {
        val viewModel: SplashViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(uiState) {
            val state = uiState
            if (state is SplashUiState.Ready) onNavigate(state.route)
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "시니어쉴드", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
        }
    }
}