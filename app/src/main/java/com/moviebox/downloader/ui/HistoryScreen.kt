package com.moviebox.downloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.moviebox.downloader.MainViewModel
import com.moviebox.downloader.api.fmtSize
import com.moviebox.downloader.data.HistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: MainViewModel,
    entries: List<HistoryEntry>,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(entries) { entries.sortedByDescending { it.createdAt } }
    var clearDialog by remember { mutableStateOf(false) }
    val active = sorted.count { it.status == "queued" || it.status == "running" }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            actions = {
                IconButton(onClick = { vm.openDownloadsApp() }) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = "Open Downloads folder")
                }
                IconButton(onClick = { clearDialog = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear list")
                }
            },
        )

        if (sorted.isNotEmpty()) {
            Text(
                "${sorted.size} ${if (sorted.size == 1) "item" else "items"}" +
                    if (active > 0) " · $active downloading" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (sorted.isEmpty()) {
            EmptyHistory()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sorted.size, key = { sorted[it].dmId }) { i ->
                    HistoryRow(vm, sorted[i])
                }
            }
        }
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Clear download list?") },
            text = { Text("Running downloads will be canceled. Files stay in your Downloads folder.") },
            confirmButton = {
                Button(onClick = {
                    clearDialog = false
                    vm.clearHistory()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyHistory() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "No downloads yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Downloaded videos appear here with live progress",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Color-coded status pill. */
@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text.uppercase(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private val StatusGreen = Color(0xFF6DD58C)
private val StatusGray = Color(0xFF9AA1AD)

@Composable
private fun HistoryRow(vm: MainViewModel, entry: HistoryEntry) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // cover thumb
            if (entry.cover.isNotEmpty()) {
                AsyncImage(
                    model = entry.cover,
                    contentDescription = null,
                    modifier = Modifier
                        .width(56.dp)
                        .height(82.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(82.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (entry.isSubtitle) Icons.Rounded.Download else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    val (pillText, pillColor) = when (entry.status) {
                        "queued" -> "Queued" to MaterialTheme.colorScheme.primary
                        "running" -> "Downloading" to MaterialTheme.colorScheme.tertiary
                        "paused" -> "Paused" to MaterialTheme.colorScheme.tertiary
                        "completed" -> "Completed" to StatusGreen
                        "failed" -> "Failed" to MaterialTheme.colorScheme.error
                        else -> "Canceled" to StatusGray
                    }
                    StatusPill(pillText, pillColor)
                    Text(
                        "${entry.label} · ${entry.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (entry.status) {
                    "queued" -> {}

                    "running", "paused" -> {
                        if (entry.total > 0) {
                            val pct = (entry.soFar * 100 / entry.total).toInt()
                            LinearProgressIndicator(
                                progress = { entry.soFar.toFloat() / entry.total.toFloat() },
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                            Text(
                                "$pct% · ${fmtSize(entry.soFar)} / ${fmtSize(entry.total)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } else {
                            LinearProgressIndicator(
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }

                    "completed" -> Text(
                        "${fmtSize(entry.sizeBytes)} · tap ▶ to play",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    "failed" -> Text(
                        "Tap ↻ to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    "canceled" -> {}
                }
            }

            Spacer(Modifier.width(8.dp))

            // status action
            when (entry.status) {
                "completed" -> IconButton(onClick = { vm.openEntry(entry) }) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Open",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                "failed" -> IconButton(onClick = { vm.redownload(entry) }) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Retry",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (entry.status == "completed" && entry.localUri != null) {
                        DropdownMenuItem(
                            text = { Text("Open file") },
                            onClick = { menuOpen = false; vm.openEntry(entry) },
                        )
                    }
                    if (entry.status == "running" || entry.status == "queued") {
                        DropdownMenuItem(
                            text = { Text("Cancel") },
                            onClick = { menuOpen = false; vm.cancelEntry(entry) },
                        )
                    }
                    if (!entry.isSubtitle) {
                        DropdownMenuItem(
                            text = { Text("Re-download") },
                            onClick = { menuOpen = false; vm.redownload(entry) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove from list") },
                        onClick = { menuOpen = false; vm.removeEntry(entry, deleteFile = false) },
                    )
                    if (entry.status == "completed" && entry.localUri != null) {
                        DropdownMenuItem(
                            text = { Text("Delete file + remove") },
                            onClick = { menuOpen = false; vm.removeEntry(entry, deleteFile = true) },
                        )
                    }
                }
            }
        }
    }
}
