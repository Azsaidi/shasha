package com.azsaidi.shasha.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
    /** Only used by downloads started with Android's download service in older versions. */
    val systemId: Long,
    val fileName: String,
    val addedAt: Long,
    val url: String = "",
    val retries: Int = 0,
    val done: Boolean = false,
    /** 0 = no failure; an HTTP code; or one of the REASON_* values. */
    val failedReason: Int = 0,
    val total: Long = 0,
) {
    val key: String get() = WatchEntry.keyOf(kind, streamId)

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("id", streamId).put("acc", accountId).put("title", title)
        .put("subtitle", subtitle ?: "").put("image", image ?: "").put("ext", extension)
        .put("seriesId", seriesId ?: "").put("seriesName", seriesName ?: "")
        .put("season", season).put("episode", episode).put("sys", systemId)
        .put("file", fileName).put("at", addedAt).put("url", url).put("retries", retries)
        .put("done", done).put("failed", failedReason).put("total", total)

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
                done = o.optBoolean("done"),
                failedReason = o.optInt("failed"),
                total = o.optLong("total"),
            )
        }.getOrNull()
    }
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadStatus(
    val state: DownloadState,
    val bytes: Long,
    val total: Long,
    /** For failures: an HTTP status code, or one of the REASON_* values. */
    val reason: Int = 0,
) {
    /** 0..1, or null when the size isn't known yet. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val active: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED
}

/**
 * The download queue. The actual downloading happens in [DownloadService], a foreground service
 * that keeps going after you leave the app. Downloads run one at a time and pause while
 * something streams on plans that allow only one connection; partial files are resumed.
 */
object Downloads {
    const val REASON_DROPPED = -2
    const val REASON_NO_SPACE = -3
    const val REASON_NEEDS_REDOWNLOAD = -4

    private lateinit var app: Context

    val items = mutableStateListOf<DownloadItem>()
    val status = mutableStateMapOf<String, DownloadStatus>()

    /** True while something streams from the provider. */
    var streamingNow by mutableStateOf(false)
        private set

    private val prefs get() = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        items.clear()
        items.addAll(load().map { migrateLegacy(it) })
        status.clear()
        for (item in items) status[item.key] = initialStatus(item)
        save()
        kick()
    }

    /** Downloads started by older versions used Android's download service; adopt or re-queue them. */
    private fun migrateLegacy(item: DownloadItem): DownloadItem {
        if (item.systemId <= 0 || item.done) return item
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        var successful = false
        runCatching {
            manager?.query(DownloadManager.Query().setFilterById(item.systemId))?.use { c ->
                if (c.moveToFirst()) {
                    successful = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                }
            }
        }
        val f = file(item)
        if (successful && f != null && f.exists() && f.length() > 0) return item.copy(systemId = 0, done = true, total = f.length())
        runCatching { manager?.remove(item.systemId) }
        return if (item.url.isBlank()) {
            item.copy(systemId = 0, failedReason = REASON_NEEDS_REDOWNLOAD)
        } else {
            item.copy(systemId = 0, failedReason = 0, retries = 0)
        }
    }

    private fun initialStatus(item: DownloadItem): DownloadStatus {
        val size = file(item)?.takeIf { it.exists() }?.length() ?: 0L
        return when {
            item.done && size > 0 -> DownloadStatus(DownloadState.DONE, size, size)
            item.done -> DownloadStatus(DownloadState.FAILED, 0, 0, REASON_NEEDS_REDOWNLOAD)
            item.failedReason != 0 -> DownloadStatus(DownloadState.FAILED, size, item.total, item.failedReason)
            else -> DownloadStatus(DownloadState.QUEUED, size, item.total)
        }
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

    private fun dir(): File? = app.getExternalFilesDir("downloads") ?: File(app.filesDir, "downloads").apply { mkdirs() }

    fun file(item: DownloadItem): File? = dir()?.let { File(it, item.fileName) }

    fun find(kind: WatchKind, streamId: String): DownloadItem? {
        val key = WatchEntry.keyOf(kind, streamId)
        return items.firstOrNull { it.key == key }
    }

    fun findByKey(key: String): DownloadItem? = items.firstOrNull { it.key == key }

    /** A file:// address for a finished download, so playback works without internet. */
    fun localUri(kind: WatchKind, streamId: String): String? {
        val item = find(kind, streamId) ?: return null
        if (status[item.key]?.state != DownloadState.DONE) return null
        val f = file(item) ?: return null
        return if (f.exists() && f.length() > 0) Uri.fromFile(f).toString() else null
    }

    private fun onePerAccount(): Boolean = (Session.auth?.maxConnections?.toIntOrNull() ?: 1) <= 1

    /** Called by the players. On one-connection plans the download pauses and resumes afterwards. */
    fun setStreaming(active: Boolean) {
        streamingNow = active
        if (active && onePerAccount()) DownloadService.pauseCurrent() else if (!active) kick()
    }

    /** True if the service may download now. */
    fun canDownloadNow(): Boolean = !(streamingNow && onePerAccount())

    /** The next item to download, or null. */
    fun next(): DownloadItem? {
        if (!canDownloadNow()) return null
        return items.filter { status[it.key]?.state == DownloadState.QUEUED || status[it.key]?.state == DownloadState.PAUSED }
            .minByOrNull { it.addedAt }
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
        kick()
        return true
    }

    /** Tries a failed download again, keeping what was already downloaded. */
    fun retry(item: DownloadItem) {
        replace(item.copy(retries = 0, failedReason = 0))
        status[item.key] = DownloadStatus(DownloadState.QUEUED, file(item)?.length() ?: 0, item.total)
        save()
        kick()
    }

    /** Cancels (if running) and deletes the file. */
    fun remove(item: DownloadItem) {
        DownloadService.cancel(item.key)
        file(item)?.delete()
        items.removeAll { it.key == item.key }
        status.remove(item.key)
        save()
    }

    fun usedBytes(): Long = dir()?.listFiles()?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = dir()?.usableSpace ?: 0L

    private fun replace(item: DownloadItem) {
        val index = items.indexOfFirst { it.key == item.key }
        if (index >= 0) items[index] = item
    }

    /** Starts the background service if there is something to download. */
    fun kick() {
        if (!::app.isInitialized || next() == null) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
    }

    // --- called by DownloadService (on the main thread) ---

    internal fun markProgress(key: String, bytes: Long, total: Long) {
        val item = findByKey(key) ?: return
        val next = DownloadStatus(DownloadState.RUNNING, bytes, total)
        if (status[key] != next) status[key] = next
        if (total > 0 && item.total != total) replace(item.copy(total = total))
    }

    internal fun markPaused(key: String) {
        val item = findByKey(key) ?: return
        status[key] = DownloadStatus(DownloadState.PAUSED, file(item)?.length() ?: 0, item.total)
    }

    internal fun markDone(key: String, size: Long) {
        val item = findByKey(key) ?: return
        replace(item.copy(done = true, failedReason = 0, total = size))
        status[key] = DownloadStatus(DownloadState.DONE, size, size)
        save()
    }

    internal fun markFailed(key: String, reason: Int) {
        val item = findByKey(key) ?: return
        replace(item.copy(failedReason = reason))
        status[key] = DownloadStatus(DownloadState.FAILED, file(item)?.length() ?: 0, item.total, reason)
        save()
    }
}

/** "1.2 GB" / "350 MB" for storage figures. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}
