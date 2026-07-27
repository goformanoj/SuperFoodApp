package com.jarvis.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
    private var permissionsAsked = false

    // One launcher for all permissions — requesting mic and calendar in a single
    // sequence avoids the second request being dropped while the first dialog shows.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
            engine.onMicPermission(micOk)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = AssistantEngine(applicationContext)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    val state by engine.state
                    JarvisApp(
                        state = state,
                        onClearChat = { engine.clearConversation() },
                        onSubmitCommand = { engine.submitText(it) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.resume()
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) engine.onMicPermission(true)
        requestMissingPermissions()
    }

    override fun onStop() {
        super.onStop()
        engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED.filter { !hasPermission(it) }
        if (missing.isNotEmpty() && !permissionsAsked) {
            permissionsAsked = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val REQUIRED: Array<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            // Android 13+ needs this for the work-session notification to be seen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
}
