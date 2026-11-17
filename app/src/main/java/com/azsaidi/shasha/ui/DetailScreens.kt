package com.azsaidi.shasha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Episode
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.SeriesInfo
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.VodInfo
import com.azsaidi.shasha.player.PlayItem
import com.azsaidi.shasha.player.launchPlayer
import kotlinx.coroutines.CancellationException

private val FavoritePink = Color(0xFFFF5A79)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        Text(if (favorite) "In favorites" else "Add to favorites")
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
fun MovieScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = remember(id) { Session.findItem(ContentType.MOVIE, id) }
    var info by remember { mutableStateOf<VodInfo?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(id) {
        info = try {
            Session.requireApi().vodInfo(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val isTv = rememberIsTv()
    val playFocus = remember { FocusRequester() }
    if (isTv) LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    val accountId = Session.account?.id.orEmpty()
    val resumeKey = "movie:$accountId:$id"
    val saved = remember(resumeTick) { Store.position(resumeKey) }
    val title = item?.name ?: info?.name ?: "Movie"

    fun play(fromStart: Boolean) {
        val api = Session.api ?: return
        if (fromStart) Store.savePosition(resumeKey, 0)
        val ext = info?.extension ?: item?.extension ?: "mp4"
        launchPlayer(context, listOf(PlayItem(title, api.movieUrl(id, ext), isLive = false, resumeKey = resumeKey)), 0)
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
                    info?.cover ?: item?.icon,
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
                }
            }

            Button(
                onClick = { play(fromStart = false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .focusRequester(playFocus)
                    .focusRing(ButtonShape),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (saved > 0) "Resume from ${formatTime(saved)}" else "Play")
            }
            if (saved > 0) {
                TextButton(onClick = { play(fromStart = true) }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
                    Text("Start from the beginning")
                }
            }
            FavoriteButton(item)

            info?.plot?.let { ExpandableText(it) }
            info?.director?.let {
                Text("Director: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            info?.cast?.let {
                Text("Cast: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SeriesScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val item = remember(id) { Session.findItem(ContentType.SERIES, id) }
    var state by remember { mutableStateOf<Load<SeriesInfo>>(Load.Loading) }
    var retry by remember { mutableIntStateOf(0) }
    var selectedSeason by rememberSaveable { mutableIntStateOf(-1) }
    val isTv = rememberIsTv()
    val firstEpisode = remember { FocusRequester() }

    LaunchedEffect(id, retry) {
        state = Load.Loading
        state = try {
            Load.Ready(Session.requireApi().seriesInfo(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }

    val title = item?.name ?: (state as? Load.Ready<SeriesInfo>)?.value?.name ?: "Series"

    DetailScaffold(title, onBack) { padding ->
        when (val s = state) {
            is Load.Loading -> Column(Modifier.padding(padding)) { LoadingView("Loading episodes…") }
            is Load.Failed -> Column(Modifier.padding(padding)) {
                MessageView("Couldn't load this series", s.message, "Try again") { retry++ }
            }
            is Load.Ready -> {
                val info = s.value
                val seasons = info.seasons.keys.toList()
                val season = if (selectedSeason in seasons) selectedSeason else seasons.firstOrNull() ?: -1
                val episodes = info.seasons[season].orEmpty()

                if (isTv) {
                    LaunchedEffect(episodes) { runCatching { firstEpisode.requestFocus() } }
                }
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        SeriesHeader(item, info, title)
                    }
                    if (seasons.isEmpty()) {
                        item { MessageView("No episodes yet") }
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
                                            label = { Text("Season $number") },
                                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "Season $season",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        itemsIndexed(episodes) { index, episode ->
                            val rowModifier = if (index == 0) Modifier.focusRequester(firstEpisode) else Modifier
                            EpisodeRow(episode, info.cover ?: item?.icon, rowModifier) {
                                val api = Session.api ?: return@EpisodeRow
                                val accountId = Session.account?.id.orEmpty()
                                val queue = episodes.map { ep ->
                                    PlayItem(
                                        title = "S${ep.season} E${ep.number} · ${ep.title}",
                                        url = api.episodeUrl(ep.id, ep.extension),
                                        isLive = false,
                                        resumeKey = "ep:$accountId:${ep.id}",
                                    )
                                }
                                launchPlayer(context, queue, index)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(item: StreamItem?, info: SeriesInfo, title: String) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        "${info.seasons.size} season${if (info.seasons.size == 1) "" else "s"}",
                        (info.rating ?: item?.rating)?.let { "★ $it" },
                    )
                )
                info.genre?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FavoriteButton(item)
        info.plot?.let { ExpandableText(it) }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, fallbackImage: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text("${episode.number}. ${episode.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val details = listOfNotNull(episode.duration, episode.plot).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Thumb(
                episode.image ?: fallbackImage,
                "${episode.number}",
                Modifier.size(width = 104.dp, height = 58.dp),
                crop = true,
            )
        },
        modifier = modifier
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    )
}
