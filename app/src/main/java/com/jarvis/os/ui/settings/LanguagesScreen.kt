package com.jarvis.os.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ui.components.SectionLabel
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.Language
import com.jarvis.os.voice.LanguagePrefs

/**
 * Pick the up-to-two languages JARVIS understands and answers in.
 *
 * Five languages, choose one or two. The FIRST chosen is the primary — the one
 * the recogniser defaults to and JARVIS falls back to — and it carries a marker
 * that says so, because "which of my two is the main one" is the only thing about
 * this screen that is not obvious from the ticks. Selecting a third when two are
 * held swaps the second rather than refusing the tap, which is what a two-slot
 * chooser should do; a tap that does nothing reads as broken.
 *
 * Each language shows its own name in its own script, so a speaker recognises it
 * without reading English — and that name is drawn in the system font on purpose,
 * because the app's display font is Latin-only and would render Devanagari or
 * Arabic as empty boxes.
 */
@Composable
fun LanguagesScreen(
    current: LanguagePrefs,
    onSelect: (LanguagePrefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Order is priority: index 0 is the primary. Held locally so the ticks move
    // the instant they are touched, and written out through [onSelect] on change.
    var chosen by remember { mutableStateOf(current.understood) }

    fun apply(next: List<Language>) {
        chosen = next
        onSelect(LanguagePrefs.of(next))
    }

    fun toggle(lang: Language) {
        apply(
            when {
                lang in chosen -> chosen - lang
                chosen.size < 2 -> chosen + lang
                // Two already held: keep the primary, swap the second.
                else -> listOf(chosen[0], lang)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        SectionLabel("Understand and reply in your languages")
        Text(
            "Choose up to two. JARVIS understands both and answers in whichever one " +
                "you spoke. The first is your main language.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 14.dp),
        )

        Language.entries.forEach { lang ->
            val rank = chosen.indexOf(lang) // -1 when not chosen, 0 = primary
            LanguageRow(
                lang = lang,
                rank = rank,
                onClick = { toggle(lang) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * One selectable language: its native name, its English name, a note when it is
 * the primary, and a tick badge on the right showing whether — and in what slot —
 * it is chosen.
 */
@Composable
private fun LanguageRow(
    lang: Language,
    rank: Int,
    onClick: () -> Unit,
) {
    val selected = rank >= 0
    val accent = JarvisTheme.accent
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else JarvisTheme.card)
            .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else JarvisTheme.cardBorder, shape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // The endonym in the system font, so non-Latin scripts actually render.
            Text(
                text = lang.endonym,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (rank == 0) "${lang.label}  ·  MAIN" else lang.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (rank == 0) accent else TextSecondary,
            )
        }
        SelectBadge(rank = rank, accent = accent)
    }
}

/** A filled tick (numbered by slot) when chosen, an empty ring when not. */
@Composable
private fun SelectBadge(rank: Int, accent: androidx.compose.ui.graphics.Color) {
    val selected = rank >= 0
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                1.5.dp,
                if (selected) accent else JarvisTheme.cardBorder,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(
                text = "${rank + 1}",
                // Dark numeral on the bright accent fill — the accents are all
                // light (cyan, copper, violet), so black is the legible choice.
                color = androidx.compose.ui.graphics.Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}
