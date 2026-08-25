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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.data.AnswerLength
import com.jarvis.os.data.Instructions
import com.jarvis.os.data.InstructionsDraft
import com.jarvis.os.data.Tone
import com.jarvis.os.ui.components.JarvisButton
import com.jarvis.os.ui.components.JarvisCard
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.components.SectionLabel
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

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
    // The draft, not the string. The screen edits answers; the string is composed
    // from them on save. See `InstructionsDraft` for why that is the right shape.
    var draft by remember(initial) { mutableStateOf(Instructions.parse(initial)) }
    var saved by remember(initial) { mutableStateOf(true) }
    fun edit(change: (InstructionsDraft) -> InstructionsDraft) {
        draft = change(draft)
        saved = false
    }

    val composed = Instructions.compose(draft)
    val overLimit = composed.length > UserPreferences.MAX_INSTRUCTIONS

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Instructions",
            subtitle = "How JARVIS should talk to you. Sent with every message, so keep it short.",
        )

        // ── What to call you ────────────────────────────────────────────────
        SectionLabel("What should JARVIS call you?")
        JarvisCard {
            OutlinedTextField(
                value = draft.callMe,
                onValueChange = { name -> edit { it.copy(callMe = name.take(40)) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("sir, boss, your name…", color = TextSecondary) },
                colors = fieldColours(),
            )
        }

        // ── How long ────────────────────────────────────────────────────────
        //
        // Chips, not sentences to paste. The old screen offered a menu of
        // pre-written lines to append into a textarea, which was the design
        // admitting people would not know what to write — and then answering that
        // with copy-and-paste. Almost everything anyone wants here is one of a
        // handful of choices, so they are offered as choices.
        SectionLabel("How long should answers be?")
        ChipRow(
            options = AnswerLength.entries.map { it.label },
            selected = draft.length?.label,
            onSelect = { label ->
                val picked = AnswerLength.entries.firstOrNull { it.label == label }
                // Tapping the selected chip clears it — there must be a way back
                // to "no preference", or the first tap is permanent.
                edit { d -> d.copy(length = if (d.length == picked) null else picked) }
            },
        )

        // ── What tone ───────────────────────────────────────────────────────
        SectionLabel("What tone?")
        ChipRow(
            options = Tone.entries.map { it.label },
            selected = draft.tone?.label,
            onSelect = { label ->
                val picked = Tone.entries.firstOrNull { it.label == label }
                edit { d -> d.copy(tone = if (d.tone == picked) null else picked) }
            },
        )

        // ── Anything else ───────────────────────────────────────────────────
        SectionLabel(
            text = "Anything else",
            trailing = "${composed.length} / ${UserPreferences.MAX_INSTRUCTIONS}",
        )
        JarvisCard {
            OutlinedTextField(
                value = draft.extra,
                onValueChange = { more -> edit { it.copy(extra = more) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text(
                        "Nicknames for apps, where you live, anything standing.\n" +
                            "e.g. When I say \"cloud\" I mean Claude.",
                        color = TextSecondary,
                    )
                },
                colors = fieldColours(),
            )
        }

        Spacer(Modifier.height(16.dp))
        JarvisButton(
            text = if (saved) "Saved" else "Save",
            onClick = {
                onSave(composed)
                saved = true
            },
            // Nothing to save, or too long to send. A primary action that is
            // always live invites a tap that does nothing.
            enabled = !saved && !overLimit,
            modifier = Modifier.fillMaxWidth(),
        )
        if (overLimit) {
            Spacer(Modifier.height(8.dp))
            Text(
                "That is longer than JARVIS can carry on every message. Trim it a little.",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
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

        Spacer(Modifier.height(24.dp))
        Text(
            "Instructions guide JARVIS but cannot override acting safely or truthfully.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(40.dp))
    }
}

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

/**
 * A row of mutually-exclusive choices.
 *
 * The screen asks questions now, and a question with three possible answers
 * should be three things you can tap — not a sentence to copy into a box. Tapping
 * the selected one clears it, because "no preference" has to be reachable or the
 * first tap is permanent.
 */
@Composable
private fun ChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            val on = option == selected
            val accent = JarvisTheme.accent
            Text(
                text = option,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) accent else TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) accent.copy(alpha = 0.14f) else JarvisTheme.card)
                    .border(
                        1.dp,
                        if (on) accent else JarvisTheme.cardBorder,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 11.dp),
            )
        }
    }
}

/** One set of text-field colours, so the two fields on this screen match. */
@Composable
private fun fieldColours() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedBorderColor = JarvisTheme.accent,
    unfocusedBorderColor = JarvisTheme.cardBorder,
    cursorColor = JarvisTheme.accent,
)
