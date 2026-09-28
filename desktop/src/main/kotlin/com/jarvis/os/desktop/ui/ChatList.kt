package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.brain.Brain

/**
 * The sidebar's chats, organised (AGENT_PLAN §3): pinned first, then one collapsible
 * group per project, then everything else, with archived chats folded at the bottom.
 * Every chat has a ⋯ menu — pin, move to a project (or a new one), archive, delete.
 * Delete is the one step that can't be undone, so it asks first.
 */
@Composable
fun ChatList(a: DesktopAssistant, screen: Screen, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var showArchived by remember { mutableStateOf(false) }
    var newProjectFor by remember { mutableStateOf<String?>(null) }   // conversation to move into it
    var confirmDelete by remember { mutableStateOf<Brain.Conversation?>(null) }

    val pinned = a.conversations.filter { it.pinned }
    val rest = a.conversations.filterNot { it.pinned }
    val byProject = rest.groupBy { it.projectId }
    val ungrouped = byProject[null].orEmpty()

    @Composable
    fun item(c: Brain.Conversation) = ChatItem(
        a, c,
        selected = screen == Screen.Chat && c.id == a.activeId,
        onOpen = { onOpen(c.id) },
        onNewProject = { newProjectFor = c.id },
        onDelete = { confirmDelete = c },
    )

    LazyColumn(modifier.padding(horizontal = 10.dp)) {
        if (a.conversations.isEmpty() && a.archived.isEmpty()) {
            item { Text("Your chats will appear here.", color = J.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
        if (pinned.isNotEmpty()) {
            item(key = "h-pinned") { GroupHeader("Pinned", null, expanded = true, onToggle = null) }
            items(pinned, key = { "p-" + it.id }) { item(it) }
        }
        a.projects.forEach { p ->
            val chats = byProject[p.id].orEmpty()
            val open = p.id !in collapsed
            item(key = "h-" + p.id) {
                GroupHeader(
                    "${p.name} · ${chats.size}", Icons.Outlined.Folder, expanded = open,
                    onToggle = { collapsed = if (open) collapsed + p.id else collapsed - p.id },
                    onRemove = { a.deleteProject(p.id) },
                )
            }
            if (open) items(chats, key = { "c-" + it.id }) { item(it) }
        }
        if (ungrouped.isNotEmpty()) {
            item(key = "h-recent") { GroupHeader("Recent", null, expanded = true, onToggle = null) }
            items(ungrouped, key = { "u-" + it.id }) { item(it) }
        }
        if (a.archived.isNotEmpty()) {
            item(key = "h-archived") {
                GroupHeader("Archived · ${a.archived.size}", null, expanded = showArchived, onToggle = { showArchived = !showArchived })
            }
            if (showArchived) items(a.archived, key = { "a-" + it.id }) { item(it) }
        }
    }

    newProjectFor?.let { convId ->
        NewProjectDialog(
            onCreate = { name -> a.createProject(name)?.let { a.moveToProject(convId, it.id) }; newProjectFor = null },
            onDismiss = { newProjectFor = null },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = J.Card,
            title = { Text("Delete this chat?", color = J.Text) },
            text = { Text("“${c.title}” and its messages will be gone for good. Archive it instead to keep it out of the way.", color = J.TextMuted) },
            confirmButton = { TextButton(onClick = { a.delete(c.id); confirmDelete = null }) { Text("Delete", color = Color(0xFFFF8A8A)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).hoverable(hover)
            .then(if (onToggle != null) Modifier.clicky(onToggle) else Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = J.Accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
        }
        Eyebrow(title, Modifier.weight(1f))
        if (onRemove != null && hovered) {
            Hint("Remove project (its chats stay, ungrouped)") {
                Icon(Icons.Outlined.Close, "Remove project", tint = J.TextDim, modifier = Modifier.size(20.dp).clicky(onRemove).padding(3.dp))
            }
        }
        if (onToggle != null) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = J.TextFaint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChatItem(
    a: DesktopAssistant,
    c: Brain.Conversation,
    selected: Boolean,
    onOpen: () -> Unit,
    onNewProject: () -> Unit,
    onDelete: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val busy = a.thinkingIn == c.id
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(HudShapeSmall)
            .background(if (selected) Color(0x14FFFFFF) else if (hovered) Color(0x0AFFFFFF) else Color.Transparent)
            .hoverable(hover).clicky(onOpen).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(J.Accent))
            Spacer(Modifier.width(8.dp))
        } else if (c.pinned) {
            Icon(Icons.Outlined.PushPin, null, tint = J.Accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            c.title, color = if (selected) J.Text else J.TextMuted, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if ((hovered || menu) && !busy) {
            Box {
                Hint("More") {
                    Icon(Icons.Outlined.MoreHoriz, "More", tint = J.TextMuted, modifier = Modifier.size(28.dp).clip(HudShapeSmall).clicky { menu = true }.padding(5.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = J.Card) {
                    DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin to top") }, onClick = { a.setPinned(c.id, !c.pinned); menu = false })
                    HorizontalDivider(color = J.Hairline)
                    Text("MOVE TO", color = J.TextFaint, fontSize = 9.sp, fontFamily = J.Display, letterSpacing = 1.2.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    a.projects.forEach { p ->
                        DropdownMenuItem(
                            text = { Text((if (c.projectId == p.id) "✓ " else "") + p.name) },
                            onClick = { a.moveToProject(c.id, p.id); menu = false },
                        )
                    }
                    if (c.projectId != null) DropdownMenuItem(text = { Text("No project") }, onClick = { a.moveToProject(c.id, null); menu = false })
                    DropdownMenuItem(text = { Text("New project…", color = J.Accent) }, onClick = { menu = false; onNewProject() })
                    HorizontalDivider(color = J.Hairline)
                    DropdownMenuItem(text = { Text(if (c.archived) "Unarchive" else "Archive") }, onClick = { a.setArchived(c.id, !c.archived); menu = false })
                    DropdownMenuItem(text = { Text("Delete…", color = Color(0xFFFF8A8A)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = J.Card,
        title = { Text("New project", color = J.Text) },
        text = {
            Column {
                Text("A space for related chats — “Work”, “House move”, “Health”.", color = J.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(HudShapeSmall).border(1.dp, J.Accent.copy(alpha = 0.5f), HudShapeSmall).padding(10.dp)) {
                    if (name.isEmpty()) Text("Project name", color = J.TextFaint, fontSize = 14.sp)
                    BasicTextField(
                        value = name, onValueChange = { name = it.replace("\n", "") }, singleLine = true,
                        textStyle = TextStyle(color = J.Text, fontSize = 14.sp), cursorBrush = SolidColor(J.Accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
