package com.moviebox.downloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.moviebox.downloader.MainViewModel
import com.moviebox.downloader.api.ContentLayout
import com.moviebox.downloader.api.DetailData
import com.moviebox.downloader.api.fmtSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    vm: MainViewModel,
    state: MainViewModel.DetailUiState,
    bottomBarPadding: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.downloadSelected() else vm.onPermissionDenied()
    }

    fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val d = state.detail

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Column(modifier = Modifier.fillMaxSize()) {
                FloatingBackButton(onBack = { vm.goBack() })
                ErrorPanel(state.error)
            }

            d != null -> DetailBody(vm, state, d, ::withStoragePermission)
        }

        // batch download bar slides in above the bottom nav when episodes
        // are selected
        AnimatedVisibility(
            visible = d != null && state.selectedEps.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            BatchBar(
                count = state.selectedEps.size,
                totalBytes = state.selectedEps.size * (
                    state.streams.firstOrNull { it.resolutionInt == state.selectedResolution }?.sizeBytes ?: 0L
                    ),
                bottomPadding = bottomBarPadding,
                onDownload = { withStoragePermission { vm.downloadSelected() } },
                onClear = { vm.clearSelection() },
            )
        }
    }
}

@Composable
private fun ErrorPanel(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DetailBody(
    vm: MainViewModel,
    state: MainViewModel.DetailUiState,
    d: DetailData,
    withStoragePermission: (() -> Unit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 110.dp),
    ) {
        HeroHeader(d, onBack = { vm.goBack() })
        DescriptionSection(d)

        if (d.dubs.size > 1) DubChips(d, vm)
        if (d.content is ContentLayout.Episodes) SeasonChips(state, d, vm)
        QualityChips(state, vm)
        SubtitleChips(state, vm, d)
        ContentList(vm, state, d, withStoragePermission)
    }
}

/* ------------------------------------------------------------------ */
/* Hero                                                                */
/* ------------------------------------------------------------------ */

