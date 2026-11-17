package com.azsaidi.shasha.player

import android.content.Context
import android.content.Intent
import com.azsaidi.shasha.data.Episode
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
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

/** Builds play queues for each kind of content and starts the player. */
object Playback {

    private fun start(context: Context, items: List<PlayItem>, index: Int, fromStart: Boolean = false) {
        if (items.isEmpty()) return
        PlayerQueue.items = items
        PlayerQueue.startIndex = index.coerceIn(0, items.lastIndex)
        PlayerQueue.fromStart = fromStart
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    fun channels(context: Context, channels: List<StreamItem>, index: Int) {
        val api = Session.api ?: return
        val accountId = Session.account?.id ?: return
        val ext = Session.liveExtension()
        val items = channels.map {
            PlayItem(it.name, api.liveUrl(it.id, ext), WatchKind.LIVE, it.id, accountId, image = it.icon)
        }
        start(context, items, index)
    }

    fun recentChannels(context: Context, entries: List<WatchEntry>, index: Int) {
        val api = Session.api ?: return
        val accountId = Session.account?.id ?: return
        val ext = Session.liveExtension()
        val items = entries.map {
            PlayItem(it.title, api.liveUrl(it.streamId, ext), WatchKind.LIVE, it.streamId, accountId, image = it.image)
        }
        start(context, items, index)
    }

    fun movie(context: Context, id: String, title: String, image: String?, extension: String?, fromStart: Boolean = false) {
        val api = Session.api ?: return
        val accountId = Session.account?.id ?: return
        val ext = extension ?: "mp4"
        start(
            context,
            listOf(PlayItem(title, api.movieUrl(id, ext), WatchKind.MOVIE, id, accountId, image = image, extension = ext)),
            0,
            fromStart,
        )
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
        val api = Session.api ?: return
        val accountId = Session.account?.id ?: return
        val items = episodes.map { ep ->
            PlayItem(
                title = "$seriesName · S${ep.season} E${ep.number}",
                url = api.episodeUrl(ep.id, ep.extension),
                kind = WatchKind.EPISODE,
                streamId = ep.id,
                accountId = accountId,
                subtitle = ep.title,
                image = ep.image ?: cover,
                extension = ep.extension,
                seriesId = seriesId,
                seriesName = seriesName,
            )
        }
        start(context, items, index, fromStart)
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
                    val api = Session.api ?: return
                    val accountId = Session.account?.id ?: return
                    val ext = entry.extension ?: "mp4"
                    start(
                        context,
                        listOf(
                            PlayItem(
                                entry.title, api.episodeUrl(entry.streamId, ext), WatchKind.EPISODE, entry.streamId, accountId,
                                subtitle = entry.subtitle, image = entry.image, extension = ext,
                                seriesId = entry.seriesId, seriesName = entry.seriesName,
                            )
                        ),
                        0,
                    )
                }
            }
        }
    }
}
