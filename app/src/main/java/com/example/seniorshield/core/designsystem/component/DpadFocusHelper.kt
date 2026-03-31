package com.example.seniorshield.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * D-pad(NAVI 키) 포커스 시 시각적 하이라이트를 표시하는 Modifier.
 * MIVE 스타일폴더 2 등 하드키 단말에서 현재 포커스 위치를 명확히 보여준다.
 */
@Composable
fun Modifier.dpadFocusHighlight(
    shape: Shape = RoundedCornerShape(8.dp),
    borderWidth: Dp = 3.dp,
    focusedColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    var hasFocus by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (hasFocus) focusedColor else Color.Transparent,
        label = "dpadFocus",
    )
    return this
        .onFocusChanged { hasFocus = it.hasFocus }
        .border(width = borderWidth, color = borderColor, shape = shape)
}
