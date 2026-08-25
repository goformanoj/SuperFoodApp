package com.jarvis.os.ui.files

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jarvis.os.ui.components.EmptyState
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.files.Artifact
import com.jarvis.os.files.ArtifactStore
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The things JARVIS has made for you: tap to open, Share to send it on, Delete
 * to remove the file as well as the row.
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { ArtifactStore(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val files = remember(refresh) { store.list() }
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    fun uriFor(artifact: Artifact) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        store.fileFor(artifact),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Files",
            subtitle = "Things JARVIS has made for you. They stay on this phone unless " +
                "you share one.",
        )

        if (files.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Folder,
                title = "Nothing here yet",
                line = "Try: \"make a PDF of the important points from our conversation\".",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { artifact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(JarvisTheme.glass)
                            .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uriFor(artifact), artifact.kind.mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            artifact.kind.id.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = JarvisTheme.accent,
                            modifier = Modifier.padding(end = 14.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                artifact.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${stamp.format(Date(artifact.createdMillis))} · ${artifact.sizeBytes / 1024 + 1} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                        Text(
                            "Share",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisTheme.accent,
                            modifier = Modifier
                                .clickable {
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
                                }
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            "Delete",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier
                                .clickable {
                                    store.delete(artifact)
                                    refresh++
                                }
                                .padding(start = 4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
