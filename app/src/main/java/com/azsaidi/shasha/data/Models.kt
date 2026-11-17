package com.azsaidi.shasha.data

import androidx.annotation.StringRes
import com.azsaidi.shasha.R
import org.json.JSONObject

data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
)

enum class ContentType(@StringRes val titleRes: Int, @StringRes val nounRes: Int) {
    LIVE(R.string.tab_live, R.string.noun_channels),
    MOVIE(R.string.tab_movies, R.string.noun_movies),
    SERIES(R.string.tab_series, R.string.noun_series),
}

data class Category(val id: String, val name: String)

data class StreamItem(
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val type: ContentType,
    val extension: String?,
    val rating: String?,
    /** Unix seconds when the provider added (or last updated) this item. */
    val added: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("icon", icon ?: "")
        .put("categoryId", categoryId ?: "")
        .put("type", type.name)
        .put("extension", extension ?: "")
        .put("rating", rating ?: "")
        .put("added", added ?: 0L)

    companion object {
        fun fromJson(o: JSONObject) = StreamItem(
            id = o.optString("id"),
            name = o.optString("name"),
            icon = o.optString("icon").ifBlank { null },
            categoryId = o.optString("categoryId").ifBlank { null },
            type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrDefault(ContentType.LIVE),
            extension = o.optString("extension").ifBlank { null },
            rating = o.optString("rating").ifBlank { null },
            added = o.optLong("added", 0L).takeIf { it > 0 },
        )
    }
}

typealias Library = Pair<List<Category>, List<StreamItem>>

data class AuthInfo(
    val status: String?,
    val expDate: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
    val allowedFormats: List<String>,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val extension: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
)

data class SeriesInfo(
    val name: String?,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val seasons: Map<Int, List<Episode>>,
)

data class VodInfo(
    val name: String?,
    val plot: String?,
    val genre: String?,
    val duration: String?,
    val releaseDate: String?,
    val rating: String?,
    val cover: String?,
    val director: String?,
    val cast: String?,
    val extension: String?,
)

data class EpgEntry(val title: String, val start: Long, val end: Long)

enum class WatchKind(val prefix: String) { LIVE("live"), MOVIE("movie"), EPISODE("ep") }

/** One row of watch history: what was played, where it stopped, and enough to play it again. */
data class WatchEntry(
    val kind: WatchKind,
    val streamId: String,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val extension: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val finished: Boolean = false,
    /** Set on the episode after one that was finished, so it shows as "Up next". */
    val upNext: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = keyOf(kind, streamId)
    val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val resumable: Boolean get() = kind != WatchKind.LIVE && !finished && (position > 10_000 || upNext)

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("id", streamId)
        .put("title", title)
        .put("subtitle", subtitle ?: "")
        .put("image", image ?: "")
        .put("ext", extension ?: "")
        .put("seriesId", seriesId ?: "")
        .put("seriesName", seriesName ?: "")
        .put("pos", position)
        .put("dur", duration)
        .put("finished", finished)
        .put("upNext", upNext)
        .put("at", updatedAt)

    companion object {
        fun keyOf(kind: WatchKind, streamId: String) = "${kind.prefix}:$streamId"

        fun fromJson(o: JSONObject): WatchEntry? {
            val kind = runCatching { WatchKind.valueOf(o.optString("kind")) }.getOrNull() ?: return null
            return WatchEntry(
                kind = kind,
                streamId = o.optString("id").ifBlank { return null },
                title = o.optString("title"),
                subtitle = o.optString("subtitle").ifBlank { null },
                image = o.optString("image").ifBlank { null },
                extension = o.optString("ext").ifBlank { null },
                seriesId = o.optString("seriesId").ifBlank { null },
                seriesName = o.optString("seriesName").ifBlank { null },
                position = o.optLong("pos"),
                duration = o.optLong("dur"),
                finished = o.optBoolean("finished"),
                upNext = o.optBoolean("upNext"),
                updatedAt = o.optLong("at"),
            )
        }
    }
}

/** Reads a string field, treating missing, JSON null, "null" and blank values as null. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return if (value.isEmpty() || value == "null") null else value
}
