package com.example.seniorshield.feature.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.seniorshield.core.designsystem.component.PermissionStatusItem
import com.example.seniorshield.core.designsystem.component.PrimaryButton
import com.example.seniorshield.core.designsystem.component.SeniorShieldScaffold
import com.example.seniorshield.core.navigation.SeniorShieldDestination
import com.example.seniorshield.domain.model.PermissionStatus
import com.example.seniorshield.domain.model.PermissionType

fun NavGraphBuilder.permissionsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = {},
) {
    composable(
        route = "${SeniorShieldDestination.PERMISSIONS}?fromOnboarding={fromOnboarding}",
        arguments = listOf(
            navArgument("fromOnboarding") {
                type = NavType.BoolType
                defaultValue = false
            }
        ),
    ) { backStackEntry ->
        val fromOnboarding = backStackEntry.arguments?.getBoolean("fromOnboarding") ?: false
        val viewModel: PermissionsViewModel = hiltViewModel()
        val items by viewModel.items.collectAsStateWithLifecycle()

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        PermissionsContent(
            items = items,
            fromOnboarding = fromOnboarding,
            onBack = if (fromOnboarding) onNavigateHome else onBack,
            onRefresh = { viewModel.refresh() },
            onNavigateHome = onNavigateHome,
        )
    }
}

@Composable
private fun PermissionsContent(
    items: List<PermissionStatus>,
    fromOnboarding: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val context = LocalContext.current
    val allGranted = items.all { it.granted }
    val ungrantedCount = items.count { !it.granted }

    val runtimePermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefresh()
        openNextSettingsPermission(context, items)
    }

    SeniorShieldScaffold(
        title = if (fromOnboarding) "권한 설정" else "권한 설정 및 안내",
        onBackClick = if (fromOnboarding) null else onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                PermissionStatusItem(
                    item = item,
                    onAction = if (!item.granted) {
                        {
                            when (item.type) {
                                PermissionType.USAGE_ACCESS -> context.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                )
                                PermissionType.OVERLAY -> context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                                else -> batchLauncher.launch(runtimePermissions.toTypedArray())
                            }
                        }
                    } else null,
                )
            }
            if (!allGranted) {
                PrimaryButton(
                    text = "모두 허용하기 (${ungrantedCount}개 남음)",
                    onClick = { batchLauncher.launch(runtimePermissions.toTypedArray()) },
                )
            }
            if (fromOnboarding) {
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryButton(
                    text = if (allGranted) "시작하기" else "나중에 설정하기",
                    onClick = onNavigateHome,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }
}

private fun openNextSettingsPermission(context: Context, items: List<PermissionStatus>) {
    val usageItem = items.find { it.type == PermissionType.USAGE_ACCESS }
    if (usageItem != null && !usageItem.granted) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        return
    }
    val overlayItem = items.find { it.type == PermissionType.OVERLAY }
    if (overlayItem != null && !overlayItem.granted) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }
}
