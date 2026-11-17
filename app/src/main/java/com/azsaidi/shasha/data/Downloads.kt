package com.azsaidi.shasha.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A film or episode saved on the device for offline viewing. */
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
    val systemId: Long,
    val fileName: String,
    val addedAt: Long,
) {
    val key: String get() = WatchEntry.keyOf(kind, streamId)

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("id", streamId).put("acc", accountId).put("title", title)
        .put("subtitle", subtitle ?: "").put("image", image ?: "").put("ext", extension)
        .put("seriesId", seriesId ?: "").put("seriesName", seriesName ?: "")
        .put("season", season).put("episode", episode).put("sys", systemId)
        .put("file", fileName).put("at", addedAt)

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
                systemId = o.getLong("sys"),
                fileName = o.getString("file"),
                addedAt = o.optLong("at"),
            )
        }.getOrNull()
    }
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadStatus(val state: DownloadState, val bytes: Long, val total: Long) {
    /** 0..1, or null when the size isn't known yet. */
    val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val active: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED
}

/**
 * Downloads use Android's own download service, so they keep going in the background, survive
 * the app being closed and resume after network drops. Files live in the app's own storage
 * (removed if the app is uninstalled).
 */
object Downloads {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var polling: Job? = null

    val items = mutableStateListOf<DownloadItem>()
    val status = mutableStateMapOf<String, DownloadStatus>()

    private val manager: DownloadManager
        get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val prefs get() = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        items.clear()
        items.addAll(load())
        refreshStatus()
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

    fun statusOf(kind: WatchKind, streamId: String): DownloadStatus? =
        status[WatchEntry.keyOf(kind, streamId)]

    /** A file:// address for a finished download, so playback works without internet. */
    fun localUri(kind: WatchKind, streamId: String): String? {
        val item = find(kind, streamId) ?: return null
        if (status[item.key]?.state != DownloadState.DONE) return null
        val f = file(item) ?: return null
        return if (f.exists() && f.length() > 0) Uri.fromFile(f).toString() else null
    }

    /** Starts a download; returns false if it couldn't be queued. */
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
            val st = status[existing.key]?.state
            if (st != DownloadState.FAILED) return true
            remove(existing)
        }
        if (dir() == null) return false
        val fileName = "${kind.prefix}_${streamId}_${System.currentTimeMillis()}.$extension"
        val id = runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("User-Agent", Http.USER_AGENT)
                .setTitle(if (subtitle != null) "$title · $subtitle" else title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(app, "downloads", fileName)
            manager.enqueue(request)
        }.getOrNull() ?: return false

        items.add(
            DownloadItem(
                kind, streamId, accountId, title, subtitle, image, extension,
                seriesId, seriesName, season, episode, id, fileName, System.currentTimeMillis(),
            )
        )
        status[WatchEntry.keyOf(kind, streamId)] = DownloadStatus(DownloadState.QUEUED, 0, 0)
        save()
        startPolling()
        return true
    }

    /** Cancels (if still running) and deletes the file. */
    fun remove(item: DownloadItem) {
        runCatching { manager.remove(item.systemId) }
        file(item)?.delete()
        items.removeAll { it.key == item.key }
        status.remove(item.key)
        save()
    }

    fun usedBytes(): Long = dir()?.listFiles()?.sumOf { it.length() } ?: 0L

    fun freeBytes(): Long = dir()?.usableSpace ?: 0L

    private fun startPolling() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (true) {
                refreshStatus()
                if (status.values.none { it.active }) break
                delay(1_000)
            }
        }
    }

    private fun refreshStatus() {
        if (items.isEmpty()) return
        val byId = items.associateBy { it.systemId }
        val seen = HashSet<Long>()
        runCatching {
            manager.query(DownloadManager.Query().setFilterById(*byId.keys.toLongArray()))?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
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
                    val next = DownloadStatus(state, c.getLong(bytesCol), c.getLong(totalCol))
                    if (status[item.key] != next) status[item.key] = next
                }
            }
        }
        // Entries the system no longer knows about: finished if the file is there, otherwise failed.
        for (item in items) {
            if (item.systemId in seen) continue
            val f = file(item)
            val done = f != null && f.exists() && f.length() > 0
            val next = DownloadStatus(if (done) DownloadState.DONE else DownloadState.FAILED, f?.length() ?: 0, f?.length() ?: 0)
            if (status[item.key] != next) status[item.key] = next
        }
    }
}

/** "1.2 GB" / "350 MB" for storage figures. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}
