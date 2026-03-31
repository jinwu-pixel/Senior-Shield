package com.example.seniorshield.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    secondary = BlueSecondary,
    tertiary = StatusGreen, // SuccessGreen -> StatusGreen 으로 수정
    background = GrayBackground,
)

private val DarkColors = darkColorScheme(
    primary = BlueSecondary,
    secondary = BluePrimary,
    tertiary = StatusGreen, // SuccessGreen -> StatusGreen 으로 수정
)

@Composable
fun SeniorShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
