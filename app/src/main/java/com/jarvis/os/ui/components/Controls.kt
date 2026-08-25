package com.jarvis.os.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * The surface a screen's content sits on.
 *
 * Cards used to be `accent` at **7% opacity** over a full-brightness animated
 * backdrop. On a bright theme that is not a card, it is nothing — a screenshot of
 * the Forge settings screen showed rows that had simply vanished, leaving text
 * floating over moving beams. A card has to be something the content sits ON.
 */
@Composable
fun JarvisCard(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(CARD_RADIUS)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (raised) JarvisTheme.cardRaised else JarvisTheme.card)
            .border(BorderStroke(1.dp, JarvisTheme.cardBorder), shape)
            .padding(CARD_PADDING),
    ) {
        content()
    }
}

/**
 * A settings row whose control is a **switch**.
 *
 * It used to be an outlined pill reading "On" or "Off". That is wrong twice over:
 * it looks like a button, so it invites a tap without saying what will happen, and
 * it is not the control Android users already know. Every settings screen on the
 * platform uses a switch for a boolean, and matching that convention is most of
 * what separates an app that feels native from one that feels drawn.
 *
 * The whole row is the target, not just the switch — a 32dp thumb is a small
 * thing to hit, and the row is already there.
 */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    JarvisCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisTheme.background,
                    checkedTrackColor = accent,
                    checkedBorderColor = accent,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = TextSecondary,
                ),
            )
        }
    }
}

/**
 * A settings row whose control is an action — "Set up", "Choose", "Grant".
 *
 * Distinct from [SettingSwitchRow] on purpose: a row that *goes somewhere* and a
 * row that *flips something* should not look identical, and before this they did,
 * because both were an outlined pill with a word in it.
 */
@Composable
fun SettingActionRow(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    JarvisCard(modifier) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(14.dp))
            // BELOW the text, not beside it. A trailing button in the same row as
            // a two-line title squeezes the title into a column barely wider than
            // the button, which is exactly what wrapped "Open JARVIS with a
            // gesture" across three lines in a screenshot.
            JarvisButton(text = actionLabel, onClick = onAction, fill = false)
        }
    }
}

/**
 * The app's button.
 *
 * `Button` straight from Material gives a full-width flat slab with black label
 * text, which is what the Save button was — correct by the letter of the spec and
 * wrong for a dark, precise interface. This keeps Material's behaviour (ripple,
 * disabled state, minimum touch target) and fixes the three things that made it
 * look unconsidered: the height, the corner radius, and a label that speaks in
 * the app's own voice rather than the system's.
 *
 * @param fill true for the primary action on a screen; false for a secondary one.
 */
@Composable
fun JarvisButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(BUTTON_RADIUS)
    if (fill) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = JarvisTheme.background,
                disabledContainerColor = accent.copy(alpha = 0.25f),
                disabledContentColor = JarvisTheme.background.copy(alpha = 0.55f),
            ),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = modifier.defaultMinSize(minHeight = BUTTON_HEIGHT),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent,
                disabledContentColor = accent.copy(alpha = 0.35f),
            ),
            modifier = modifier.defaultMinSize(minHeight = BUTTON_HEIGHT),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The quiet uppercase heading that separates one group of rows from the next.
 *
 * Written by hand on four screens with four different paddings before it lived
 * here.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = LocalAccent.current)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
    }
}

private val CARD_RADIUS = 18.dp
private val CARD_PADDING = 18.dp
private val BUTTON_RADIUS = 14.dp

/** Android's minimum comfortable touch target, and the height every button uses. */
private val BUTTON_HEIGHT = 48.dp
