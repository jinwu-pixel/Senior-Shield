package com.example.seniorshield.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.example.seniorshield.feature.guardian.guardianScreen
import com.example.seniorshield.feature.history.historyScreen
import com.example.seniorshield.feature.home.homeScreen
import com.example.seniorshield.feature.onboarding.onboardingScreen
import com.example.seniorshield.feature.permissions.permissionsScreen
import com.example.seniorshield.feature.policy.policyScreen
import com.example.seniorshield.feature.simulation.simulationListScreen
import com.example.seniorshield.feature.simulation.simulationPlayScreen
import com.example.seniorshield.feature.settings.settingsScreen
import com.example.seniorshield.feature.splash.splashScreen
import com.example.seniorshield.feature.warning.warningScreen

@Composable
fun SeniorShieldNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = SeniorShieldDestination.SPLASH) {

        splashScreen(onNavigate = { route ->
            navController.navigate(route) {
                popUpTo(SeniorShieldDestination.SPLASH) { inclusive = true }
                launchSingleTop = true
            }
        })

        onboardingScreen(onComplete = {
            navController.navigate("${SeniorShieldDestination.PERMISSIONS}?fromOnboarding=true") {
                popUpTo(SeniorShieldDestination.ONBOARDING) { inclusive = true }
                launchSingleTop = true
            }
        })

        homeScreen(
            onNavigateHistory = { navController.navigate(SeniorShieldDestination.HISTORY) },
            onNavigateWarning = { navController.navigate(SeniorShieldDestination.WARNING) },
            onNavigatePermissions = { navController.navigate(SeniorShieldDestination.PERMISSIONS) },
            onNavigatePolicy = { navController.navigate(SeniorShieldDestination.POLICY) },
            onNavigateSettings = { navController.navigate(SeniorShieldDestination.SETTINGS) },
            onNavigateSimulation = { navController.navigate(SeniorShieldDestination.SIMULATION_LIST) },
        )

        historyScreen(onBack = { navController.popBackStack() })
        warningScreen(
            onBack = { navController.popBackStack() },
            onNavigateGuardian = { navController.navigate(SeniorShieldDestination.GUARDIAN) },
        )
        permissionsScreen(
            onBack = { navController.popBackStack() },
            onNavigateHome = {
                navController.navigate(SeniorShieldDestination.HOME) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
        policyScreen(onBack = { navController.popBackStack() })
        guardianScreen(onBack = { navController.popBackStack() })
        simulationListScreen(
            onBack = { navController.popBackStack() },
            onSelectScenario = { scenarioId ->
                navController.navigate("${SeniorShieldDestination.SIMULATION_PLAY}/$scenarioId")
            },
        )
        simulationPlayScreen(onBack = { navController.popBackStack() })
        settingsScreen(
            onBack = { navController.popBackStack() },
            onNavigatePolicy = { navController.navigate(SeniorShieldDestination.POLICY) },
            onNavigateGuardian = { navController.navigate(SeniorShieldDestination.GUARDIAN) },
        )
    }
}
