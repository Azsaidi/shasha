package com.azsaidi.shasha.player

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.DownloadItem
import com.azsaidi.shasha.data.DownloadState
import com.azsaidi.shasha.data.Downloads
import com.azsaidi.shasha.data.Episode
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.data.tidyEpisodeTitle
import kotlinx.coroutines.withTimeoutOrNull

data class PlayItem(
    val title: String,
    val url: String,
    val kind: WatchKind,
    val streamId: String,
    val accountId: String,
    val subtitle: String? = null,
    val image: String? = null,
    val extension: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
) {
    val isLive: Boolean get() = kind == WatchKind.LIVE
    val key: String get() = WatchEntry.keyOf(kind, streamId)
}

/** Hands a playlist to [PlayerActivity] without squeezing thousands of URLs through an Intent. */
object PlayerQueue {
    var items: List<PlayItem> = emptyList()
    var startIndex: Int = 0
    /** Ignore the saved position for the first item. */
    var fromStart: Boolean = false
}

/** Streams picked for split screen (up to four). */
object MultiView {
    const val MAX = 4
    val items = mutableStateListOf<PlayItem>()

    /** Adds an item and tells the user; returns false if it was already full. */
    fun add(context: Context, item: PlayItem?): Boolean {
        if (item == null) return false
        if (items.any { it.key == item.key }) {
            Toast.makeText(context, context.getString(R.string.split_added, items.size), Toast.LENGTH_SHORT).show()
            return true
        }
        if (items.size >= MAX) {
            Toast.makeText(context, context.getString(R.string.split_full), Toast.LENGTH_SHORT).show()
            return false
        }
        items.add(item)
        Toast.makeText(context, context.getString(R.string.split_added, items.size), Toast.LENGTH_SHORT).show()
        return true
    }

    fun clear() = items.clear()

    fun open(context: Context) {
        if (items.isNotEmpty()) MultiViewActivity.open(context, pick = false)
    }
}

/** Builds play queues for each kind of content and starts the player. */
object Playback {

