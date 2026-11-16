package com.azsaidi.shasha.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The account currently open, plus in-memory caches of its catalogue. */
object Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var account by mutableStateOf<Account?>(null)
        private set
    var api: XtreamApi? = null
        private set
    var auth by mutableStateOf<AuthInfo?>(null)
        private set
    var cacheVersion by mutableIntStateOf(0)
        private set

    val favorites = mutableStateListOf<StreamItem>()

    private val categories = HashMap<ContentType, List<Category>>()
    private val streams = HashMap<ContentType, List<StreamItem>>()

    fun open(acc: Account, knownAuth: AuthInfo? = null) {
        account = acc
        val client = XtreamApi(acc)
        api = client
        auth = knownAuth
        categories.clear()
        streams.clear()
        cacheVersion++
        favorites.clear()
        favorites.addAll(Store.favorites(acc.id))
        Store.lastAccountId = acc.id
        if (knownAuth == null) {
            scope.launch {
                val result = runCatching { client.login() }.getOrNull()
                if (account?.id == acc.id && result != null) auth = result
            }
        }
    }

    fun close() {
        account = null
        api = null
        auth = null
        categories.clear()
        streams.clear()
        favorites.clear()
    }

    fun requireApi(): XtreamApi = api ?: error("No account is open")

    fun cached(type: ContentType): Library? {
        val c = categories[type] ?: return null
        val s = streams[type] ?: return null
        return c to s
    }

    suspend fun load(type: ContentType): Library {
        cached(type)?.let { return it }
        val client = requireApi()
        val result = coroutineScope {
            val c = async { client.categories(type) }
            val s = async { client.streams(type) }
            c.await() to s.await()
        }
        if (api === client) {
            categories[type] = result.first
            streams[type] = result.second
        }
        return result
    }

    fun clearCache() {
        categories.clear()
        streams.clear()
        cacheVersion++
    }

    fun findItem(type: ContentType, id: String): StreamItem? =
        streams[type]?.firstOrNull { it.id == id }
            ?: favorites.firstOrNull { it.type == type && it.id == id }

    fun isFavorite(item: StreamItem): Boolean =
        favorites.any { it.type == item.type && it.id == item.id }

    fun toggleFavorite(item: StreamItem) {
        val acc = account ?: return
        val existing = favorites.indexOfFirst { it.type == item.type && it.id == item.id }
        if (existing >= 0) favorites.removeAt(existing) else favorites.add(item)
        Store.saveFavorites(acc.id, favorites.toList())
    }

    /** File extension to request live channels in. */
    fun liveExtension(): String = when (Store.liveFormat) {
        "ts" -> "ts"
        "m3u8" -> "m3u8"
        else -> {
            val formats = auth?.allowedFormats.orEmpty()
            if (formats.isEmpty() || "ts" in formats) "ts" else if ("m3u8" in formats) "m3u8" else "ts"
        }
    }
}
