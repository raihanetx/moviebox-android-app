package com.moviebox.downloader

import com.moviebox.downloader.api.CaptionItem
import com.moviebox.downloader.api.DetailData
import com.moviebox.downloader.api.SearchResult
import com.moviebox.downloader.api.StreamItem

/** Shared UI models — imported by MainViewModel and the extracted ViewModels. */
sealed class Screen {
    object Home : Screen()
    data class Detail(val slug: String) : Screen()
    object Downloads : Screen()
    object Debug : Screen()
}

data class HomeUiState(
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
    val searched: Boolean = false,
    val blockedCount: Int = 0,
)

data class DetailUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val detail: DetailData? = null,
    val selectedSe: Int = 0,
    val selectedResolution: Int = 0,
    val selectedSubtitles: Set<String> = emptySet(),
    val selectedEps: Set<Pair<Int, Int>> = emptySet(),
    val streams: List<StreamItem> = emptyList(),
    val captions: List<CaptionItem> = emptyList(),
    val linksLoading: Boolean = false,
    val linksError: String? = null,
)

data class DebugUiState(
    val running: Boolean = false,
    val steps: List<DebugStep> = emptyList(),
    val report: String? = null,
)

data class DebugStep(
    val title: String,
    val status: String = "pending",
    val summary: String = "",
    val raw: String = "",
    val ms: Long = 0,
)
