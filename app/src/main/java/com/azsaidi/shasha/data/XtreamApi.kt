package com.azsaidi.shasha.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotXtreamException : IOException("Not an Xtream server")
class WrongLoginException : IOException("Wrong username or password")
class InvalidAddressException : IOException("Invalid server address")
class UnexpectedResponseException : IOException("Unexpected response")
class RefusedException(val code: Int) : IOException("HTTP $code")
class ServerErrorException(val code: Int) : IOException("HTTP $code")

object Http {
    /** Many IPTV servers reject unknown clients, so present ourselves as VLC. */
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }
}

/** Client for the Xtream Codes "player_api.php" API. */
class XtreamApi(private val account: Account) {
    private val base = account.server.trimEnd('/')
    private val user = Uri.encode(account.username)
    private val pass = Uri.encode(account.password)

    fun liveUrl(streamId: String, ext: String) = "$base/live/$user/$pass/$streamId.$ext"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$user/$pass/$streamId.$ext"
    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$user/$pass/$episodeId.$ext"

    suspend fun login(): AuthInfo = withContext(Dispatchers.IO) {
        val json = fetchObject(apiUrl())
        val info = json.optJSONObject("user_info") ?: throw NotXtreamException()
        if (info.optInt("auth", 0) != 1) throw WrongLoginException()
        val formats = info.optJSONArray("allowed_output_formats")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it).lowercase() } }
            .orEmpty()
        AuthInfo(
            status = info.str("status"),
            expDate = info.str("exp_date")?.toLongOrNull(),
            maxConnections = info.str("max_connections"),
            activeConnections = info.str("active_cons"),
            allowedFormats = formats,
        )
    }

    suspend fun categories(type: ContentType): List<Category> = withContext(Dispatchers.IO) {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.MOVIE -> "get_vod_categories"
            ContentType.SERIES -> "get_series_categories"
        }
        val arr = fetchArray(apiUrl(action))
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("category_id") ?: continue
            out.add(Category(id, o.str("category_name") ?: "Untitled"))
        }
        out
    }

    suspend fun streams(type: ContentType, onProgress: ((Long) -> Unit)? = null): List<StreamItem> = withContext(Dispatchers.IO) {
        val action = when (type) {
            ContentType.LIVE -> "get_live_streams"
            ContentType.MOVIE -> "get_vod_streams"
            ContentType.SERIES -> "get_series"
        }
        val arr = fetchArray(apiUrl(action), onProgress)
        val out = ArrayList<StreamItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = if (type == ContentType.SERIES) {
                StreamItem(
                    id = o.str("series_id") ?: continue,
                    name = o.str("name") ?: "Untitled",
                    icon = o.str("cover"),
                    categoryId = o.str("category_id"),
                    type = type,
                    extension = null,
                    rating = o.str("rating"),
                    added = o.str("last_modified")?.toLongOrNull(),
                    year = releaseYear(o, o.str("name")),
                )
            } else {
                StreamItem(
                    id = o.str("stream_id") ?: continue,
                    name = o.str("name") ?: "Untitled",
                    icon = o.str("stream_icon"),
                    categoryId = o.str("category_id"),
                    type = type,
                    extension = o.str("container_extension"),
                    rating = o.str("rating"),
                    added = o.str("added")?.toLongOrNull(),
                    year = if (type == ContentType.MOVIE) releaseYear(o, o.str("name")) else null,
                )
            }
            out.add(item)
        }
        out
    }

    suspend fun vodInfo(vodId: String): VodInfo = withContext(Dispatchers.IO) {
        val json = fetchObject(apiUrl("get_vod_info", mapOf("vod_id" to vodId)))
        val info = json.optJSONObject("info") ?: JSONObject()
        val data = json.optJSONObject("movie_data") ?: JSONObject()
        VodInfo(
            name = data.str("name") ?: info.str("name"),
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            duration = info.str("duration"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            rating = info.str("rating"),
            cover = info.str("cover_big") ?: info.str("movie_image"),
            director = info.str("director"),
            cast = info.str("cast") ?: info.str("actors"),
            extension = data.str("container_extension"),
        )
    }

    suspend fun seriesInfo(seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        val json = fetchObject(apiUrl("get_series_info", mapOf("series_id" to seriesId)))
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, MutableList<Episode>>()

        fun addEpisode(o: JSONObject, fallbackSeason: Int) {
            val season = o.str("season")?.toIntOrNull() ?: fallbackSeason
            val number = o.str("episode_num")?.toIntOrNull() ?: 0
            val epInfo = o.optJSONObject("info")
            val episode = Episode(
                id = o.str("id") ?: return,
                title = o.str("title") ?: "Episode $number",
                season = season,
                number = number,
                extension = o.str("container_extension") ?: "mp4",
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image"),
                duration = epInfo?.str("duration"),
            )
            seasons.getOrPut(season) { mutableListOf() }.add(episode)
        }

        // Servers return episodes either as {"1": [...], "2": [...]} or as [[...], [...]].
        when (val episodes = json.opt("episodes")) {
            is JSONObject -> {
                val keys = episodes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodes.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, key.toIntOrNull() ?: 1) }
                }
            }
            is JSONArray -> {
                for (i in 0 until episodes.length()) {
                    val inner = episodes.optJSONArray(i)
                    if (inner != null) {
                        for (j in 0 until inner.length()) inner.optJSONObject(j)?.let { addEpisode(it, i + 1) }
                    } else {
                        episodes.optJSONObject(i)?.let { addEpisode(it, 1) }
                    }
                }
            }
        }
        seasons.values.forEach { list -> list.sortBy { it.number } }

        SeriesInfo(
            name = info.str("name"),
            cover = info.str("cover"),
            plot = info.str("plot"),
            genre = info.str("genre"),
            releaseDate = info.str("releaseDate") ?: info.str("release_date"),
            rating = info.str("rating"),
            seasons = seasons,
        )
    }

    /** Now/next programme info for one live channel. Returns an empty list if the provider has none. */
    suspend fun shortEpg(streamId: String): List<EpgEntry> = withContext(Dispatchers.IO) {
        val json = try {
            fetchObject(apiUrl("get_short_epg", mapOf("stream_id" to streamId, "limit" to "4")))
        } catch (e: IOException) {
            return@withContext emptyList()
        }
        val arr = json.optJSONArray("epg_listings") ?: return@withContext emptyList()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = decodeBase64(o.str("title")) ?: continue
            val start = o.str("start_timestamp")?.toLongOrNull() ?: continue
            val end = (o.str("stop_timestamp") ?: o.str("end_timestamp"))?.toLongOrNull() ?: continue
            out.add(EpgEntry(title, start * 1000, end * 1000))
        }
        out.sortedBy { it.start }
    }

    private fun decodeBase64(value: String?): String? {
        if (value == null) return null
        val decoded = runCatching { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(value)
        return decoded.trim().ifEmpty { null }
    }

    private fun apiUrl(action: String? = null, extra: Map<String, String> = emptyMap()): HttpUrl {
        val root = "$base/player_api.php".toHttpUrlOrNull() ?: throw InvalidAddressException()
        val builder = root.newBuilder()
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
        if (action != null) builder.addQueryParameter("action", action)
        for ((key, value) in extra) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private fun fetch(url: HttpUrl, onProgress: ((Long) -> Unit)? = null): String {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw RefusedException(response.code)
            if (!response.isSuccessful) throw ServerErrorException(response.code)
            val body = response.body ?: return ""
            if (onProgress == null) return body.string().trim()
            // Read in chunks so the UI can show how much has been downloaded.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var reported = 0L
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total - reported >= 256 * 1024) {
                        reported = total
                        onProgress(total)
                    }
                }
            }
            onProgress(total)
            return out.toString("UTF-8").trim()
        }
    }

    private fun fetchArray(url: HttpUrl, onProgress: ((Long) -> Unit)? = null): JSONArray =
        try { JSONArray(fetch(url, onProgress)) } catch (e: JSONException) { JSONArray() }

    private fun fetchObject(url: HttpUrl): JSONObject =
        try { JSONObject(fetch(url)) } catch (e: JSONException) { throw UnexpectedResponseException() }

    private val yearInText = Regex("(19|20)\\d{2}")

    /** Release year from "year"/"releaseDate" fields, or from a title like "The Prestige (2006)". */
    private fun releaseYear(o: JSONObject, name: String?): Int? {
        o.str("year")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { return it }
        (o.str("releaseDate") ?: o.str("release_date") ?: o.str("releasedate"))
            ?.let { yearInText.find(it)?.value?.toIntOrNull() }
            ?.let { return it }
        return yearFromTitle(name)
    }

    companion object {
        /** Turns "example.com:8080/", "http://example.com:8080/get.php?..." etc. into "http://example.com:8080". */
        fun normalizeServer(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }
    }
}