/** Circular scrim back button floating over the hero / error states. */
@Composable
private fun FloatingBackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 6.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable { onBack() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun HeroHeader(d: DetailData, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp),
    ) {
        if (d.cover.isNotEmpty()) {
            AsyncImage(
                model = d.cover,
                contentDescription = d.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.40f to Color.Transparent,
                        0.72f to MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                        1.0f to MaterialTheme.colorScheme.surface,
                    )
                )
        )
        FloatingBackButton(onBack = onBack)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        d.type.uppercase(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (d.genres.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            d.genres.first(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                d.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (d.imdb.isNotEmpty() && d.imdb != "0") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color(0xFFFFC107),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            d.imdb,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "  IMDb",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                for (meta in listOf(d.year, d.duration, d.country)) {
                    if (meta.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptionSection(d: DetailData) {
    if (d.description.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        SectionLabel("STORY")
        Text(
            d.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.animateContentSize(),
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show less" else "Read more")
        }
    }
}

/* ------------------------------------------------------------------ */
/* Chip sections                                                       */
/* ------------------------------------------------------------------ */

@Composable
private fun DubChips(d: DetailData, vm: MainViewModel) {
    Column {
        SectionLabel("AUDIO / DUB LANGUAGE")
        ChipRow {
            d.dubs.forEach { dub ->
                FilterChip(
                    selected = dub.current,
                    onClick = { if (!dub.current && dub.detailPath.isNotEmpty()) vm.switchDub(dub.detailPath) },
                    label = { Text(if (dub.original) "${dub.lanName} (original)" else dub.lanName) },
                    leadingIcon = if (dub.current) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun SeasonChips(state: MainViewModel.DetailUiState, d: DetailData, vm: MainViewModel) {
    val seasons = (d.content as ContentLayout.Episodes).seasons
    if (seasons.size <= 1) return
    Column {
        SectionLabel("SEASON")
        ChipRow {
            seasons.forEach { s ->
                FilterChip(
                    selected = s.se == state.selectedSe,
                    onClick = { vm.selectSeason(s.se) },
                    label = { Text("S${s.se}") },
                )
            }
        }
    }
}

@Composable
private fun QualityChips(state: MainViewModel.DetailUiState, vm: MainViewModel) {
    Column {
        SectionLabel("QUALITY")
        when {
            state.linksLoading -> SectionLoading("Loading available qualities…")
            state.linksError != null -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    state.linksError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { vm.retryLinks() }) { Text("Retry") }
            }
            state.streams.isEmpty() -> SectionHint(
                "No downloadable streams for this selection (some titles are VIP-only)."
            )
            else -> ChipRow {
                state.streams.forEach { s ->
                    FilterChip(
                        selected = s.resolutionInt == state.selectedResolution,
                        onClick = { vm.selectResolution(s.resolutionInt) },
                        label = {
                            Text(
                                if (s.size.isNotEmpty()) "${s.resolutionInt}p · ${s.size}"
                                else "${s.resolutionInt}p"
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleChips(
    state: MainViewModel.DetailUiState,
    vm: MainViewModel,
    d: DetailData,
) {
    Column {
        SectionLabel("SUBTITLES (SAVED AS .SRT WITH VIDEO)")
        when {
            state.linksLoading -> SectionLoading("Loading subtitles…")
            state.captions.isNotEmpty() -> ChipRow {
                state.captions.forEach { c ->
                    FilterChip(
                        selected = c.lanName in state.selectedSubtitles,
                        onClick = { vm.toggleSubtitle(c.lanName) },
                        label = {
                            Text(
                                if (c.size.isNotEmpty()) "${c.lanName} · ${c.size}"
                                else c.lanName
                            )
                        },
                    )
                }
            }
            state.linksError != null -> SectionHint(
                "Subtitles unavailable — the play request failed (see the QUALITY error above)."
            )
            d.subtitles.isNotEmpty() -> {
                // the catalog knows the languages; selectable .srt chips
                // appear once the play links load successfully
                ChipRow {
                    d.subtitles.forEach { lan ->
                        AssistChip(onClick = {}, label = { Text(lan) })
                    }
                }
                SectionHint(
                    "Catalog languages shown — the downloadable .srt chips appear once play links load."
                )
            }
            else -> SectionHint("None available.")
        }
    }
}

@Composable
private fun SectionLoading(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/* ------------------------------------------------------------------ */
/* Episodes / parts                                                    */
/* ------------------------------------------------------------------ */

@Composable
private fun ContentList(
    vm: MainViewModel,
    state: MainViewModel.DetailUiState,
    d: DetailData,
    withStoragePermission: (() -> Unit) -> Unit,
) {
    val items: List<Pair<Int, Int>> = when (val c = d.content) {
        is ContentLayout.Episodes -> {
            val season = c.seasons.firstOrNull { it.se == state.selectedSe }
            (1..(season?.maxEp ?: 0)).map { state.selectedSe to it }
        }
        is ContentLayout.Parts -> c.items.map { it.se to it.ep }
    }
    if (items.isEmpty()) return

    val selectedStream =
        state.streams.firstOrNull { it.resolutionInt == state.selectedResolution }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (d.content is ContentLayout.Episodes) "EPISODES" else "DOWNLOAD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (d.content is ContentLayout.Episodes) {
                TextButton(onClick = { vm.selectAllEps() }) { Text("Select all") }
            }
        }

        if (d.content is ContentLayout.Parts) {
            // Movies / multi-part movies: big, always-visible download cards
            val parts = (d.content as ContentLayout.Parts).items
            items.forEach { (se, ep) ->
                val label = if (parts.size > 1 && se != 0) "Part $se" else "Movie"
                val quality = selectedStream?.let { "${it.resolutionInt}p" }
                    ?: if (state.selectedResolution > 0) "${state.selectedResolution}p" else "best"
                MovieDownloadCard(
                    label = label,
                    quality = quality,
                    size = selectedStream?.size ?: "",
                    onDownload = { withStoragePermission { vm.downloadSingle(se, ep) } },
                )
            }
        } else {
            items.forEach { (se, ep) ->
                EpisodeRow(
                    label = "S${String.format("%02d", se)} E${String.format("%02d", ep)}",
                    size = selectedStream?.size ?: "",
                    checked = (se to ep) in state.selectedEps,
                    onCheck = { vm.toggleEp(se, ep) },
                    onDownload = { withStoragePermission { vm.downloadSingle(se, ep) } },
                )
            }
        }
    }
}

/** Movie / part download card — the button is big, labeled and always
 *  visible even while qualities are still loading. */
@Composable
private fun MovieDownloadCard(
    label: String,
    quality: String,
    size: String,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(quality, size.ifEmpty { null }, "MP4").joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            GradientButton(
                text = "Download $label · $quality",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                onClick = onDownload,
            )
        }
    }
}

/** Brand gradient call-to-action button used for the main download actions. */
@Composable
private fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(27.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                    )
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    label: String,
    size: String,
    checked: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheck() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // numbered episode badge (streaming-app style); tapping the row
            // toggles batch selection, the Get button downloads just this one
            val epNum = label.substringAfterLast('E').toIntOrNull()
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    epNum?.toString() ?: label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (checked) Color.Black else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (size.isNotEmpty()) {
                    Text(
                        "~$size · MP4",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // labeled download button — unmissable on every episode row
            FilledTonalButton(
                onClick = onDownload,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download",
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Get", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Batch bar                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun BatchBar(
    count: Int,
    totalBytes: Long,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onDownload: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 10.dp),
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$count episode${if (count > 1) "s" else ""} selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                val size = fmtSize(totalBytes)
                Text(
                    if (size.isNotEmpty()) "~$size total · batch download" else "batch download",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(4.dp))
            GradientButton(
                text = "Download",
                modifier = Modifier.height(46.dp),
                onClick = onDownload,
            )
        }
    }
}