    private fun start(context: Context, items: List<PlayItem>, index: Int, fromStart: Boolean = false) {
        if (items.isEmpty()) return
        PlayerQueue.items = items
        PlayerQueue.startIndex = index.coerceIn(0, items.lastIndex)
        PlayerQueue.fromStart = fromStart
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------- item builders

    fun liveItem(item: StreamItem): PlayItem? = liveItem(item.id, item.name, item.icon)

    fun liveItem(id: String, name: String, icon: String?): PlayItem? {
        val api = Session.api ?: return null
        val accountId = Session.account?.id ?: return null
        return PlayItem(name, api.liveUrl(id, Session.liveExtension()), WatchKind.LIVE, id, accountId, image = icon)
    }

    fun movieItem(id: String, title: String, image: String?, extension: String?): PlayItem? {
        val accountId = Session.account?.id ?: return null
        val ext = extension ?: "mp4"
        // A downloaded copy plays without internet.
        val url = Downloads.localUri(WatchKind.MOVIE, id) ?: Session.api?.movieUrl(id, ext) ?: return null
        return PlayItem(title, url, WatchKind.MOVIE, id, accountId, image = image, extension = ext)
    }

    fun episodeItem(context: Context, seriesId: String, seriesName: String, cover: String?, ep: Episode): PlayItem? {
        val accountId = Session.account?.id ?: return null
        val url = Downloads.localUri(WatchKind.EPISODE, ep.id) ?: Session.api?.episodeUrl(ep.id, ep.extension) ?: return null
        return PlayItem(
            title = "$seriesName · " + context.getString(R.string.episode_code, ep.season, ep.number),
            url = url,
            kind = WatchKind.EPISODE,
            streamId = ep.id,
            accountId = accountId,
            subtitle = tidyEpisodeTitle(ep.title, seriesName),
            image = ep.image ?: cover,
            extension = ep.extension,
            seriesId = seriesId,
            seriesName = seriesName,
        )
    }

    /** Builds a play item from a history entry (for Continue watching and split screen). */
    fun entryItem(entry: WatchEntry): PlayItem? {
        return when (entry.kind) {
            WatchKind.LIVE -> liveItem(entry.streamId, entry.title, entry.image)
            WatchKind.MOVIE -> movieItem(entry.streamId, entry.title, entry.image, entry.extension)
            WatchKind.EPISODE -> {
                val accountId = Session.account?.id ?: return null
                val ext = entry.extension ?: "mp4"
                val url = Downloads.localUri(WatchKind.EPISODE, entry.streamId)
                    ?: Session.api?.episodeUrl(entry.streamId, ext) ?: return null
                PlayItem(
                    entry.title, url, WatchKind.EPISODE, entry.streamId, accountId,
                    subtitle = entry.subtitle, image = entry.image, extension = ext,
                    seriesId = entry.seriesId, seriesName = entry.seriesName,
                )
            }
        }
    }

    /** A finished download, played from the file. */
    fun downloadItem(item: DownloadItem): PlayItem? {
        val url = Downloads.localUri(item.kind, item.streamId) ?: return null
        val title = if (item.kind == WatchKind.EPISODE) {
            "${item.seriesName ?: item.title} · ${item.subtitle?.substringBefore(" · ") ?: ""}".trimEnd(' ', '·')
        } else {
            item.title
        }
        return PlayItem(
            title = title,
            url = url,
            kind = item.kind,
            streamId = item.streamId,
            accountId = item.accountId,
            subtitle = item.subtitle?.substringAfter(" · ", "")?.ifBlank { null },
            image = item.image,
            extension = item.extension,
            seriesId = item.seriesId,
            seriesName = item.seriesName,
        )
    }

    // ------------------------------------------------------------- starting playback

    fun channels(context: Context, channels: List<StreamItem>, index: Int) {
        start(context, channels.mapNotNull { liveItem(it) }, index)
    }

    fun recentChannels(context: Context, entries: List<WatchEntry>, index: Int) {
        start(context, entries.mapNotNull { liveItem(it.streamId, it.title, it.image) }, index)
    }

    fun movie(context: Context, id: String, title: String, image: String?, extension: String?, fromStart: Boolean = false) {
        val item = movieItem(id, title, image, extension) ?: return
        start(context, listOf(item), 0, fromStart)
    }

    fun episodes(
        context: Context,
        seriesId: String,
        seriesName: String,
        cover: String?,
        episodes: List<Episode>,
        index: Int,
        fromStart: Boolean = false,
    ) {
        start(context, episodes.mapNotNull { episodeItem(context, seriesId, seriesName, cover, it) }, index, fromStart)
    }

    /** Plays downloaded files; for episodes, the other downloaded episodes of that series follow. */
    fun downloads(context: Context, item: DownloadItem) {
        val queue = if (item.kind == WatchKind.EPISODE && item.seriesId != null) {
            Downloads.items
                .filter { it.kind == WatchKind.EPISODE && it.seriesId == item.seriesId && Downloads.status[it.key]?.state == DownloadState.DONE }
                .sortedWith(compareBy({ it.season }, { it.episode }))
        } else {
            listOf(item)
        }
        val playItems = queue.mapNotNull { downloadItem(it) }
        start(context, playItems, playItems.indexOfFirst { it.streamId == item.streamId }.coerceAtLeast(0))
    }

    /** Continue a history entry with one tap. For episodes the rest of the season is queued too. */
    suspend fun resume(context: Context, entry: WatchEntry) {
        when (entry.kind) {
            WatchKind.LIVE -> recentChannels(context, listOf(entry), 0)
            WatchKind.MOVIE -> movie(context, entry.streamId, entry.title, entry.image, entry.extension)
            WatchKind.EPISODE -> {
                val seriesId = entry.seriesId
                val info = if (seriesId != null) {
                    withTimeoutOrNull(8_000) { runCatching { Session.requireApi().seriesInfo(seriesId) }.getOrNull() }
                } else null
                val season = info?.seasons?.values?.firstOrNull { list -> list.any { it.id == entry.streamId } }
                val index = season?.indexOfFirst { it.id == entry.streamId } ?: -1
                if (season != null && index >= 0 && seriesId != null) {
                    episodes(context, seriesId, entry.seriesName ?: info?.name ?: entry.title, info?.cover ?: entry.image, season, index)
                } else {
                    // Offline or the provider didn't answer: play just this one (from the download if there is one).
                    val item = entryItem(entry) ?: return
                    start(context, listOf(item), 0)
                }
            }
        }
    }
}

/** Download helpers that need the open account. */
object DownloadActions {
    /** On Android 13+ the progress notification needs permission; ask once when downloading. */
    private fun askForNotifications(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val activity = context as? android.app.Activity ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    fun movie(context: Context, id: String, title: String, image: String?, extension: String?): Boolean {
        askForNotifications(context)
        val api = Session.api ?: return false
        val accountId = Session.account?.id ?: return false
        val ext = extension ?: "mp4"
        val ok = Downloads.enqueue(api.movieUrl(id, ext), WatchKind.MOVIE, id, accountId, title, null, image, ext)
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.download_started, title) else context.getString(R.string.download_error),
            Toast.LENGTH_SHORT,
        ).show()
        return ok
    }

