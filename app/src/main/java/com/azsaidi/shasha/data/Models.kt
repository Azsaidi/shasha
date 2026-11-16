package com.azsaidi.shasha.data

import org.json.JSONObject

data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
)

enum class ContentType(val title: String, val noun: String) {
    LIVE("Live TV", "channels"),
    MOVIE("Movies", "movies"),
    SERIES("Series", "series"),
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("icon", icon ?: "")
        .put("categoryId", categoryId ?: "")
        .put("type", type.name)
        .put("extension", extension ?: "")
        .put("rating", rating ?: "")

    companion object {
        fun fromJson(o: JSONObject) = StreamItem(
            id = o.optString("id"),
            name = o.optString("name"),
            icon = o.optString("icon").ifBlank { null },
            categoryId = o.optString("categoryId").ifBlank { null },
            type = runCatching { ContentType.valueOf(o.optString("type")) }.getOrDefault(ContentType.LIVE),
            extension = o.optString("extension").ifBlank { null },
            rating = o.optString("rating").ifBlank { null },
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

/** Reads a string field, treating missing, JSON null, "null" and blank values as null. */
internal fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return if (value.isEmpty() || value == "null") null else value
}
