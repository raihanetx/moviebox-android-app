package com.moviebox.downloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moviebox.downloader.MainViewModel
import com.moviebox.downloader.debug.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug & diagnostics screen — reachable from the bug icon on the Home
 * top bar. Three tabs:
 *
 *  1. LIVE — the unified always-on activity log: everything the app does
 *     between open and close (lifecycle, navigation, every user action,
 *     every HTTP request with full request/response detail, downloads
 *     with progress/completions/errors). Filterable by category, each
 *     entry expandable and individually copyable.
 *  2. E2E TEST — one-tap pipeline test with raw server responses.
 *  3. SESSION — device/network info + the previous session's log
 *     (what happened before the app was closed last time).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    vm: MainViewModel,
    state: MainViewModel.DebugUiState,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Debug — everything, live") },
            navigationIcon = {
                IconButton(onClick = { vm.goBack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { vm.shareFullLog(context) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share full log")
                }
            },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("E2E test") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Session") })
        }
        when (tab) {
            0 -> LiveTab(vm)
            1 -> E2eTab(vm, state)
            2 -> SessionTab(vm)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tab 1 — live unified activity log                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun LiveTab(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val events by AppLog.events.collectAsState()
    val stats = AppLog.stats()
    var filter by remember { mutableStateOf("ALL") }

    val filters = listOf(
        "ALL", AppLog.CAT_LIFE, AppLog.CAT_NAV, AppLog.CAT_UI,
        AppLog.CAT_NET, AppLog.CAT_DL, AppLog.CAT_STORE, "ERRORS",
    )
    val shown = remember(events, filter) {
        when (filter) {
            "ALL" -> events
            "ERRORS" -> events.filter { it.level == AppLog.Level.ERROR }
            else -> events.filter { it.cat == filter }
        }.asReversed().take(400)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        /* summary + actions */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Since app open · uptime ${AppLog.uptime()}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "${stats.events} events · ${stats.netOk} requests OK · ${stats.netFailed} failed · ${stats.errors} errors",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stats.errors > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(vm.fullLogText())) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy everything")
            }
            IconButton(onClick = { vm.clearAppLog() }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Clear log")
            }
        }

        /* category filter chips */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(if (f == "ALL") "All" else if (f == "ERRORS") "Errors" else f) },
                )
            }
        }

        if (shown.isEmpty()) {
            Text(
                if (filter == "ALL")
                    "Nothing recorded yet — but the log is always on. Go search, open a\n" +
                        "title or start a download and come back: every step appears here\n" +
                        "in real time, and it is all saved for the next launch too."
                else "No ${if (filter == "ERRORS") "errors" else filter} events yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { ev -> EventRow(ev) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EventRow(ev: AppLog.Event) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(ev.id) { mutableStateOf(false) }
    val time = remember(ev.id) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.at)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ev.cat,
                style = MaterialTheme.typography.labelSmall,
                color = catColor(ev.cat),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(40.dp),
            )
            Text(
                ev.msg,
                style = MaterialTheme.typography.bodySmall,
                color = when (ev.level) {
                    AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
                    AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = if (expanded) 8 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            if (ev.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    ev.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                clipboard.setText(
                    AnnotatedString(
                        "[$time] ${ev.cat} ${ev.level} ${ev.msg}" +
                            (if (ev.detail.isNotEmpty()) "\n${ev.detail}" else "")
                    )
                )
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy this entry")
            }
        }
    }
}

@Composable
private fun catColor(cat: String) = when (cat) {
    AppLog.CAT_NET -> MaterialTheme.colorScheme.primary
    AppLog.CAT_DL -> MaterialTheme.colorScheme.secondary
    AppLog.CAT_NAV -> MaterialTheme.colorScheme.tertiary
    AppLog.CAT_UI -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}

/* ------------------------------------------------------------------ */
/* Tab 2 — end-to-end test                                             */
/* ------------------------------------------------------------------ */

@Composable
private fun E2eTab(
    vm: MainViewModel,
    state: MainViewModel.DebugUiState,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("End-to-end test", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Runs the full pipeline the app uses: connectivity, guest token, " +
                        "search, detail, play streams, subtitles, then downloads 1KB from " +
                        "the CDN. Each step shows timing and the raw server response. " +
                        "Everything it does also appears in the Live tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (state.running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                }
                Button(
                    onClick = { vm.runDiagnostics() },
                    enabled = !state.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Icon(
                        Icons.Rounded.BugReport,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.running) "Running…" else "Run end-to-end test")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { state.report?.let { clipboard.setText(AnnotatedString(it)) } },
                    enabled = state.report != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy test report")
                }
            }
        }

        state.steps.forEachIndexed { i, step -> StepCard(i, step) }
    }
}

@Composable
private fun StepCard(index: Int, step: MainViewModel.DebugStep) {
    var expanded by remember(step.title) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (step.status) {
                "ok" -> MaterialTheme.colorScheme.secondaryContainer
                "fail" -> MaterialTheme.colorScheme.errorContainer
                "running" -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (step.status) {
                    "running" -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    "ok" -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    "fail" -> Icon(
                        Icons.Rounded.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> Icon(
                        Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${index + 1}. ${step.title}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (step.ms > 0) {
                    Text("${step.ms} ms", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (step.summary.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    step.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == "fail") MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.raw.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide raw response" else "Show raw response")
                }
                if (expanded) {
                    Text(
                        step.raw,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tab 3 — session info + previous session                             */
/* ------------------------------------------------------------------ */

@Composable
private fun SessionTab(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val previous by AppLog.previous.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("This device / session", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    vm.deviceInfo(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Uptime: ${AppLog.uptime()} · the full log persists to applog.jsonl and is " +
                        "viewable here after every restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(vm.fullLogText())) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Copy this session's full log")
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    "Previous session (before the app was closed)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (previous.isEmpty()) {
                    Text(
                        "No previous session was recorded yet — it will appear here after " +
                            "you close and reopen the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val last = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(previous.last().at))
                    Text(
                        "${previous.size} events, last one at $last",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(vm.previousSessionText())) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Copy previous session log")
                    }
                    Spacer(Modifier.height(10.dp))
                    previous.takeLast(30).asReversed().forEach { ev ->
                        Text(
                            "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.at))}  ${ev.msg}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (ev.level) {
                                AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
