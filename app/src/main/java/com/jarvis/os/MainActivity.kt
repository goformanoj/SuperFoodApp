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
import com.jarvis.os.ui.home.VoiceHome
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.voice.VoiceController

class MainActivity : ComponentActivity() {

    private lateinit var controller: VoiceController

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.start() else controller.onPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = VoiceController(applicationContext)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    val state by controller.state
                    VoiceHome(state = state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start listening as soon as the app is visible; ask for the mic once if needed.
        if (hasMicPermission()) {
            controller.start()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        controller.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.destroy()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
