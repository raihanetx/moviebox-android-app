package com.moviebox.downloader.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/* ------------------------------------------------------------------ */
/* Lenient JSON parsing helpers (the API is sloppy: numbers-as-strings,
   mixed types, unknown fields) — mirrors the JS implementation.       */
/* ------------------------------------------------------------------ */

val lenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun JsonObject?.optString(key: String): String? {
    val el = this?.get(key) ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.content ?: return null
}

fun JsonObject?.optInt(key: String): Int? = this?.optString(key)?.toDoubleOrNull()?.toInt()

fun JsonObject?.optLong(key: String): Long? = this?.optString(key)?.toDoubleOrNull()?.toLong()

fun JsonObject?.optBool(key: String): Boolean? {
    val s = this?.optString(key) ?: return null
    return when (s.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

fun JsonObject?.optObj(key: String): JsonObject? = this?.get(key) as? JsonObject

fun JsonObject?.optArr(key: String): JsonArray? = this?.get(key) as? JsonArray

fun parseObj(body: String): JsonObject =
    lenientJson.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalStateException("unexpected JSON structure")

/* ------------------------------------------------------------------ */
/* Domain models (normalized, mirrors the web app types)              */
/* ------------------------------------------------------------------ */

data class Dub(
    val lanName: String,
    val lanCode: String,
    val detailPath: String,
    val original: Boolean,
    val current: Boolean,
)

data class PartItem(
    val se: Int,
    val ep: Int,
    val label: String,
    val resolutions: List<Int>,
)

data class SeasonItem(
    val se: Int,
    val maxEp: Int,
    val label: String,
    val resolutions: List<Int>,
)

sealed class ContentLayout {
    data class Parts(val items: List<PartItem>) : ContentLayout()
    data class Episodes(val seasons: List<SeasonItem>) : ContentLayout()
}

data class CastMember(
    val name: String,
    val character: String,
    val avatar: String,
)

data class DetailData(
    val subjectId: String,
    val detailPath: String,
    val type: String,          // "movie" | "series" | "video"
    val title: String,
    val description: String,
    val cover: String,
    val releaseDate: String,
    val year: String,
    val duration: String,
    val genres: List<String>,
    val country: String,
    val imdb: String,
    val source: String,
    val dubs: List<Dub>,
    val subtitles: List<String>,
    val cast: List<CastMember>,
    val content: ContentLayout,
    val hasResource: Boolean,
)

data class StreamItem(
    val format: String,
    val id: String,
    val url: String,
    val resolution: String,     // "480" etc
    val resolutionInt: Int,
    val size: String,           // human readable
    val sizeBytes: Long,
    val duration: String,
)

data class CaptionItem(
    val lanName: String,
    val lan: String,
    val url: String,
    val size: String,
)

data class PlayResult(
    val streams: List<StreamItem>,
    val captions: List<CaptionItem>,
)

data class SearchResult(
    val title: String,
    val detailPath: String,
    val type: String,
    val cover: String,
    val year: String,
    val hasResource: Boolean,
    val genres: List<String> = emptyList(),
)

/* ------------------------------------------------------------------ */
/* Formatting helpers (ported from the web version)                    */
/* ------------------------------------------------------------------ */

fun fmtSize(num: Long?): String {
    var n = num?.toDouble() ?: return ""
    if (n <= 0.0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var i = 0
    while (n >= 1024 && i < 3) {
        n /= 1024
        i++
    }
    return if (i == 0) "${n.toInt()} ${units[i]}" else String.format("%.1f %s", n, units[i])
}

fun fmtDuration(sec: Long?): String {
    val s = sec ?: return ""
    if (s <= 0) return ""
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${String.format("%02d", m)}m" else "${m}m"
}

private val TYPE_NAMES = mapOf(1 to "movie", 2 to "series", 6 to "video")

fun typeName(code: Int): String = TYPE_NAMES[code] ?: "video"

/* ------------------------------------------------------------------ */
/* Detail normalization — auto movie / multi-part / series detection   */
/* (exact port of the TypeScript normalizeDetail)                      */
/* ------------------------------------------------------------------ */

fun normalizeDetail(data: JsonObject): DetailData {
    val subj = data.optObj("subject")
    val resource = data.optObj("resource")
    val stars = data.optArr("stars") ?: emptyList()

    val tName = typeName(subj?.optInt("subjectType") ?: 0)

    // --- seasons raw ---
    data class SeasonRaw(val se: Int, val maxEp: Int, val resolutions: List<Int>)
    val seasons: List<SeasonRaw> = (resource?.optArr("seasons") ?: emptyList())
        .mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            val res = (s.optArr("resolutions") ?: emptyList())
                .mapNotNull { r -> (r as? JsonObject)?.optInt("resolution") }
                .filter { it > 0 }
                .distinct()
                .sorted()
            SeasonRaw(s.optInt("se") ?: 0, s.optInt("maxEp") ?: 0, res)
        }

    // --- content layout ---
    val content: ContentLayout = when {
        tName == "movie" && seasons.isNotEmpty() -> {
            val allZero = seasons.all { it.maxEp == 0 }
            if (allZero) {
                val parts = if (seasons.size == 1) {
                    listOf(PartItem(seasons[0].se, 0, "Movie", seasons[0].resolutions))
                } else {
                    seasons.map {
                        PartItem(it.se, 0, if (it.se != 0) "Part ${it.se}" else "Movie", it.resolutions)
                    }
                }
                ContentLayout.Parts(parts)
            } else {
                // movie with episode-ish parts: show as one movie card
                val allRes = seasons.flatMap { it.resolutions }.distinct().sorted()
                ContentLayout.Parts(listOf(PartItem(0, 0, "Movie", allRes)))
            }
        }
        seasons.isNotEmpty() -> ContentLayout.Episodes(
            seasons.map {
                SeasonItem(it.se, if (it.maxEp > 0) it.maxEp else 1, "Season ${it.se}", it.resolutions)
            }
        )
        else -> ContentLayout.Parts(
            listOf(PartItem(0, 0, if (tName == "movie") "Movie" else "Video", emptyList()))
        )
    }

    // --- dubs ---
    val subjPath = subj?.optString("detailPath") ?: ""
    var dubs: List<Dub> = (subj?.optArr("dubs") ?: emptyList()).mapNotNull { el ->
        val d = el as? JsonObject ?: return@mapNotNull null
        Dub(
            lanName = d.optString("lanName") ?: "",
            lanCode = d.optString("lanCode") ?: "",
            detailPath = d.optString("detailPath") ?: "",
            original = d.optString("original") == "true" || d.optString("original") == "1",
            current = (d.optString("detailPath") ?: "") == subjPath,
        )
    }
    if (dubs.isEmpty()) {
        dubs = listOf(Dub("Original", "", subjPath, true, true))
    }

    // --- subtitles (csv) ---
    val subtitles = (subj?.optString("subtitles") ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    // --- cast (first 12) ---
    val cast: List<CastMember> = stars.mapNotNull { el ->
        val s = el as? JsonObject ?: return@mapNotNull null
        CastMember(
            name = s.optString("name") ?: "",
            character = s.optString("character") ?: "",
            avatar = s.optString("avatarUrl") ?: "",
        )
    }.take(12)

    val genres = (subj?.optString("genre") ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val releaseDate = subj?.optString("releaseDate") ?: ""
    val hasResource = (subj?.optBool("hasResource") == true) || seasons.isNotEmpty()

    return DetailData(
        subjectId = subj?.optString("subjectId") ?: "",
        detailPath = subjPath,
        type = tName,
        title = subj?.optString("title") ?: "",
        description = subj?.optString("description") ?: "",
        cover = subj?.optObj("cover")?.optString("url") ?: "",
        releaseDate = releaseDate,
        year = releaseDate.take(4),
        duration = fmtDuration(subj?.optLong("duration")),
        genres = genres,
        country = subj?.optString("countryName") ?: "",
        imdb = subj?.optString("imdbRatingValue") ?: "",
        source = resource?.optString("source") ?: "",
        dubs = dubs,
        subtitles = subtitles,
        cast = cast,
        content = content,
        hasResource = hasResource,
    )
}
