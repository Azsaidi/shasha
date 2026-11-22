package com.azsaidi.shasha.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.azsaidi.shasha.MainActivity
import com.azsaidi.shasha.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continue watching on the Google TV / Android TV home screen, mirroring the app's own shelf for the
 * open account (films and episodes part-watched, and the episode up next); choosing one opens the
 * app and resumes it. Two places:
 * - Shasha's own row (a "preview channel"), shown on the home screen like YouTube's. Google TV
 *   shows these for apps in the top of "Your apps".
 * - Android's Watch Next list (the older Android TV home's "Play next" row). Google TV's own
 *   Continue watching row only takes entries from apps Google has approved, so it stays empty there.
 */
object WatchNext {
    const val ACTION_RESUME = "com.azsaidi.shasha.RESUME"
    const val EXTRA_KEY = "key"
    const val EXTRA_ACCOUNT = "account"

    private const val MAX = 15
    private const val CHANNEL = "continue_watching"
    /** What the row last showed, so it's only rebuilt when that changes (not on every progress save). */
    private var lastRow: String? = null
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
                    runCatching { syncRow(context, accountId, entries) }
                    runCatching { sync(context, accountId, entries) }
                }
            }
        }
    }

    private fun openApp(context: Context): Intent = Intent(context, MainActivity::class.java)

    private fun resumeIntent(context: Context, accountId: String, entry: WatchEntry): Intent =
        openApp(context)
            .setAction(ACTION_RESUME)
            .putExtra(EXTRA_KEY, entry.key)
            .putExtra(EXTRA_ACCOUNT, accountId)

    /** Shasha's own "Continue watching" row on the home screen. */
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncRow(context: Context, accountId: String?, entries: List<WatchEntry>) {
        val helper = PreviewChannelHelper(context)
        val existing = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL }
        val channelId = existing?.id ?: run {
            if (accountId == null || entries.isEmpty()) return
            val logo = ContextCompat.getDrawable(context, R.drawable.ic_launcher)?.toBitmap(320, 320) ?: return
            lastRow = null
            // The app's first (default) row is shown on the home screen without asking.
            helper.publishDefaultChannel(
                PreviewChannel.Builder()
                    .setDisplayName(context.getString(R.string.continue_watching))
                    .setAppLinkIntentUri(Uri.parse(openApp(context).toUri(Intent.URI_INTENT_SCHEME)))
                    .setLogo(logo)
                    .setInternalProviderId(CHANNEL)
                    .build(),
            )
        }
        val row = "$accountId|" + entries.joinToString(",") { "${it.key}:${it.upNext}:${it.position / 60_000}" }
        if (row == lastRow) return
        // Rebuilt in order: the home screen shows programmes in the order they were added.
        val resolver = context.contentResolver
        val old = ArrayList<Long>()
        resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) old += PreviewProgram.fromCursor(cursor).id
        }
        old.forEach { helper.deletePreviewProgram(it) }
        if (accountId != null) {
            for (entry in entries) helper.publishPreviewProgram(previewProgram(context, channelId, accountId, entry))
        }
        lastRow = row
    }

    @SuppressLint("RestrictedApi")
    private fun previewProgram(context: Context, channelId: Long, accountId: String, entry: WatchEntry): PreviewProgram {
        val episode = entry.kind == WatchKind.EPISODE
        val builder = PreviewProgram.Builder()
        builder.setChannelId(channelId)
            .setType(if (episode) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
            .setTitle(if (episode) entry.seriesName ?: entry.title else entry.title)
            .setInternalProviderId("$accountId|${entry.key}")
            .setIntent(resumeIntent(context, accountId, entry))
        if (episode) {
            val subtitle = entry.subtitle.orEmpty()
            // "S2 E1 · Episode name".
            builder.setEpisodeTitle(subtitle.ifBlank { null })
            builder.setDescription(subtitle.ifBlank { null })
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
            .setIntent(resumeIntent(context, accountId, entry))
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
