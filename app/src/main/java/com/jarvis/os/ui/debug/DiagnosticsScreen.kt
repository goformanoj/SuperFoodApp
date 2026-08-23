package com.jarvis.os.ui.debug

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jarvis.os.debug.Check
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.debug.Diagnostics
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * The debugging surface: self-checks, a typed command box that drives the whole
 * pipeline without the microphone, and an exportable trace of what actually
 * happened. Built so a problem can be reported as facts rather than "it didn't
 * work".
 */
@Composable
fun DiagnosticsScreen(onSubmitCommand: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumping this re-reads the checks and the log.
    var refresh by remember { mutableIntStateOf(0) }
    var command by remember { mutableStateOf("") }
    var pinging by remember { mutableStateOf(false) }

    val checks = remember(refresh) { Diagnostics.run(context) }
    val entries = remember(refresh) { DebugLog.snapshot().reversed() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Text(
            "Self-checks, a typed command box, and the trace to share when something breaks.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        SectionTitle("Checks")
        checks.forEach { CheckRow(it) }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (pinging) "Testing…" else "Test AI", enabled = !pinging) {
                pinging = true
                scope.launch {
                    val result = Diagnostics.pingBrain()
                    DebugLog.log(
                        if (result.ok) DebugLog.Stage.REPLY else DebugLog.Stage.ERROR,
                        "AI round-trip: ${result.detail}",
                    )
                    pinging = false
                    refresh++
                }
            }
            ActionButton("Refresh") { refresh++ }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Typed command")
        Text(
            "Runs the full pipeline — brain, markers, screen actions — without the microphone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. show me a standup comedy video", color = TextSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = JarvisTheme.accent,
                unfocusedBorderColor = JarvisTheme.glassBorder,
                cursorColor = JarvisTheme.accent,
            ),
        )
        Spacer(Modifier.height(8.dp))
        ActionButton("Send", enabled = command.isNotBlank()) {
            onSubmitCommand(command.trim())
            command = ""
            refresh++
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Trace (${entries.size} entries, newest first)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Share") {
                val text = DebugLog.export(header = checks.joinToString("\n") { "${if (it.ok) "OK  " else "FAIL"}  ${it.name}: ${it.detail}" })
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "JARVIS diagnostics")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share JARVIS log"))
            }
            ActionButton("Clear") {
                DebugLog.clear()
                refresh++
            }
        }

        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Text(
                "Nothing yet — say something to JARVIS, or send a typed command above.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(JarvisTheme.glass)
                    .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.forEach { entry ->
                    Text(
                        text = "${entry.stage.label}  ${entry.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (entry.stage == DebugLog.Stage.ERROR) ErrorRed else TextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = JarvisTheme.accent,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun CheckRow(check: Check) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (check.ok) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (check.ok) SuccessGreen else ErrorRed,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(check.name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = JarvisTheme.accent,
            contentColor = Background,
            disabledContainerColor = JarvisTheme.glassBorder,
            disabledContentColor = TextSecondary,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
