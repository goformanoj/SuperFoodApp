package com.jarvis.os.ui.files

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jarvis.os.files.Artifact
import com.jarvis.os.files.ArtifactKind
import com.jarvis.os.files.ArtifactStore
import com.jarvis.os.files.FileFormat
import com.jarvis.os.ui.components.EmptyState
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Things JARVIS has made: grouped by when, opened, shared or deleted from a ⋯ menu. */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ArtifactStore(context) }
    val now = remember { System.currentTimeMillis() }
    var refresh by remember { mutableIntStateOf(0) }
    val all = remember(refresh) { store.list() }
    var filter by remember { mutableStateOf<ArtifactKind?>(null) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    fun uriFor(a: Artifact) = FileProvider.getUriForFile(context, "${context.packageName}.files", store.fileFor(a))

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Files",
            subtitle = "Everything JARVIS has made for you. On this phone unless you share it.",
        )

        if (all.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                title = "Nothing here yet",
                line = "Try: \"make a PDF of the important points from our conversation\".",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Type filter — only kinds that actually exist, plus All.
            val kinds = all.map { it.kind }.distinct()
            if (kinds.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("All", filter == null) { filter = null }
                    kinds.forEach { k -> FilterChip(k.id.uppercase(), filter == k) { filter = k } }
                }
                Spacer(Modifier.height(14.dp))
            }

            val shown = if (filter == null) all else all.filter { it.kind == filter }
            FileFormat.Bucket.entries.forEach { bucket ->
                val group = shown.filter { FileFormat.bucket(it.createdMillis, now) == bucket }
                if (group.isNotEmpty()) {
                    Text(bucket.label, style = MaterialTheme.typography.labelLarge, color = JarvisTheme.accent)
                    Spacer(Modifier.height(8.dp))
                    group.forEach { artifact ->
                        FileRow(
                            artifact = artifact,
                            stampText = stamp.format(Date(artifact.createdMillis)),
                            menuOpen = openMenu == artifact.fileName,
                            onMenu = { openMenu = if (openMenu == artifact.fileName) null else artifact.fileName },
                            onDismissMenu = { openMenu = null },
                            onOpen = {
                                openMenu = null
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uriFor(artifact), artifact.kind.mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            onShare = {
                                openMenu = null
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND)
                                                .setType(artifact.kind.mime)
                                                .putExtra(Intent.EXTRA_STREAM, uriFor(artifact))
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            "Share ${artifact.title}",
                                        ),
                                    )
                                }
                            },
                            onDelete = {
                                openMenu = null
                                store.delete(artifact)
                                refresh++
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                FileFormat.summary(all.map { FileFormat.Item(it.kind.id.uppercase(), it.sizeBytes) }),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Background else TextSecondary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) JarvisTheme.accent else JarvisTheme.glass)
            .border(1.dp, if (selected) JarvisTheme.accent else JarvisTheme.glassBorder, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun FileRow(
    artifact: Artifact,
    stampText: String,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type thumbnail — the kind reads before the title does.
        Box(
            Modifier
                .size(width = 40.dp, height = 46.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(JarvisTheme.accent.copy(alpha = 0.14f))
                .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                artifact.kind.id.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = JarvisTheme.accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artifact.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$stampText · ${FileFormat.humanSize(artifact.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Box {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Actions",
                tint = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onMenu() }
                    .padding(4.dp)
                    .size(22.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("Open") }, onClick = onOpen)
                DropdownMenuItem(text = { Text("Share") }, onClick = onShare)
                DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
            }
        }
    }
}
