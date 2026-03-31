package com.example.seniorshield.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme

private val buttonModifier = Modifier
    .fillMaxWidth()
    .height(56.dp) // 시니어 친화적 터치 영역

/**
 * 가장 중요한 행동을 위한 버튼 (예: 시작하기, 가족에게 연락)
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.then(buttonModifier),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 두 번째로 중요한 행동을 위한 버튼 (예: 공식 기관 확인)
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.then(buttonModifier),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 중요도가 가장 낮은 행동을 위한 버튼 (예: 닫기)
 */
@Composable
fun BasicTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(48.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}


@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ButtonsPreview() {
    SeniorShieldTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            PrimaryButton(text = "Primary Button", onClick = {})
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "Secondary Button", onClick = {})
            Spacer(Modifier.height(8.dp))
            BasicTextButton(text = "Text Button", onClick = {})
        }
    }
}
