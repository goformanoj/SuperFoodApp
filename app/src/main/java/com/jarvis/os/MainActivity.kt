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

    private var calendarAsked = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            engine.onMicPermission(granted)
        }

    // Result unused: CalendarWriter checks the permission when it needs it, and
    // falls back to opening the calendar app if it was denied.
    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = AssistantEngine(applicationContext)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    val state by engine.state
                    JarvisApp(state = state, onClearChat = { engine.clearConversation() })
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
        maybeRequestCalendar()
    }

    private fun maybeRequestCalendar() {
        if (calendarAsked) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            calendarAsked = true
            calendarPermission.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            )
        }
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
