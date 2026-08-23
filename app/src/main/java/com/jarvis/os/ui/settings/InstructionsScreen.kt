package com.jarvis.os.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SuccessGreen
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
 *
 * The screen is three clearly separated blocks: the editor you own, the facts
 * JARVIS learned on its own (each removable), and one-tap examples to append.
 * The three used to look alike — bordered grey boxes stacked in a scroll — so it
 * was hard to tell what was editable, what was a suggestion, and what removing a
 * card would do. Now each block reads as what it is.
 */
@Composable
fun InstructionsScreen(
    initial: String,
    learned: List<String> = emptyList(),
    onSave: (String) -> Unit,
    onForget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var saved by remember(initial) { mutableStateOf(false) }

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
            "What JARVIS always knows about you — nicknames for apps, what to call you, how you " +
                "like things done. Sent with every message, so keep it brief.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
        )

        // --- The editor you own -------------------------------------------------
        SectionLabel("Your instructions", "${text.length} / ${UserPreferences.MAX_INSTRUCTIONS}")
        Spacer(Modifier.height(10.dp))
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
                .height(180.dp),
            shape = RoundedCornerShape(14.dp),
            placeholder = {
                Text(
                    "e.g. Call me sir.\nKeep replies short.\nWhen I say \"music\" I mean Spotify.",
                    color = TextSecondary,
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = JarvisTheme.glass,
                unfocusedContainerColor = JarvisTheme.glass,
                focusedBorderColor = JarvisTheme.accent,
                unfocusedBorderColor = JarvisTheme.glassBorder,
                cursorColor = JarvisTheme.accent,
            ),
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                onSave(text)
                saved = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (saved) SuccessGreen else JarvisTheme.accent,
                contentColor = Background,
            ),
        ) {
            Text(
                if (saved) "Saved ✓" else "Save",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // --- What JARVIS learned on its own ------------------------------------
        if (learned.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            SectionLabel("What JARVIS has picked up")
            Text(
                "Learned from your conversations. Remove anything that's wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                learned.forEach { fact ->
                    LearnedFactRow(fact = fact, onForget = { onForget(fact) })
                }
            }
        }

        // --- One-tap examples to append ----------------------------------------
        Spacer(Modifier.height(36.dp))
        SectionLabel("Add a common one")
        Text(
            "Tap to append it to your instructions above.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EXAMPLES.forEach { example ->
                ExampleRow(
                    example = example,
                    onAdd = {
                        val addition = if (text.isBlank()) example else "\n$example"
                        if (text.length + addition.length <= UserPreferences.MAX_INSTRUCTIONS) {
                            text += addition
                            saved = false
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Instructions guide JARVIS but cannot override acting safely or truthfully.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(40.dp))
    }
}

/** A small uppercase heading, with an optional right-aligned counter. */
@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = JarvisTheme.accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/** A learned fact with an explicit, destructive-looking remove control. */
@Composable
private fun LearnedFactRow(fact: String, onForget: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(JarvisTheme.accent),
        )
        Text(
            fact,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp),
        )
        Text(
            "Forget",
            style = MaterialTheme.typography.labelMedium,
            color = ErrorRed,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onForget() }
                .border(1.dp, ErrorRed.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A suggestion chip that reads as an action: a leading "+" and a cyan edge. */
@Composable
private fun ExampleRow(example: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable { onAdd() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            color = JarvisTheme.accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            example,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}
