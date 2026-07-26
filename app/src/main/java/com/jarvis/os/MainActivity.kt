package com.jarvis.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jarvis.os.ui.home.HomeScreen
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisApp()
        }
    }
}

@Composable
private fun JarvisApp() {
    JarvisTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            HomeScreen()
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF050B18)
@Composable
private fun JarvisAppPreview() {
    JarvisApp()
}
