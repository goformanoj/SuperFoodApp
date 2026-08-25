package com.jarvis.os.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.ui.components.EmptyState
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/** Terminal-style conversation history. */
@Composable
fun ChatScreen(messages: List<ChatTurn>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Chat",
            subtitle = "Everything you and JARVIS have said. Stays on this phone.",
            action = {
                if (messages.isNotEmpty()) {
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

        if (messages.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Forum,
                title = "No conversation yet",
                line = "Say \"Hey JARVIS\", or tap the orb on the home screen.",
                // Bounded here: this Column is not scrollable, so the empty state
                // can take the remaining space and centre in it.
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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
}

/**
 * One turn, as a message rather than as a log line.
 *
 * It used to be a label over unboxed text, left-aligned for both speakers — which
 * is what a debug transcript looks like, and the reason this screen felt like a
 * developer tool. Two things do almost all the work of making it read as a
 * conversation: the two sides are **shaped differently**, and they **sit on
 * different edges**. Everything else here is detail.
 *
 * The user's turn is a filled block on the right; JARVIS answers unboxed on the
 * left in the accent, because the assistant's voice is the content of the screen
 * and boxing both sides equally makes neither one lead.
 */
@Composable
private fun ChatBubble(turn: ChatTurn) {
    val isUser = turn.role == ChatTurn.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            // Never the full width: a bubble that reaches both edges stops
            // reading as a bubble, and the ragged right margin is most of what
            // says "someone said this".
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
                Text(
                    text = turn.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp))
                        .background(JarvisTheme.glass)
                        .border(
                            BorderStroke(1.dp, JarvisTheme.glassBorder),
                            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                        )
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
