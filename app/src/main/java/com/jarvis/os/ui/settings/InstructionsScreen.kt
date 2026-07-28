package com.jarvis.os.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

private val EXAMPLES = listOf(
    "Call me sir.",
    "Keep answers to one sentence unless I ask for detail.",
    "I'm in Bangalore — assume IST for times.",
    "Never send a message without reading it back to me first.",
    "When I ask for music, use YouTube rather than anything else.",
)

/**
 * Standing instructions that shape every reply, not just the next one.
 *
 * These ride on every request, which is why the length is capped and said out
 * loud: an essay here is a permanent tax on latency and cost.
 */
@Composable
fun InstructionsScreen(
    initial: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Custom instructions", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Text(
            "Standing preferences JARVIS follows in every conversation — how to address " +
                "you, how much detail you want, anything it should always or never do.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = text,
            onValueChange = {
                if (it.length <= UserPreferences.MAX_INSTRUCTIONS) {
                    text = it
                    saved = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            placeholder = { Text("e.g. Call me sir. Keep replies short.", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = GlassBorder,
                cursorColor = Cyan,
            ),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "${text.length} / ${UserPreferences.MAX_INSTRUCTIONS}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (saved) {
                Text("Saved", style = MaterialTheme.typography.bodySmall, color = Cyan)
                Spacer(Modifier.padding(horizontal = 6.dp))
            }
            Button(
                onClick = {
                    onSave(text)
                    saved = true
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Background),
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("Tap to add", style = MaterialTheme.typography.labelLarge, color = Cyan)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { example ->
                Text(
                    text = example,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceGlass)
                        .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            val addition = if (text.isBlank()) example else "\n$example"
                            if (text.length + addition.length <= UserPreferences.MAX_INSTRUCTIONS) {
                                text += addition
                                saved = false
                            }
                        }
                        .padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "These are sent with every message, so keep them brief. They guide JARVIS but " +
                "cannot override acting safely or truthfully.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(40.dp))
    }
}
