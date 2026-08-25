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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.data.AnswerLength
import com.jarvis.os.data.Instructions
import com.jarvis.os.data.InstructionsDraft
import com.jarvis.os.data.Tone
import com.jarvis.os.ui.components.JarvisButton
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
    var draft by remember(initial) { mutableStateOf(Instructions.parse(initial)) }
    var saved by remember(initial) { mutableStateOf(true) }
    // Which row is open. One at a time: an accordion where everything can be open
    // is a form again, which is what this screen was.
    var openRow by remember { mutableStateOf<Field?>(null) }
    fun edit(change: (InstructionsDraft) -> InstructionsDraft) {
        draft = change(draft)
        saved = false
    }

    val composed = Instructions.compose(draft)
    val overLimit = composed.length > UserPreferences.MAX_INSTRUCTIONS

    Column(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ScreenHeader(title = "Instructions")

            // WHAT JARVIS ACTUALLY GETS TOLD, in one sentence.
            //
            // The screen was four questions in four boxes, and answering them
            // still left the user to assemble in their head what any of it did.
            // The outcome is the thing worth showing, and showing it change as a
            // choice is made does more than any label above a field.
            SummaryCard(Instructions.summary(draft))

            Spacer(Modifier.height(22.dp))

            // COMPACT ROWS THAT OPEN, not four stacked fields.
            //
            // Each shows its own value, so the whole state is readable at a
            // glance without scrolling through inputs — the same shape as the
            // Settings index, which reads well. Only the row being changed shows
            // a control, so the screen is a list rather than a form.
            FieldRow(
                label = "Calls you",
                value = draft.callMe.ifBlank { "Not set" },
                open = openRow == Field.Name,
                onToggle = { openRow = if (openRow == Field.Name) null else Field.Name },
            ) {
                BareField(
                    value = draft.callMe,
                    onValueChange = { name -> edit { it.copy(callMe = name.take(40)) } },
                    placeholder = "sir, boss, your name…",
                    singleLine = true,
                )
            }
            FieldRow(
                label = "Answer length",
                value = draft.length?.label ?: "No preference",
                open = openRow == Field.Length,
                onToggle = { openRow = if (openRow == Field.Length) null else Field.Length },
            ) {
                ChipRow(AnswerLength.entries.map { it.label }, draft.length?.label) { label ->
                    val picked = AnswerLength.entries.firstOrNull { it.label == label }
                    edit { d -> d.copy(length = if (d.length == picked) null else picked) }
                }
            }
            FieldRow(
                label = "Tone",
                value = draft.tone?.label ?: "No preference",
                open = openRow == Field.Tone,
                onToggle = { openRow = if (openRow == Field.Tone) null else Field.Tone },
            ) {
                ChipRow(Tone.entries.map { it.label }, draft.tone?.label) { label ->
                    val picked = Tone.entries.firstOrNull { it.label == label }
                    edit { d -> d.copy(tone = if (d.tone == picked) null else picked) }
                }
            }
            FieldRow(
                label = "Notes",
                // The count lives on the row it belongs to. It used to sit beside
                // a section heading, where it looked like a heading of its own.
                value = if (draft.extra.isBlank()) "None" else
                    "${composed.length} / ${UserPreferences.MAX_INSTRUCTIONS}",
                open = openRow == Field.Notes,
                onToggle = { openRow = if (openRow == Field.Notes) null else Field.Notes },
            ) {
                BareField(
                    value = draft.extra,
                    onValueChange = { more -> edit { it.copy(extra = more) } },
                    placeholder = "Nicknames for apps, where you live, anything standing.",
                    singleLine = false,
                )
            }

            if (overLimit) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "That is longer than JARVIS can carry on every message. Trim it a little.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                )
            }

            // WHAT JARVIS WORKED OUT ON ITS OWN.
            //
            // Shown even when empty, which it was not before. This is the only
            // place a user can see what the assistant believes about them, and a
            // section that hides itself until it has content means nobody
            // discovers it exists until it already knows something.
            LearnedSection(learned = learned, onForget = onForget)

            Spacer(Modifier.height(24.dp))
            Text(
                "Instructions guide JARVIS but cannot override acting safely or truthfully.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(24.dp))
        }

        // SAVE IS PINNED, not the last thing in a scroll.
        //
        // A screenshot caught it half under the gesture bar: it was at the bottom
        // of a scrolling column, so how much of it you could see depended on where
        // you had scrolled to. The one action a screen exists for should not have
        // to be hunted for.
        Column(
            Modifier
                .background(JarvisTheme.card)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            JarvisButton(
                text = if (saved) "Saved" else "Save",
                onClick = {
                    onSave(composed)
                    saved = true
                },
                enabled = !saved && !overLimit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The four things this screen can change. */
private enum class Field { Name, Length, Tone, Notes }

/**
 * Everything JARVIS has worked out about you, and a way to take any of it back.
 *
 * This is a **privacy surface** before it is a settings one: it is the only place
 * the assistant's beliefs about a person are visible. Three things follow from
 * that, and none of them were true before.
 *
 * It is shown when **empty**, with a line explaining what will appear. A section
 * that hides until it has content means nobody finds out it exists until the
 * assistant already knows something about them, which is exactly backwards.
 *
 * It carries a **count**, because "how much does it know about me" is the first
 * question anyone has here and scrolling to answer it is a poor substitute.
 *
 * And removal is a **quiet icon**, not a red-bordered word on every row. The old
 * one made a list of harmless facts look like a list of warnings — and it was the
 * same fault as the "On"/"Off" pills: a bordered word that looks like a button
 * without saying what it does.
 */
@Composable
private fun LearnedSection(learned: List<String>, onForget: (String) -> Unit) {
    SectionLabel(
        text = "What JARVIS has picked up",
        trailing = if (learned.isEmpty()) null else "${learned.size}",
    )
    Text(
        text = if (learned.isEmpty()) {
            "Nothing yet. As you talk, JARVIS notes standing things — what to call " +
                "you, a nickname for an app — and they appear here for you to remove."
        } else {
            "Learned from your conversations, not from anything you typed. Remove " +
                "anything that is wrong or that you would rather it did not keep."
        },
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    learned.forEach { fact ->
        LearnedFactRow(fact = fact, onForget = { onForget(fact) })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LearnedFactRow(fact: String, onForget: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, JarvisTheme.cardBorder, shape)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
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
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        )
        // A 40dp target around a 18dp glyph. The old control was a bordered word
        // with 6dp of padding — under Android's minimum, beside body text, and
        // destructive.
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Forget: $fact",
            tint = TextSecondary,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onForget() }
                .padding(11.dp),
        )
    }
}

