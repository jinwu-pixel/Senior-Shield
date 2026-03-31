package com.example.seniorshield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.seniorshield.core.designsystem.theme.SeniorShieldTheme
import com.example.seniorshield.core.navigation.SeniorShieldNavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeniorShieldTheme {
                Surface(modifier = Modifier) {
                    SeniorShieldNavGraph()
                }
            }
        }
    }
}