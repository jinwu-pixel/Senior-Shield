package com.example.seniorshield.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 시니어 사용자를 위해 가독성을 높인 타이포그래피 시스템
val AppTypography = Typography(
    // 경고 화면 핵심 제목 (28sp)
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    // 일반 화면 메인 제목 (24sp)
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    // 카드 등 섹션 제목 (20sp)
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // 버튼 텍스트 (18sp)
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    // 본문 텍스트 (17sp)
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp, // 줄 간격 확보
        letterSpacing = 0.5.sp
    ),
    // 보조 설명 텍스트 (15sp)
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp, // 줄 간격 확보
        letterSpacing = 0.25.sp
    ),
    // 버튼, 라벨 등에 사용
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    )
)
