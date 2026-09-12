package com.jarvis.os.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.memory.MemoryFormat
import com.jarvis.os.ui.components.EmptyState
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * Two things that used to be one flat log line each, now given room to be useful:
 *
 * - **Conversation** — the transcript, plus a type bar so JARVIS answers a typed
 *   question when speaking aloud is not an option (a meeting, a quiet room). The
 *   typed line runs the full pipeline, exactly as the voice path does.
 * - **Memory** — the standing facts JARVIS carries into every reply, grouped by
 *   what kind of thing they are, with the private ones (a number, an email)
 *   blurred so the screen is safe to hold up, and each removable in one tap.
 *
 * Splitting them into tabs rather than two drawer entries keeps "what was said"
 * and "what is known" next to each other — you correct a wrong memory in the same
 * place you noticed it in the conversation.
 */
@Composable
fun ChatScreen(
    messages: List<ChatTurn>,
    onClear: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    learnedFacts: () -> List<String>,
    onForgetFact: (String) -> Unit,
    onRememberFact: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Chat",
            subtitle = if (tab == 0) {
                "Everything you and JARVIS have said. Stays on this phone."
            } else {
                "What JARVIS carries into every reply. Yours to edit."
            },
            action = {
                if (tab == 0 && messages.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Clear conversation",
                        tint = TextSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onClear() }
                            .padding(6.dp)
                            .size(22.dp),
                    )
                }
            },
        )

        TabSwitch(tab) { tab = it }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> ConversationTab(messages, onSubmitCommand, Modifier.weight(1f))
            else -> MemoryTab(learnedFacts, onForgetFact, onRememberFact, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabSwitch(selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SegItem("Conversation", selected == 0, Modifier.weight(1f)) { onSelect(0) }
        SegItem("Memory", selected == 1, Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) JarvisTheme.accent else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Background else TextSecondary,
        )
    }
}

// --- Conversation ----------------------------------------------------------

@Composable
private fun ConversationTab(
    messages: List<ChatTurn>,
    onSubmitCommand: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Forum,
                    title = "No conversation yet",
                    line = "Say \"Hey JARVIS\", tap the orb, or just type below.",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    items(messages) { turn ->
                        ChatBubble(turn)
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }
        TypeBar(onSend = onSubmitCommand)
    }
}

/** A typed line into the same pipeline the microphone drives. */
@Composable
private fun TypeBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(24.dp)

    fun submit() {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            text = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    "Type to JARVIS…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                cursorBrush = SolidColor(JarvisTheme.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val enabled = text.isNotBlank()
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (enabled) JarvisTheme.accent else JarvisTheme.glassBorder)
                .clickable(enabled = enabled) { submit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send",
                tint = if (enabled) Background else TextSecondary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * One turn, as a message rather than as a log line. The two sides are shaped
 * differently and sit on different edges — the user filled and boxed on the
 * right, JARVIS unboxed in the accent on the left, because the assistant's voice
 * is the content of the screen.
 */
@Composable
private fun ChatBubble(turn: ChatTurn) {
    val isUser = turn.role == ChatTurn.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = if (isUser) "YOU" else "JARVIS",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) TextSecondary else JarvisTheme.accent,
            )
            Spacer(Modifier.height(5.dp))
            if (isUser) {
                val bubble = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
                Text(
                    text = turn.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .clip(bubble)
                        .background(JarvisTheme.glass)
                        .border(BorderStroke(1.dp, JarvisTheme.glassBorder), bubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                Text(
                    text = turn.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = JarvisTheme.accent,
                )
            }
        }
    }
}

// --- Memory ----------------------------------------------------------------

@Composable
private fun MemoryTab(
    learnedFacts: () -> List<String>,
    onForgetFact: (String) -> Unit,
    onRememberFact: (String) -> Unit,
    modifier: Modifier,
) {
    // Reading through a bumped counter so a forget or an add repaints the list
    // without the screen having to be left and re-entered.
    var refresh by remember { mutableIntStateOf(0) }
    val facts = remember(refresh) { learnedFacts() }
    val groups = remember(facts) { MemoryFormat.grouped(facts) }
    var newFact by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        AddFactRow(
            value = newFact,
            onValueChange = { newFact = it },
            onAdd = {
                val t = newFact.trim()
                if (t.isNotEmpty()) {
                    onRememberFact(t)
                    newFact = ""
                    refresh++
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        if (facts.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Bookmark,
                title = "Nothing remembered yet",
                line = "Tell JARVIS \"remember I prefer short answers\", or add a note above.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "${facts.size} of ${UserPreferences.MAX_FACTS} · added to every reply",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                groups.forEach { group ->
                    item(key = "h:${group.category.name}") {
                        Text(
                            group.category.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = JarvisTheme.accent,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                        )
                    }
                    items(group.facts, key = { "f:$it" }) { fact ->
                        FactRow(
                            fact = fact,
                            onForget = {
                                onForgetFact(fact)
                                refresh++
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddFactRow(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "Add something to remember…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(JarvisTheme.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val enabled = value.isNotBlank()
        Text(
            "Add",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Background else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (enabled) JarvisTheme.accent else JarvisTheme.glassBorder)
                .clickable(enabled = enabled) { onAdd() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun FactRow(fact: String, onForget: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val sensitive = remember(fact) { MemoryFormat.isSensitive(fact) }
    val shown = remember(fact) { MemoryFormat.masked(fact) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sensitive) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Private",
                tint = TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            shown,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Forget this",
            tint = TextSecondary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onForget() }
                .padding(4.dp)
                .size(18.dp),
        )
    }
}
