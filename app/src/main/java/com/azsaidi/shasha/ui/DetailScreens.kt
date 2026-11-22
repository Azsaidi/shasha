package com.azsaidi.shasha.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.azsaidi.shasha.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Episode
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.SeriesInfo
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.VodInfo
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.data.tidyEpisodeTitle
import com.azsaidi.shasha.player.Playback
import com.azsaidi.shasha.player.DownloadActions
import com.azsaidi.shasha.data.DownloadItem
import com.azsaidi.shasha.data.Downloads
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.focus.focusProperties
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
private fun FavoriteButton(item: StreamItem?) {
    if (item == null) return
    val favorite = Session.isFavorite(item)
    OutlinedButton(onClick = { Session.toggleFavorite(item) }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
        Icon(
            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = if (favorite) FavoritePink else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (favorite) R.string.in_favourites else R.string.add_favourite))
    }
}

@Composable
private fun MetaText(parts: List<String?>) {
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  ")
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .focusRing(RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded },
    )
}

@Composable
private fun PlayButton(label: String, focus: FocusRequester, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .focusRequester(focus)
            .focusRing(ButtonShape),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MovieScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = remember(id) { Session.findItem(ContentType.MOVIE, id) }
    var info by remember { mutableStateOf<VodInfo?>(null) }
    val isTv = rememberIsTv()
    val playFocus = remember { FocusRequester() }
    val memory = rememberFocusMemory("movie-$id")

    LaunchedEffect(id) {
        info = try {
            Session.requireApi().vodInfo(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    if (isTv) LaunchedEffect(Unit) { if (!FocusMemory.hasPending()) runCatching { playFocus.requestFocus() } }

    // Reads the history state list, so this updates when you come back from the player.
    val entry = Session.historyEntry(WatchKind.MOVIE, id)
    val resumeAt = entry?.takeIf { !it.finished && it.position > 10_000 }?.position ?: 0L
    val title = item?.name ?: info?.name ?: stringResource(R.string.movie)
    val image = info?.cover ?: item?.icon

    fun play(fromStart: Boolean) {
        memory.opened("play")
        Playback.movie(context, id, title, item?.icon ?: image, info?.extension ?: item?.extension, fromStart)
    }

    DetailScaffold(title, onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Thumb(
                    image,
                    title,
                    Modifier
                        .width(130.dp)
                        .aspectRatio(2f / 3f),
                    crop = true,
                    corner = 12.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    MetaText(listOf(info?.releaseDate?.take(4), info?.duration, (info?.rating ?: item?.rating)?.let { "★ $it" }))
                    info?.genre?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry?.finished == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.watched), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            PlayButton(
                if (resumeAt > 0) stringResource(R.string.resume_from, formatTime(resumeAt)) else stringResource(R.string.play),
                playFocus,
                Modifier.remembered(memory, "play"),
            ) { play(fromStart = false) }
            if (resumeAt > 0 && entry != null && entry.duration > 0) {
                WatchBar(entry.progress, Modifier.fillMaxWidth().height(4.dp))
            }
            if (resumeAt > 0) {
                TextButton(onClick = { play(fromStart = true) }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
                    Text(stringResource(R.string.start_over))
                }
            }
            FavoriteButton(item)
            MovieDownloadButton(
                onDownload = { DownloadActions.movie(context, id, title, item?.icon ?: image, info?.extension ?: item?.extension) },
                item = Downloads.find(WatchKind.MOVIE, id),
            )

            info?.plot?.let { ExpandableText(it) }
            info?.director?.let {
                Text(stringResource(R.string.director, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            info?.cast?.let {
                Text(stringResource(R.string.cast, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What the big button on a series page should do. */
internal data class SeriesAction(val label: String, val season: Int, val index: Int, val fromStart: Boolean)

internal fun seriesAction(context: Context, info: SeriesInfo, latest: WatchEntry?): SeriesAction? {
    val seasons = info.seasons.filterValues { it.isNotEmpty() }
    val first = seasons.entries.firstOrNull() ?: return null
    fun code(ep: Episode) = context.getString(R.string.episode_code, ep.season, ep.number)
    if (latest != null) {
        for ((season, episodes) in seasons) {
            val index = episodes.indexOfFirst { it.id == latest.streamId }
            if (index < 0) continue
            val ep = episodes[index]
            if (!latest.finished) {
                val label = if (latest.position > 10_000) {
                    context.getString(R.string.resume_ep, code(ep), formatTime(latest.position))
                } else {
                    context.getString(R.string.play_ep, code(ep))
                }
                return SeriesAction(label, season, index, fromStart = false)
            }
            // Finished: move on to the next episode, crossing into the next season if needed.
            if (index + 1 < episodes.size) {
                return SeriesAction(context.getString(R.string.play_next_ep, code(episodes[index + 1])), season, index + 1, fromStart = true)
            }
            val keys = seasons.keys.toList()
            val nextSeason = keys.getOrNull(keys.indexOf(season) + 1)
            if (nextSeason != null) {
                val next = seasons.getValue(nextSeason).first()
                return SeriesAction(context.getString(R.string.play_next_ep, code(next)), nextSeason, 0, fromStart = true)
            }
            return SeriesAction(context.getString(R.string.watch_again, code(first.value[0])), first.key, 0, fromStart = true)
        }
    }
    return SeriesAction(context.getString(R.string.play_ep, code(first.value[0])), first.key, 0, fromStart = true)
}

@Composable
fun SeriesScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = remember(id) { Session.findItem(ContentType.SERIES, id) }
    var state by remember { mutableStateOf<Load<SeriesInfo>>(Load.Loading) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedSeason by rememberSaveable { mutableIntStateOf(-1) }
    val isTv = rememberIsTv()
    val playFocus = remember { FocusRequester() }
    val memory = rememberFocusMemory("series-$id")

    LaunchedEffect(id, retry) {
        state = Load.Loading
        state = try {
            Load.Ready(Session.requireApi().seriesInfo(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(context, e))
        }
    }

    val title = item?.name ?: (state as? Load.Ready<SeriesInfo>)?.value?.name ?: stringResource(R.string.tab_series)

    DetailScaffold(title, onBack) { padding ->
        when (val s = state) {
            is Load.Loading -> Column(Modifier.padding(padding)) { LoadingView(stringResource(R.string.loading_episodes)) }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                MessageView(stringResource(R.string.couldnt_load_series), s.message, stringResource(R.string.try_again)) { retry++ }
            }
            is Load.Ready -> {
                val info = s.value
                val cover = info.cover ?: item?.icon
                val latest = Session.latestEpisode(id)
                val action = seriesAction(context, info, latest)
                val seasons = info.seasons.keys.toList()
                val season = when {
                    selectedSeason in seasons -> selectedSeason
                    action != null -> action.season
                    else -> seasons.firstOrNull() ?: -1
                }
                val episodes = info.seasons[season].orEmpty()

                fun playSeason(seasonNumber: Int, index: Int, fromStart: Boolean) {
                    val list = info.seasons[seasonNumber].orEmpty()
                    if (list.isEmpty()) return
                    Playback.episodes(context, id, title, cover, list, index, fromStart)
                }

                if (isTv) LaunchedEffect(Unit) { if (!FocusMemory.hasPending()) runCatching { playFocus.requestFocus() } }

                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SeriesHeader(item, info, title)
                            if (action != null) {
                                PlayButton(action.label, playFocus, Modifier.remembered(memory, "play")) {
                                    memory.opened("play")
                                    playSeason(action.season, action.index, action.fromStart)
                                }
                            }
                            FavoriteButton(item)
                            info.plot?.let { ExpandableText(it) }
                        }
                    }
                    if (seasons.isEmpty()) {
                        item { MessageView(stringResource(R.string.no_episodes)) }
                    } else {
                        if (seasons.size > 1) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(seasons) { number ->
                                        FilterChip(
                                            selected = number == season,
                                            onClick = { selectedSeason = number },
                                            label = { Text(stringResource(R.string.season_n, number)) },
                                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    stringResource(R.string.season_n, season),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { DownloadActions.episodes(context, id, title, cover, episodes) },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .fillMaxWidth()
                                    .focusRing(ButtonShape),
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.download_season, season))
                            }
                        }
                        itemsIndexed(episodes) { index, episode ->
                            val watch = Session.historyEntry(WatchKind.EPISODE, episode.id)
                            val play = {
                                memory.opened("ep-${episode.id}")
                                playSeason(season, index, fromStart = false)
                            }
                            EpisodeRow(
                                episode,
                                cover,
                                watch,
                                Modifier.remembered(memory, "ep-${episode.id}"),
                                seriesName = title,
                                download = Downloads.find(WatchKind.EPISODE, episode.id),
                                onDownload = { DownloadActions.episodes(context, id, title, cover, listOf(episode)) },
                                onLongClick = { showEpisodeMenu(context, episode, id, title, cover, play) },
                                onClick = play,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(item: StreamItem?, info: SeriesInfo, title: String) {
    Row {
        Thumb(
            info.cover ?: item?.icon,
            title,
            Modifier
                .width(120.dp)
                .aspectRatio(2f / 3f),
            crop = true,
            corner = 12.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            MetaText(
                listOf(
                    info.releaseDate?.take(4),
                    LocalContext.current.resources.getQuantityString(R.plurals.seasons, info.seasons.size, info.seasons.size),
                    (info.rating ?: item?.rating)?.let { "★ $it" },
                )
            )
            info.genre?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: Episode,
    fallbackImage: String?,
    watch: WatchEntry?,
    modifier: Modifier,
    seriesName: String,
    download: DownloadItem?,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val rowFocus = remember { FocusRequester() }
    val downloadFocus = remember { FocusRequester() }
    ListItem(
        headlineContent = {
            val name = tidyEpisodeTitle(episode.title, seriesName) ?: stringResource(R.string.episode_n, episode.number)
            Text("${episode.number}. $name", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val details = listOfNotNull(
                episode.duration,
                when {
                    watch == null -> null
                    watch.finished -> stringResource(R.string.watched)
                    watch.position > 10_000 -> stringResource(R.string.stopped_at, formatTime(watch.position))
                    else -> null
                },
                episode.plot,
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Box {
                Thumb(
                    episode.image ?: fallbackImage,
                    "${episode.number}",
                    Modifier.size(width = 104.dp, height = 58.dp),
                    crop = true,
                )
                if (watch != null && !watch.finished && watch.progress > 0f) {
                    WatchBar(
                        watch.progress,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .width(104.dp)
                            .height(3.dp),
                    )
                }
                if (watch?.finished == true) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.watched),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp),
                    )
                }
            }
        },
        trailingContent = {
            EpisodeDownloadIcon(
                download,
                onDownload,
                Modifier
                    .focusRequester(downloadFocus)
                    .focusProperties { start = rowFocus },
            )
        },
        modifier = modifier
            .focusRequester(rowFocus)
            .focusProperties { end = downloadFocus }
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .pressable(onClick = onClick, onLongClick = onLongClick),
    )
}
