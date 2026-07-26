package com.jarvis.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jarvis.os.assistant.AssistantEngine
import com.jarvis.os.ui.home.JarvisApp
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private lateinit var engine: AssistantEngine

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            engine.onMicPermission(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = AssistantEngine(applicationContext)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    val state by engine.state
                    JarvisApp(state = state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val granted = hasMicPermission()
        engine.onMicPermission(granted)
        engine.resume()
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStop() {
        super.onStop()
        engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
