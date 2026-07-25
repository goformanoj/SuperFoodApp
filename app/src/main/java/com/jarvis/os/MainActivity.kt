package com.jarvis.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp

// JARVIS brand colors
private val JarvisBackground = Color(0xFF050B18)
private val JarvisCyan = Color(0xFF00D4FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisRoot()
        }
    }
}

@Composable
private fun JarvisRoot() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "JARVIS",
            color = JarvisCyan,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JarvisRootPreview() {
    JarvisRoot()
}
