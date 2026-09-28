package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.brain.Brain
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Files (AGENT_PLAN §3/§5): every document the user has given JARVIS. The text was read
 * on this laptop and stays here; asking about one opens a chat with it attached.
 */
@Composable
fun FilesScreen(a: DesktopAssistant, onAsk: () -> Unit, onOpenConversation: (String) -> Unit) {
    Page(
        "Files",
        "Documents you've given JARVIS: PDF, Word and text. They're read on this laptop, never uploaded whole; only the passages a question needs go to the model. Drag a file onto the window, or add one here.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(38.dp).clip(HudShapeSmall).background(J.Accent).clicky { pickFiles()?.let { a.attach(it); onAsk() } }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, null, tint = J.OnAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("ADD A FILE", color = J.OnAccent, fontSize = 11.sp, fontFamily = J.Display, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.width(14.dp))
            Text("Or ask in chat: “find the invoice from March”: JARVIS searches the whole laptop.", color = J.TextDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        if (a.documents.isEmpty()) {
            Text("No documents yet.", color = J.TextMuted, fontSize = 15.sp)
            return@Page
        }
        LazyColumn(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { HudRule("Documents · ${a.documents.size}", Modifier.padding(bottom = 4.dp)) }
            items(a.documents, key = { it.id }) { d -> DocRow(a, d, onAsk, onOpenConversation) }
        }
    }
}

@Composable
private fun DocRow(a: DesktopAssistant, d: Brain.Document, onAsk: () -> Unit, onOpenConversation: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(HudShapeSmall).background(J.Card).border(1.dp, J.CardBorder, HudShapeSmall)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(J.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(if (d.kind == "pdf") Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null, tint = J.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = J.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = d.sourceConversation?.let { id -> a.conversations.firstOrNull { it.id == id } }
            Row {
                Text(
                    "${d.pages} ${d.unit}${if (d.pages == 1) "" else "s"} · ${"%,d".format(d.chars)} characters · added ${added(d.created)}",
                    color = J.TextDim, fontSize = 11.sp,
                )
                if (source != null) {
                    Text("  ·  from “${source.title}”", color = J.Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clicky { onOpenConversation(source.id) })
                }
            }
        }
        if (confirm) {
            Text("Remove from JARVIS? (the file itself stays)", color = J.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text("REMOVE", color = Color(0xFFFF8A8A), fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(HudShapeSmall).clicky { a.deleteDocument(d.id) }.padding(8.dp))
            Text("KEEP", color = J.Text, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(HudShapeSmall).clicky { confirm = false }.padding(8.dp))
        } else {
            Hint("Ask JARVIS about this document") {
                Row(
                    Modifier.height(32.dp).clip(HudShapeSmall).border(1.dp, J.Border, HudShapeSmall).clicky { a.askAbout(d); onAsk() }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, tint = J.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ask", color = J.Text, fontSize = 12.sp)
                }
            }
            Hint("Remove from JARVIS (the file on your laptop is not touched)") {
                Icon(Icons.Outlined.Close, "Remove", tint = J.TextDim,
                    modifier = Modifier.size(32.dp).clip(HudShapeSmall).clicky { confirm = true }.padding(7.dp))
            }
        }
    }
}

private fun added(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
