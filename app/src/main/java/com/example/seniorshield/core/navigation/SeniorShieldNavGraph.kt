package com.example.seniorshield.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.example.seniorshield.feature.guardian.guardianAddScreen
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
import kotlinx.coroutines.flow.collect

@Composable
fun SeniorShieldNavGraph(navigationEventBus: NavigationEventBus) {
    val navController = rememberNavController()

    LaunchedEffect(navController, navigationEventBus) {
        // 오버레이 뒤로가기 등 인프라 레이어의 pop 요청을 수신. Home이 backstack에 있으면
        // 그 위 destination들을 모두 pop하고 Home으로 복귀. 없으면 popBackStack이 false 반환 (무해).
        navigationEventBus.popToHomeEvents.collect {
            navController.popBackStack(SeniorShieldDestination.HOME, inclusive = false)
        }
    }

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
            onNavigateWarning = {
                // 동일 세션에서 notification escalation이 HIGH→CRITICAL로 이어지면 navigateToWarning가
                // 서로 다른 event id로 2회 emit되어 Warning이 backstack에 중복 push된다.
                // launchSingleTop으로 top 인스턴스를 재사용해 Warning stack 최대 1개를 보장.
                navController.navigate(SeniorShieldDestination.WARNING) {
                    launchSingleTop = true
                }
            },
            onNavigatePermissions = { navController.navigate(SeniorShieldDestination.PERMISSIONS) },
            onNavigatePolicy = { navController.navigate(SeniorShieldDestination.POLICY) },
            onNavigateSettings = { navController.navigate(SeniorShieldDestination.SETTINGS) },
            onNavigateSimulation = { navController.navigate(SeniorShieldDestination.SIMULATION_LIST) },
            onNavigateGuardian = { navController.navigate(SeniorShieldDestination.GUARDIAN) },
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
        guardianScreen(
            onBack = { navController.popBackStack() },
            onNavigateAdd = {
                navController.navigate(SeniorShieldDestination.GUARDIAN_ADD) {
                    launchSingleTop = true
                }
            },
        )
        guardianAddScreen(
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
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
