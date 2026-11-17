package com.azsaidi.shasha.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.DownloadItem
import com.azsaidi.shasha.data.DownloadState
import com.azsaidi.shasha.data.DownloadStatus
import com.azsaidi.shasha.data.Downloads
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.data.formatBytes
import com.azsaidi.shasha.player.Playback

/** Short status text for a download. */
fun downloadStatusText(context: Context, status: DownloadStatus?): String = when (status?.state) {
    null, DownloadState.QUEUED -> context.getString(R.string.download_queued)
    DownloadState.RUNNING -> status.fraction?.let { context.getString(R.string.downloading_pct, (it * 100).toInt()) }
        ?: context.getString(R.string.downloading)
    DownloadState.PAUSED -> context.getString(R.string.download_paused)
    DownloadState.DONE -> context.getString(R.string.downloaded)
    DownloadState.FAILED -> context.getString(R.string.download_failed)
}

/** Options for something that is downloaded or downloading. */
fun showDownloadMenu(context: Context, item: DownloadItem, retry: (() -> Unit)? = null) {
    val state = Downloads.status[item.key]?.state
    val actions = ArrayList<MenuAction>()
    if (state == DownloadState.DONE) {
        actions.add(MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow) { Playback.downloads(context, item) })
        actions.add(MenuAction(context.getString(R.string.delete_download), Icons.Filled.Delete) { Downloads.remove(item) })
    } else if (state == DownloadState.FAILED) {
        if (retry != null) actions.add(MenuAction(context.getString(R.string.retry_download), Icons.Filled.Refresh, retry))
        actions.add(MenuAction(context.getString(R.string.delete_download), Icons.Filled.Delete) { Downloads.remove(item) })
    } else {
        actions.add(MenuAction(context.getString(R.string.cancel_download), Icons.Filled.Close) { Downloads.remove(item) })
    }
    val title = listOfNotNull(item.title, item.subtitle).joinToString(" · ")
    ItemMenu.show(title, actions)
}

/** Download button for a film page; shows progress and turns into a "Downloaded" button. */
@Composable
fun MovieDownloadButton(onDownload: () -> Unit, item: DownloadItem?) {
    val context = LocalContext.current
    val status = item?.let { Downloads.status[it.key] }
    OutlinedButton(
        onClick = {
            if (item == null) onDownload() else showDownloadMenu(context, item, retry = onDownload)
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(ButtonShape),
    ) {
        when {
            item == null -> {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download))
            }
            status?.state == DownloadState.DONE -> {
                Icon(Icons.Filled.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.downloaded))
            }
            status?.state == DownloadState.FAILED -> {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_failed))
            }
            else -> {
                ProgressIcon(status)
                Spacer(Modifier.width(10.dp))
                Text(downloadStatusText(context, status))
            }
        }
    }
}

@Composable
private fun ProgressIcon(status: DownloadStatus?) {
    val fraction = status?.fraction
    if (fraction != null && status.state == DownloadState.RUNNING) {
        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** Small download control at the end of an episode row. */
@Composable
fun EpisodeDownloadIcon(
    item: DownloadItem?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status = item?.let { Downloads.status[it.key] }
    IconButton(
        onClick = { if (item == null) onDownload() else showDownloadMenu(context, item, retry = onDownload) },
        modifier = modifier.focusRing(CircleShape),
    ) {
        when {
            item == null -> Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download))
            status?.state == DownloadState.DONE ->
                Icon(Icons.Filled.DownloadDone, contentDescription = stringResource(R.string.downloaded), tint = MaterialTheme.colorScheme.secondary)
            status?.state == DownloadState.FAILED ->
                Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.download_failed), tint = MaterialTheme.colorScheme.error)
            else -> ProgressIcon(status)
        }
    }
}

/** The Downloads tab: films and series episodes saved on this device. */
@Composable
fun DownloadsTab() {
    val context = LocalContext.current
    val items = Downloads.items.toList()
    if (items.isEmpty()) {
        MessageView(stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_hint))
        return
    }
    // Recompute storage figures whenever a download's status changes.
    val statuses = Downloads.status.toMap()
    val used = remember(statuses, items.size) { Downloads.usedBytes() }
    val free = remember(statuses, items.size) { Downloads.freeBytes() }

    val films = items.filter { it.kind == WatchKind.MOVIE }.sortedByDescending { it.addedAt }
    val series = items.filter { it.kind == WatchKind.EPISODE }
        .groupBy { it.seriesId ?: it.title }
        .values
        .map { list -> list.sortedWith(compareBy({ it.season }, { it.episode })) }
        .sortedByDescending { list -> list.maxOf { it.addedAt } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "storage") {
            Text(
                stringResource(R.string.storage_line, formatBytes(used), formatBytes(free)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (films.isNotEmpty()) {
            item(key = "films-title") { SectionTitle(stringResource(R.string.tab_movies)) }
            items(films, key = { it.key }) { DownloadRow(context, it, it.title, null) }
        }
        for (group in series) {
            val first = group.first()
            item(key = "series-${first.seriesId ?: first.title}") { SectionTitle(first.seriesName ?: first.title) }
            items(group, key = { it.key }) { DownloadRow(context, it, it.subtitle ?: it.title, null) }
        }
    }
}

@Composable
private fun DownloadRow(context: Context, item: DownloadItem, title: String, subtitle: String?) {
    val status = Downloads.status[item.key]
    val rowFocus = remember { FocusRequester() }
    val deleteFocus = remember { FocusRequester() }
    val open = {
        if (status?.state == DownloadState.DONE) Playback.downloads(context, item) else showDownloadMenu(context, item)
    }
    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                Text(listOfNotNull(subtitle, downloadStatusText(context, status)).joinToString(" · "), maxLines = 1)
                if (status != null && status.active) {
                    Spacer(Modifier.height(6.dp))
                    val fraction = status.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                    }
                }
            }
        },
        leadingContent = {
            Box {
                Thumb(item.image, item.title, Modifier.size(width = 96.dp, height = 56.dp), crop = true)
            }
        },
        trailingContent = {
            IconButton(
                onClick = { Downloads.remove(item) },
                modifier = Modifier
                    .focusRequester(deleteFocus)
                    .focusProperties { start = rowFocus }
                    .focusRing(CircleShape),
            ) {
                Icon(
                    if (status?.active == true) Icons.Filled.Close else Icons.Filled.Delete,
                    contentDescription = stringResource(if (status?.active == true) R.string.cancel_download else R.string.delete_download),
                )
            }
        },
        modifier = Modifier
            .focusRequester(rowFocus)
            .focusProperties { end = deleteFocus }
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .pressable(onClick = open, onLongClick = { showDownloadMenu(context, item) }),
    )
}
