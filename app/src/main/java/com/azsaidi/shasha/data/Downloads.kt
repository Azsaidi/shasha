package com.azsaidi.shasha.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A film or episode saved (or waiting to be saved) on the device for offline viewing. */
data class DownloadItem(
    val kind: WatchKind,
    val streamId: String,
    val accountId: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val extension: String,
    val seriesId: String?,
    val seriesName: String?,
    val season: Int,
    val episode: Int,
    /** Android download id; 0 while it's still waiting its turn. */
    val systemId: Long,
    val fileName: String,
    val addedAt: Long,
    val url: String = "",
    val retries: Int = 0,
) {
    val key: String get() = WatchEntry.keyOf(kind, streamId)

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("id", streamId).put("acc", accountId).put("title", title)
        .put("subtitle", subtitle ?: "").put("image", image ?: "").put("ext", extension)
        .put("seriesId", seriesId ?: "").put("seriesName", seriesName ?: "")
        .put("season", season).put("episode", episode).put("sys", systemId)
        .put("file", fileName).put("at", addedAt).put("url", url).put("retries", retries)

    companion object {
        fun fromJson(o: JSONObject): DownloadItem? = runCatching {
            DownloadItem(
                kind = WatchKind.valueOf(o.getString("kind")),
                streamId = o.getString("id"),
                accountId = o.getString("acc"),
                title = o.optString("title"),
                subtitle = o.optString("subtitle").ifBlank { null },
                image = o.optString("image").ifBlank { null },
                extension = o.optString("ext").ifBlank { "mp4" },
                seriesId = o.optString("seriesId").ifBlank { null },
                seriesName = o.optString("seriesName").ifBlank { null },
                season = o.optInt("season"),
                episode = o.optInt("episode"),
                systemId = o.optLong("sys"),
                fileName = o.getString("file"),
                addedAt = o.optLong("at"),
                url = o.optString("url"),
                retries = o.optInt("retries"),
            )
        }.getOrNull()
    }
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadStatus(
    val state: DownloadState,
    val bytes: Long,
    val total: Long,
    /** For failures: an HTTP status code, or one of DownloadManager's ERROR_* codes. */
    val reason: Int = 0,
) {
    /** 0..1, or null when the size isn't known yet. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val active: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED
}

/**
 * Downloads use Android's own download service, so they keep going in the background and survive
 * the app being closed. They run strictly one at a time: IPTV plans often allow only one
 * connection, and parallel downloads (or downloading while streaming) get refused by the server.
 */
object Downloads {
    private const val MAX_AUTO_RETRIES = 5

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var polling: Job? = null

    val items = mutableStateListOf<DownloadItem>()
    val status = mutableStateMapOf<String, DownloadStatus>()

    /** True while something is streaming from the provider; new downloads wait on 1-connection plans. */
    var streaming by mutableStateOf(false)
        private set

    private val manager: DownloadManager
        get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val prefs get() = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        items.clear()
        items.addAll(load())
        refreshStatus()
        pump()
        startPolling()
    }

    private fun load(): List<DownloadItem> = runCatching {
        val arr = JSONArray(prefs.getString("items", null) ?: "[]")
        (0 until arr.length()).mapNotNull { DownloadItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun dir(): File? = app.getExternalFilesDir("downloads")

    private fun file(item: DownloadItem): File? = dir()?.let { File(it, item.fileName) }

    fun find(kind: WatchKind, streamId: String): DownloadItem? {
        val key = WatchEntry.keyOf(kind, streamId)
        return items.firstOrNull { it.key == key }
    }

    /** A file:// address for a finished download, so playback works without internet. */
    fun localUri(kind: WatchKind, streamId: String): String? {
        val item = find(kind, streamId) ?: return null
        if (status[item.key]?.state != DownloadState.DONE) return null
        val f = file(item) ?: return null
        return if (f.exists() && f.length() > 0) Uri.fromFile(f).toString() else null
    }

    /** Called by the players: streaming from the provider holds back the next download. */
    fun setStreaming(active: Boolean) {
        streaming = active
        if (!active) pump()
    }

    /** Adds a download to the queue; returns false if it couldn't be queued. */
    fun enqueue(
        url: String,
        kind: WatchKind,
        streamId: String,
        accountId: String,
        title: String,
        subtitle: String?,
        image: String?,
        extension: String,
        seriesId: String? = null,
        seriesName: String? = null,
        season: Int = 0,
        episode: Int = 0,
    ): Boolean {
        val existing = find(kind, streamId)
        if (existing != null) {
            if (status[existing.key]?.state != DownloadState.FAILED) return true
            remove(existing)
        }
        if (dir() == null) return false
        val item = DownloadItem(
            kind, streamId, accountId, title, subtitle, image, extension, seriesId, seriesName,
            season, episode, systemId = 0,
            fileName = "${kind.prefix}_${streamId}_${System.currentTimeMillis()}.$extension",
            addedAt = System.currentTimeMillis(),
            url = url,
        )
        items.add(item)
        status[item.key] = DownloadStatus(DownloadState.QUEUED, 0, 0)
        save()
        pump()
        startPolling()
        return true
    }

    /** Tries a failed download again (from the start). */
    fun retry(item: DownloadItem) {
        runCatching { if (item.systemId > 0) manager.remove(item.systemId) }
        file(item)?.delete()
        replace(item.copy(systemId = 0, retries = 0))
        status[item.key] = DownloadStatus(DownloadState.QUEUED, 0, 0)
        save()
        pump()
        startPolling()
    }

    /** Cancels (if still running) and deletes the file. */
    fun remove(item: DownloadItem) {
        runCatching { if (item.systemId > 0) manager.remove(item.systemId) }
        file(item)?.delete()
        items.removeAll { it.key == item.key }
        status.remove(item.key)
        save()
        pump()
    }

    fun usedBytes(): Long = dir()?.listFiles()?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = dir()?.usableSpace ?: 0L

    private fun replace(item: DownloadItem) {
        val index = items.indexOfFirst { it.key == item.key }
        if (index >= 0) items[index] = item
    }

    private fun onePerAccount(): Boolean = (Session.auth?.maxConnections?.toIntOrNull() ?: 1) <= 1

    /** Starts the next waiting download if nothing else is downloading. */
    private fun pump() {
        if (!::app.isInitialized) return
        val busy = items.any { it.systemId > 0 && status[it.key]?.active == true }
        if (busy) return
        if (streaming && onePerAccount()) return
        val next = items.filter { it.systemId == 0L && status[it.key]?.state != DownloadState.FAILED }
            .minByOrNull { it.addedAt } ?: return
        if (next.url.isBlank()) {
            status[next.key] = DownloadStatus(DownloadState.FAILED, 0, 0)
            return
        }
        val id = runCatching {
            val request = DownloadManager.Request(Uri.parse(next.url))
                .addRequestHeader("User-Agent", Http.USER_AGENT)
                .setTitle(if (next.subtitle != null) "${next.title} · ${next.subtitle}" else next.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(app, "downloads", next.fileName)
            manager.enqueue(request)
        }.getOrNull()
        if (id == null) {
            status[next.key] = DownloadStatus(DownloadState.FAILED, 0, 0)
            save()
            return
        }
        replace(next.copy(systemId = id))
        status[next.key] = DownloadStatus(DownloadState.QUEUED, 0, 0)
        save()
    }

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (true) {
                refreshStatus()
                pump()
                if (status.values.none { it.active }) break
                delay(1_000)
            }
        }
    }

    private fun refreshStatus() {
        if (items.isEmpty()) return
        val started = items.filter { it.systemId > 0 }
        val byId = started.associateBy { it.systemId }
        val seen = HashSet<Long>()
        if (byId.isNotEmpty()) {
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(*byId.keys.toLongArray()))?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                    val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    val bytesCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val item = byId[id] ?: continue
                        seen.add(id)
                        val state = when (c.getInt(statusCol)) {
                            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.DONE
                            DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                            DownloadManager.STATUS_PAUSED -> DownloadState.PAUSED
                            DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                            else -> DownloadState.QUEUED
                        }
                        val reason = if (state == DownloadState.FAILED) c.getInt(reasonCol) else 0
                        update(item, DownloadStatus(state, c.getLong(bytesCol), c.getLong(totalCol), reason))
                    }
                }
            }
        }
        for (item in items) {
            if (item.systemId == 0L) {
                // Still waiting its turn (unless it already failed to start).
                if (status[item.key]?.state != DownloadState.FAILED) {
                    val waiting = DownloadStatus(DownloadState.QUEUED, 0, 0)
                    if (status[item.key] != waiting) status[item.key] = waiting
                }
                continue
            }
            if (item.systemId in seen) continue
            // The system forgot about it: finished if the file is there, otherwise failed.
            val f = file(item)
            val done = f != null && f.exists() && f.length() > 0
            update(item, DownloadStatus(if (done) DownloadState.DONE else DownloadState.FAILED, f?.length() ?: 0, f?.length() ?: 0))
        }
    }

    private fun update(item: DownloadItem, next: DownloadStatus) {
        val previous = status[item.key]
        if (previous == next) return
        status[item.key] = next
        // A refused or dropped download usually works on a second try once the connection is free.
        if (next.state == DownloadState.FAILED && previous?.state != DownloadState.FAILED && item.retries < MAX_AUTO_RETRIES && next.reason != DownloadManager.ERROR_INSUFFICIENT_SPACE) {
            scope.launch {
                delay(15_000)
                val current = items.firstOrNull { it.key == item.key } ?: return@launch
                if (status[current.key]?.state != DownloadState.FAILED) return@launch
                runCatching { manager.remove(current.systemId) }
                file(current)?.delete()
                replace(current.copy(systemId = 0, retries = current.retries + 1))
                status[current.key] = DownloadStatus(DownloadState.QUEUED, 0, 0)
                save()
                pump()
                startPolling()
            }
        }
    }
}

/** "1.2 GB" / "350 MB" for storage figures. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}
