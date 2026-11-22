package com.azsaidi.shasha.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.azsaidi.shasha.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Continue watching" row on the Google TV / Android TV home screen (Android's Watch Next
 * list). It mirrors the app's own Continue watching shelf for the open account: films and
 * episodes part-watched, and the episode up next. Choosing one opens the app and resumes it.
 */
object WatchNext {
    const val ACTION_RESUME = "com.azsaidi.shasha.RESUME"
    const val EXTRA_KEY = "key"
    const val EXTRA_ACCOUNT = "account"

    private const val MAX = 15
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pending: Job? = null
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Called whenever the history or the open account changes; a burst of changes makes one update. */
    fun requestSync() {
        val context = app ?: return
        if (!supported(context)) return
        pending?.cancel()
        pending = scope.launch {
            delay(2_000)
            val accountId = Session.account?.id
            val entries = if (accountId == null) emptyList() else Session.continueWatching().take(MAX)
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { sync(context, accountId, entries) }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun sync(context: Context, accountId: String?, entries: List<WatchEntry>) {
        val resolver = context.contentResolver
        // Only this app's programmes come back.
        val existing = HashMap<String, WatchNextProgram>()
        resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                val id = program.internalProviderId
                if (id == null || existing.containsKey(id)) {
                    resolver.delete(TvContractCompat.buildWatchNextProgramUri(program.id), null, null)
                } else {
                    existing[id] = program
                }
            }
        }
        val wanted = LinkedHashMap<String, WatchEntry>()
        if (accountId != null) for (e in entries) wanted["$accountId|${e.key}"] = e

        for ((id, program) in existing) {
            if (id !in wanted) resolver.delete(TvContractCompat.buildWatchNextProgramUri(program.id), null, null)
        }
        for ((id, entry) in wanted) {
            val old = existing[id]
            // Removed from the row by the person and not watched since: leave it out.
            if (old != null && !old.isBrowsable && old.lastEngagementTimeUtcMillis >= entry.updatedAt) continue
            val values = program(context, accountId!!, id, entry).toContentValues()
            if (old != null && old.isBrowsable) {
                resolver.update(TvContractCompat.buildWatchNextProgramUri(old.id), values, null, null)
            } else {
                if (old != null) resolver.delete(TvContractCompat.buildWatchNextProgramUri(old.id), null, null)
                resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun program(context: Context, accountId: String, id: String, entry: WatchEntry): WatchNextProgram {
        val episode = entry.kind == WatchKind.EPISODE
        val builder = WatchNextProgram.Builder()
        builder.setType(
            if (episode) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE,
        )
            .setWatchNextType(
                if (entry.upNext || entry.position <= 0) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            )
            .setLastEngagementTimeUtcMillis(entry.updatedAt)
            .setTitle(if (episode) entry.seriesName ?: entry.title else entry.title)
            .setInternalProviderId(id)
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_RESUME)
                    .putExtra(EXTRA_KEY, entry.key)
                    .putExtra(EXTRA_ACCOUNT, accountId),
            )
        if (episode) {
            // Subtitle reads "S2 E1 · Episode name".
            val subtitle = entry.subtitle.orEmpty()
            val name = subtitle.substringAfter(" · ", "").ifBlank { null }
            name?.let { builder.setEpisodeTitle(it) }
            Regex("""S(\d+)\s*E(\d+)""").find(subtitle)?.let { m ->
                builder.setSeasonNumber(m.groupValues[1].toInt())
                builder.setEpisodeNumber(m.groupValues[2].toInt())
            }
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
        } else {
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3)
        }
        entry.image?.takeIf { it.isNotBlank() }?.let { runCatching { builder.setPosterArtUri(Uri.parse(it)) } }
        if (!entry.upNext && entry.position > 0 && entry.duration > 0) {
            builder.setLastPlaybackPositionMillis(entry.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            builder.setDurationMillis(entry.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }
}
