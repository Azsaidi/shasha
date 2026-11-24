package com.azsaidi.shasha.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a full refresh, for the progress bar. */
data class RefreshState(
    val step: Int,
    val total: Int,
    val type: ContentType,
    val done: Boolean = false,
    val error: Throwable? = null,
)

/** The account currently open, its catalogue (memory + disk cache), favorites and watch history. */
object Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    /** Bytes downloaded so far for each list being fetched. */
    val downloadBytes = mutableStateMapOf<ContentType, Long>()
    var refresh by mutableStateOf<RefreshState?>(null)
        private set
    private var refreshJob: Job? = null

    /** Recent searches, newest first (shared by all accounts). */
    val searches = mutableStateListOf<String>().apply { addAll(Store.searches()) }

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
        downloadBytes.clear()
        favorites.clear()
        favorites.addAll(Store.favorites(acc.id))
        history.clear()
        history.addAll(Store.history(acc.id))
        Store.lastAccountId = acc.id
        if (knownAuth == null) refreshAuth()
        WatchNext.requestSync()
    }

    /** After editing an account: keep favourites and history, reload lists only if the login changed. */
    fun accountEdited(updated: Account, newAuth: AuthInfo) {
        if (account?.id != updated.id) return
        val old = account
        account = updated
        api = XtreamApi(updated)
        auth = newAuth
        if (old == null || old.server != updated.server || old.username != updated.username || old.password != updated.password) {
            categories.clear()
            streams.clear()
            loading.clear()
            CatalogCache.clear(updated.id)
            cacheVersion++
        }
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
        WatchNext.requestSync()
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
        if (running != null && !running.isCompleted) return running.await()
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
            val days = Store.autoRefreshDays
            if (days > 0 && System.currentTimeMillis() - savedAt > days * 24 * 60 * 60 * 1000L) {
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
        downloadBytes[type] = 0L
        val library = coroutineScope {
            val c = async { client.categories(type) }
            val s = async {
                client.streams(type) { bytes -> scope.launch { downloadBytes[type] = bytes } }
            }
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

    /** Throws away everything cached and downloads fresh lists, one after another, with progress. */
    fun refreshAll() {
        val acc = account ?: return
        val client = api ?: return
        if (refreshJob?.isActive == true) return
        categories.clear()
        streams.clear()
        loading.clear()
        CatalogCache.clear(acc.id)
        val types = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES)
        // Lazy jobs: screens waiting on a list share the same download instead of starting another.
        val jobs = types.associateWith { type -> scope.async(start = CoroutineStart.LAZY) { download(acc, client, type) } }
        loading.putAll(jobs)
        cacheVersion++
        refreshAuth()
        refreshJob = scope.launch {
            var error: Throwable? = null
            types.forEachIndexed { index, type ->
                if (account?.id != acc.id) {
                    refresh = null
                    return@launch
                }
                refresh = RefreshState(index + 1, types.size, type)
                try {
                    jobs.getValue(type).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                }
            }
            refresh = RefreshState(types.size, types.size, types.last(), done = true, error = error)
            delay(3_000)
            refresh = null
        }
    }

    fun findItem(type: ContentType, id: String): StreamItem? =
        streams[type]?.firstOrNull { it.id == id }
            ?: favorites.firstOrNull { it.type == type && it.id == id }

    // ---------------------------------------------------------------- searches

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        searches.removeAll { it.equals(q, ignoreCase = true) }
        searches.add(0, q)
        while (searches.size > 10) searches.removeAt(searches.lastIndex)
        Store.saveSearches(searches.toList())
    }

    fun removeSearch(query: String) {
        searches.remove(query)
        Store.saveSearches(searches.toList())
    }

    fun clearSearches() {
        searches.clear()
        Store.saveSearches(emptyList())
    }

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
        WatchNext.requestSync()
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
            saveHistory(accountId, history.toList())
        } else {
            val list = Store.history(accountId).filter { it.key != entry.key }.toMutableList()
            list.add(0, entry)
            trim(list)
            saveHistory(accountId, list)
        }
    }

    /** Saves the history and refreshes the Google TV home screen's Continue watching row. */
    private fun saveHistory(accountId: String, list: List<WatchEntry>) {
        Store.saveHistory(accountId, list)
        WatchNext.requestSync()
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
        saveHistory(acc.id, history.toList())
    }

    fun removeHistoryKey(key: String) {
        val acc = account ?: return
        if (history.removeAll { it.key == key }) saveHistory(acc.id, history.toList())
    }

    fun removeSeriesHistory(seriesId: String) {
        val acc = account ?: return
        if (history.removeAll { it.kind == WatchKind.EPISODE && it.seriesId == seriesId }) {
            saveHistory(acc.id, history.toList())
        }
    }

    fun hasSeriesHistory(seriesId: String): Boolean =
        history.any { it.kind == WatchKind.EPISODE && it.seriesId == seriesId }

    /** Marks something as finished (keeps it out of Continue watching, shows a tick). */
    /** Marks something as finished. For an episode, the next one goes into Continue watching as "Up next". */
    fun markWatched(entry: WatchEntry, context: android.content.Context? = null) {
        val acc = account ?: return
        record(acc.id, entry.copy(position = 0, finished = true, upNext = false, updatedAt = System.currentTimeMillis()))
        if (entry.kind == WatchKind.EPISODE && context != null) queueNextEpisode(context.applicationContext, acc.id, entry)
    }

    /** Finds the episode after [entry] (into the next season if needed) and lists it as up next. */
    private fun queueNextEpisode(context: android.content.Context, accountId: String, entry: WatchEntry) {
        val seriesId = entry.seriesId ?: return
        val client = api ?: return
        scope.launch {
            val info = runCatching { client.seriesInfo(seriesId) }.getOrNull() ?: return@launch
            val seasons = info.seasons.keys.sorted().filter { info.seasons[it].orEmpty().isNotEmpty() }
            var next: Episode? = null
            for ((si, season) in seasons.withIndex()) {
                val episodes = info.seasons[season].orEmpty()
                val i = episodes.indexOfFirst { it.id == entry.streamId }
                if (i < 0) continue
                next = episodes.getOrNull(i + 1) ?: seasons.getOrNull(si + 1)?.let { info.seasons[it]?.firstOrNull() }
                break
            }
            val ep = next ?: return@launch
            if (account?.id != accountId) return@launch
            val now = System.currentTimeMillis() + 1
            val existing = historyEntry(WatchKind.EPISODE, ep.id)
            if (existing != null && !existing.finished && existing.position > 10_000) {
                // Already part-watched: just bring it to the front.
                record(accountId, existing.copy(updatedAt = now))
                return@launch
            }
            val seriesName = entry.seriesName ?: info.name ?: entry.title
            val code = context.getString(com.azsaidi.shasha.R.string.episode_code, ep.season, ep.number)
            record(
                accountId,
                WatchEntry(
                    kind = WatchKind.EPISODE,
                    streamId = ep.id,
                    title = seriesName,
                    subtitle = listOfNotNull(code, tidyEpisodeTitle(ep.title, seriesName)).joinToString(" · "),
                    image = ep.image ?: info.cover ?: entry.image,
                    extension = ep.extension,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    upNext = true,
                    updatedAt = now,
                ),
            )
        }
    }

    fun clearHistory() {
        val acc = account ?: return
        history.clear()
        saveHistory(acc.id, emptyList())
    }

    /** Movies and series to resume: the latest entry per movie / series, if it isn't finished. */
    fun continueWatching(): List<WatchEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<WatchEntry>()
        val opened = HashMap<String, WatchEntry>()
        for (e in history.sortedByDescending { it.updatedAt }) {
            if (e.kind == WatchKind.LIVE) continue
            val group = if (e.kind == WatchKind.EPISODE) "series:${e.seriesId ?: e.streamId}" else e.key
            if (group in seen) continue
            // An episode only opened for a second doesn't replace the one being watched.
            if (e.barelyStarted) {
                opened.putIfAbsent(group, e)
                continue
            }
            seen.add(group)
            if (e.resumable) {
                out.add(e)
            } else if (e.finished) {
                // The last one was finished and the next one only peeked at: keep it as up next.
                opened[group]?.let { out.add(it.copy(upNext = true)) }
            }
        }
        return out
    }

    fun recentChannels(): List<WatchEntry> =
        history.filter { it.kind == WatchKind.LIVE }.sortedByDescending { it.updatedAt }.take(20)

    /** Latest episode entry for a series (to offer "Continue S1 E5"). */
    fun latestEpisode(seriesId: String): WatchEntry? {
        val all = history.filter { it.kind == WatchKind.EPISODE && it.seriesId == seriesId }
        return all.filter { !it.barelyStarted }.maxByOrNull { it.updatedAt } ?: all.maxByOrNull { it.updatedAt }
    }

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