/**
 * What JARVIS has been told, in one line.
 *
 * The first thing on the screen, and the reason the rest can be compact: with the
 * outcome stated, the rows below only have to be *changeable*, not
 * self-explanatory.
 */
@Composable
private fun SummaryCard(summary: String) {
    val accent = JarvisTheme.accent
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), shape)
            .padding(16.dp),
    ) {
        Text(
            text = if (summary.isEmpty()) {
                "Nothing set yet. JARVIS will answer however it thinks best."
            } else {
                "JARVIS will $summary"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (summary.isEmpty()) TextSecondary else TextPrimary,
        )
    }
}

/**
 * One setting: its name, its current value, and its control only when opened.
 *
 * The screen was four labelled inputs stacked down the page — a form, and it read
 * as one. This shows every value at a glance in four short rows and reveals a
 * control only for the row being changed, which is the difference between a list
 * you scan and a form you fill in.
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, if (open) JarvisTheme.accent else JarvisTheme.cardBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (open) JarvisTheme.accent else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

/**
 * A text field with ONE border.
 *
 * The fields used to sit inside a `JarvisCard`, which draws its own — so every
 * input was a bordered box inside a bordered box, plainly visible in a
 * screenshot. Here the row already provides the container, so the field brings no
 * decoration of its own at all.
 */
@Composable
private fun BareField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier else Modifier.height(140.dp)),
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        placeholder = { Text(placeholder, color = TextSecondary) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = JarvisTheme.accent,
            unfocusedBorderColor = JarvisTheme.cardBorder,
            cursorColor = JarvisTheme.accent,
        ),
    )
}

/**
 * A row of mutually-exclusive choices.
 *
 * Tapping the selected one clears it, because "no preference" has to be reachable
 * or the first tap is permanent.
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
                    .background(if (on) accent.copy(alpha = 0.16f) else Color.Transparent)
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
