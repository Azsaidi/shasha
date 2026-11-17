package com.azsaidi.shasha.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The account currently open, its catalogue (memory + disk cache), favorites and watch history. */
object Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    var account by mutableStateOf<Account?>(null)
        private set
    var api: XtreamApi? = null
        private set
    var auth by mutableStateOf<AuthInfo?>(null)
        private set

    /** Bumped whenever cached catalogues change, so screens reload them. */
    var cacheVersion by mutableIntStateOf(0)
        private set

    var hideAdult by mutableStateOf(Store.hideAdult)
        private set

    val favorites = mutableStateListOf<StreamItem>()
    val history = mutableStateListOf<WatchEntry>()

    private val categories = HashMap<ContentType, List<Category>>()
    private val streams = HashMap<ContentType, List<StreamItem>>()
    private val loading = HashMap<ContentType, Deferred<Library>>()

    fun open(acc: Account, knownAuth: AuthInfo? = null) {
        account = acc
        val client = XtreamApi(acc)
        api = client
        auth = knownAuth
        categories.clear()
        streams.clear()
        loading.clear()
        cacheVersion++
        favorites.clear()
        favorites.addAll(Store.favorites(acc.id))
        history.clear()
        history.addAll(Store.history(acc.id))
        Store.lastAccountId = acc.id
        if (knownAuth == null) refreshAuth()
    }

    fun close() {
        account = null
        api = null
        auth = null
        categories.clear()
        streams.clear()
        loading.clear()
        favorites.clear()
        history.clear()
    }

    private fun refreshAuth() {
        val acc = account ?: return
        val client = api ?: return
        scope.launch {
            val result = runCatching { client.login() }.getOrNull()
            if (account?.id == acc.id && result != null) auth = result
        }
    }

    fun requireApi(): XtreamApi = api ?: error("No account is open")

    // ---------------------------------------------------------------- catalogue

    fun cached(type: ContentType): Library? {
        val c = categories[type] ?: return null
        val s = streams[type] ?: return null
        return c to s
    }

    /** Memory, then disk, then network. Concurrent callers share one download. */
    suspend fun load(type: ContentType): Library {
        cached(type)?.let { return it }
        val acc = account ?: error("No account is open")
        val running = loading[type]
        if (running != null && running.isActive) return running.await()
        val job = scope.async { loadFromDiskOrNetwork(acc, type) }
        loading[type] = job
        return job.await()
    }

    private suspend fun loadFromDiskOrNetwork(acc: Account, type: ContentType): Library {
        val client = api ?: error("No account is open")
        val disk = withContext(Dispatchers.IO) { CatalogCache.read(acc.id, type) }
        if (disk != null) {
            val (library, savedAt) = disk
            if (account?.id == acc.id) remember(type, library)
            if (System.currentTimeMillis() - savedAt > STALE_AFTER_MS) {
                // Show the saved copy now and quietly fetch a fresh one.
                scope.launch {
                    runCatching { download(acc, client, type) }
                        .onSuccess { if (account?.id == acc.id) cacheVersion++ }
                }
            }
            return library
        }
        return download(acc, client, type)
    }

    private suspend fun download(acc: Account, client: XtreamApi, type: ContentType): Library {
        val library = coroutineScope {
            val c = async { client.categories(type) }
            val s = async { client.streams(type) }
            c.await() to s.await()
        }
        if (account?.id == acc.id) remember(type, library)
        withContext(Dispatchers.IO) { CatalogCache.write(acc.id, type, library) }
        return library
    }

    private fun remember(type: ContentType, library: Library) {
        categories[type] = library.first
        streams[type] = library.second
    }

    /** Throws away everything cached and downloads fresh lists. */
    fun refreshAll() {
        val acc = account ?: return
        categories.clear()
        streams.clear()
        loading.clear()
        CatalogCache.clear(acc.id)
        cacheVersion++
        refreshAuth()
    }

    fun findItem(type: ContentType, id: String): StreamItem? =
        streams[type]?.firstOrNull { it.id == id }
            ?: favorites.firstOrNull { it.type == type && it.id == id }

    // ---------------------------------------------------------------- adult filter

    private val adultPattern = Regex("(?i)(adult|xxx|porn|\\b18\\s*\\+|\\+\\s*18\\b)")

    fun isAdult(text: String?): Boolean = text != null && adultPattern.containsMatchIn(text)

    fun updateHideAdult(value: Boolean) {
        hideAdult = value
        Store.hideAdult = value
    }

    /** The library with adult categories removed when that setting is on. */
    fun visible(library: Library): Library {
        if (!hideAdult) return library
        val hidden = library.first.filter { isAdult(it.name) }.map { it.id }.toHashSet()
        return library.first.filterNot { it.id in hidden } to
            library.second.filter { it.categoryId !in hidden && !isAdult(it.name) }
    }

    // ---------------------------------------------------------------- favorites

    fun isFavorite(item: StreamItem): Boolean =
        favorites.any { it.type == item.type && it.id == item.id }

    fun toggleFavorite(item: StreamItem) {
        val acc = account ?: return
        val existing = favorites.indexOfFirst { it.type == item.type && it.id == item.id }
        if (existing >= 0) favorites.removeAt(existing) else favorites.add(0, item)
        Store.saveFavorites(acc.id, favorites.toList())
    }

    // ---------------------------------------------------------------- watch history

    fun historyEntry(kind: WatchKind, streamId: String): WatchEntry? {
        val key = WatchEntry.keyOf(kind, streamId)
        return history.firstOrNull { it.key == key }
    }

    fun record(accountId: String, entry: WatchEntry) {
        if (account?.id == accountId) {
            val index = history.indexOfFirst { it.key == entry.key }
            if (index >= 0) history.removeAt(index)
            history.add(0, entry)
            trim(history)
            Store.saveHistory(accountId, history.toList())
        } else {
            val list = Store.history(accountId).filter { it.key != entry.key }.toMutableList()
            list.add(0, entry)
            trim(list)
            Store.saveHistory(accountId, list)
        }
    }

    private fun trim(list: MutableList<WatchEntry>) {
        var live = 0
        val iterator = list.listIterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.kind == WatchKind.LIVE && ++live > 30) iterator.remove()
        }
        while (list.size > 300) list.removeAt(list.lastIndex)
    }

    fun removeFromHistory(entry: WatchEntry) {
        val acc = account ?: return
        // For a series, drop every episode so an older one doesn't pop up in its place.
        if (entry.kind == WatchKind.EPISODE && entry.seriesId != null) {
            history.removeAll { it.kind == WatchKind.EPISODE && it.seriesId == entry.seriesId }
        } else {
            history.removeAll { it.key == entry.key }
        }
        Store.saveHistory(acc.id, history.toList())
    }

    /** Movies and series to resume: the latest entry per movie / series, if it isn't finished. */
    fun continueWatching(): List<WatchEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<WatchEntry>()
        for (e in history.sortedByDescending { it.updatedAt }) {
            if (e.kind == WatchKind.LIVE) continue
            val group = if (e.kind == WatchKind.EPISODE) "series:${e.seriesId ?: e.streamId}" else e.key
            if (!seen.add(group)) continue
            if (e.resumable) out.add(e)
        }
        return out
    }

    fun recentChannels(): List<WatchEntry> =
        history.filter { it.kind == WatchKind.LIVE }.sortedByDescending { it.updatedAt }.take(20)

    /** Latest episode entry for a series (to offer "Continue S1 E5"). */
    fun latestEpisode(seriesId: String): WatchEntry? =
        history.filter { it.kind == WatchKind.EPISODE && it.seriesId == seriesId }.maxByOrNull { it.updatedAt }

    // ---------------------------------------------------------------- live format

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