    /** Downloads an episode straight from Continue watching. */
    fun entry(context: Context, entry: WatchEntry): Boolean {
        val api = Session.api ?: return false
        val accountId = Session.account?.id ?: return false
        val ext = entry.extension ?: "mp4"
        val code = Regex("S(\\d+)\\s*E(\\d+)").find(entry.subtitle.orEmpty())
        val ok = Downloads.enqueue(
            url = api.episodeUrl(entry.streamId, ext),
            kind = WatchKind.EPISODE,
            streamId = entry.streamId,
            accountId = accountId,
            title = entry.seriesName ?: entry.title,
            subtitle = entry.subtitle,
            image = entry.image,
            extension = ext,
            seriesId = entry.seriesId,
            seriesName = entry.seriesName ?: entry.title,
            season = code?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            episode = code?.groupValues?.get(2)?.toIntOrNull() ?: 0,
        )
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.download_started, entry.subtitle ?: entry.title) else context.getString(R.string.download_error),
            Toast.LENGTH_SHORT,
        ).show()
        return ok
    }

    /** Downloads every episode in the list that isn't already downloaded or downloading. */
    fun episodes(context: Context, seriesId: String, seriesName: String, cover: String?, episodes: List<Episode>): Int {
        askForNotifications(context)
        val api = Session.api ?: return 0
        val accountId = Session.account?.id ?: return 0
        var started = 0
        var failed = false
        for (ep in episodes) {
            val existing = Downloads.find(WatchKind.EPISODE, ep.id)
            if (existing != null && Downloads.status[existing.key]?.state != DownloadState.FAILED) continue
            val code = context.getString(R.string.episode_code, ep.season, ep.number)
            val name = tidyEpisodeTitle(ep.title, seriesName)
            val ok = Downloads.enqueue(
                url = api.episodeUrl(ep.id, ep.extension),
                kind = WatchKind.EPISODE,
                streamId = ep.id,
                accountId = accountId,
                title = seriesName,
                subtitle = listOfNotNull(code, name).joinToString(" · "),
                image = ep.image ?: cover,
                extension = ep.extension,
                seriesId = seriesId,
                seriesName = seriesName,
                season = ep.season,
                episode = ep.number,
            )
            if (ok) started++ else failed = true
        }
        val message = when {
            started > 0 -> context.resources.getQuantityString(R.plurals.episodes_downloading, started, started)
            failed -> context.getString(R.string.download_error)
            else -> context.getString(R.string.season_all_downloaded)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return started
    }
}
