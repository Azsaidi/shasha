package com.azsaidi.shasha.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Library
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.displaySubtitle
import com.azsaidi.shasha.player.Playback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Home tab: continue watching, recent channels, favourites, new arrivals and search across everything. */
@Composable
fun HomePage(onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    val isTv = rememberIsTv()
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    // Catalogues used for "Recently added" and search; usually instant from the disk cache.
    val libraries = remember { mutableStateMapOf<ContentType, Library>() }
    val version = Session.cacheVersion
    LaunchedEffect(version) {
        libraries.clear()
        for (type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) {
            runCatching { Session.load(type) }.getOrNull()?.let { libraries[type] = it }
        }
    }
    val hideAdult = Session.hideAdult
    fun itemsOf(type: ContentType): List<StreamItem> =
        libraries[type]?.let { if (hideAdult) Session.visible(it).second else it.second }.orEmpty()

    // Opening something from search results also saves the search.
    val openFromHome: (StreamItem, List<StreamItem>) -> Unit = { item, list ->
        if (query.isNotBlank()) Session.rememberSearch(query)
        onOpen(item, list)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (isTv) {
                Text(
                    Session.account?.name ?: stringResource(R.string.tab_home),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                )
                FilterChip(
                    selected = query.isNotEmpty(),
                    onClick = { showSearch = true },
                    label = { Text(query.ifEmpty { stringResource(R.string.search_everything) }, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .focusRing(RoundedCornerShape(8.dp)),
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            } else {
                PhoneSearchField(
                    query = query,
                    hint = stringResource(R.string.home_search_hint),
                    onQueryChange = { query = it },
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = { refreshContent() }, modifier = Modifier.focusRing(CircleShape)) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_content))
            }
        }

        val q = query.trim()
        if (q.length >= 2) {
            SearchResults(q, itemsOf(ContentType.LIVE), itemsOf(ContentType.MOVIE), itemsOf(ContentType.SERIES), libraries.size < 3, openFromHome)
        } else {
            HomeShelves(
                movies = itemsOf(ContentType.MOVIE),
                series = itemsOf(ContentType.SERIES),
                onOpen = onOpen,
            )
        }
    }

    if (showSearch) {
        SearchDialog(
            initial = query,
            hint = stringResource(R.string.home_search_hint),
            onSearch = {
                query = it
                showSearch = false
            },
            onDismiss = { showSearch = false },
        )
    }
}

@Composable
private fun HomeShelves(
    movies: List<StreamItem>,
    series: List<StreamItem>,
    onOpen: (StreamItem, List<StreamItem>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory("home")

    // Reading the state lists here makes the page update as soon as something is watched.
    val history = Session.history.toList()
    val continueWatching = remember(history) { Session.continueWatching() }
    val recentChannels = remember(history) { Session.recentChannels() }
    val favorites = Session.favorites.toList()
    val favoriteChannels = favorites.filter { it.type == ContentType.LIVE }
    val favoriteVod = favorites.filter { it.type != ContentType.LIVE }
    val newMovies = remember(movies) { movies.filter { (it.added ?: 0) > 0 }.sortedByDescending { it.added }.take(20) }
    val newSeries = remember(series) { series.filter { (it.added ?: 0) > 0 }.sortedByDescending { it.added }.take(20) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ExpiryBanner() }

        if (continueWatching.isEmpty() && recentChannels.isEmpty() && favorites.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            shelf(R.string.continue_watching) {
                items(continueWatching, key = { it.key }) { entry ->
                    ContinueCard(
                        entry,
                        Modifier.remembered(memory, "cw-${entry.key}"),
                        onClick = {
                            memory.opened("cw-${entry.key}")
                            scope.launch { Playback.resume(context, entry) }
                        },
                        onLongClick = {
                            showHistoryMenu(context, entry) {
                                memory.opened("cw-${entry.key}")
                                scope.launch { Playback.resume(context, entry) }
                            }
                        },
                    )
                }
            }
        }

        if (recentChannels.isNotEmpty()) {
            shelf(R.string.recent_channels) {
                itemsIndexed(recentChannels, key = { _, e -> e.key }) { index, entry ->
                    ChannelTile(
                        entry.title,
                        entry.image,
                        Modifier.remembered(memory, "rc-${entry.key}"),
                        onClick = {
                            memory.opened("rc-${entry.key}")
                            Playback.recentChannels(context, recentChannels, index)
                        },
                        onLongClick = {
                            showHistoryMenu(context, entry) {
                                memory.opened("rc-${entry.key}")
                                Playback.recentChannels(context, recentChannels, index)
                            }
                        },
                    )
                }
            }
        }

        if (favoriteChannels.isNotEmpty()) {
            shelf(R.string.favourite_channels) {
                items(favoriteChannels, key = { it.id }) { item ->
                    ChannelTile(
                        item.name,
                        item.icon,
                        Modifier.remembered(memory, "fc-${item.id}"),
                        onClick = {
                            memory.opened("fc-${item.id}")
                            onOpen(item, favoriteChannels)
                        },
                        onLongClick = {
                            showStreamItemMenu(context, item) {
                                memory.opened("fc-${item.id}")
                                onOpen(item, favoriteChannels)
                            }
                        },
                    )
                }
            }
        }

        if (favoriteVod.isNotEmpty()) {
            shelf(R.string.favourite_vod) {
                items(favoriteVod, key = { "${it.type}-${it.id}" }) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, favoriteVod) }
                }
            }
        }

        if (newMovies.isNotEmpty()) {
            shelf(R.string.new_movies) {
                items(newMovies) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, newMovies) }
                }
            }
        }

        if (newSeries.isNotEmpty()) {
            shelf(R.string.new_series) {
                items(newSeries) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, newSeries) }
                }
            }
        }
    }

}

internal fun LazyListScope.shelf(@StringRes title: Int, content: LazyListScope.() -> Unit) {
    item(key = "shelf-$title") {
        Column(Modifier.padding(top = 14.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: WatchEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier
            .width(210.dp)
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.04f)
            .clip(RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box {
            Thumb(
                entry.image,
                entry.title,
                Modifier
                    .fillMaxWidth()
                    .height(118.dp),
                crop = true,
                corner = 10.dp,
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
            }
            if (entry.upNext) {
                Text(
                    stringResource(R.string.up_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // Same layout for every card (bar and both text lines always present) so rows don't shift.
        run {
            LinearProgressIndicator(
                progress = { if (entry.upNext) 0f else entry.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val remaining = if (!entry.upNext && entry.duration > 0) {
            stringResource(R.string.time_left, formatTime((entry.duration - entry.position).coerceAtLeast(0)))
        } else {
            null
        }
        val detail = listOfNotNull(entry.displaySubtitle(), remaining).joinToString(" · ")
        Text(
            detail.ifEmpty { " " },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(name: String, logo: String?, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier
            .width(132.dp)
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.04f)
            .clip(RoundedCornerShape(12.dp))
            .pressable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Thumb(
            logo,
            name,
            Modifier
                .fillMaxWidth()
                .height(78.dp),
            corner = 10.dp,
        )
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ExpiryBanner() {
    val exp = Session.auth?.expDate ?: return
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val daysLeft = (exp - now) / 86_400
    if (daysLeft > 7) return
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(exp * 1000))
    val text = when {
        exp <= now -> stringResource(R.string.expired, date)
        daysLeft < 1 -> stringResource(R.string.expires_today, date)
        else -> context.resources.getQuantityString(R.plurals.expires_in, daysLeft.toInt(), daysLeft.toInt(), date)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    channels: List<StreamItem>,
    movies: List<StreamItem>,
    series: List<StreamItem>,
    stillLoading: Boolean,
    onOpen: (StreamItem, List<StreamItem>) -> Unit,
) {
    val memory = rememberFocusMemory("home-search")
    val foundChannels = remember(query, channels) { channels.filter { it.name.contains(query, ignoreCase = true) }.take(40) }
    val foundMovies = remember(query, movies) { movies.filter { it.name.contains(query, ignoreCase = true) }.take(40) }
    val foundSeries = remember(query, series) { series.filter { it.name.contains(query, ignoreCase = true) }.take(40) }

    if (foundChannels.isEmpty() && foundMovies.isEmpty() && foundSeries.isEmpty()) {
        if (stillLoading) LoadingView(stringResource(R.string.searching)) else MessageView(stringResource(R.string.nothing_found, query))
        return
    }

    val channelsTitle = stringResource(R.string.tab_live)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (foundMovies.isNotEmpty()) {
            shelf(R.string.tab_movies) {
                items(foundMovies) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, foundMovies) }
                }
            }
        }
        if (foundSeries.isNotEmpty()) {
            shelf(R.string.tab_series) {
                items(foundSeries) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, foundSeries) }
                }
            }
        }
        if (foundChannels.isNotEmpty()) {
            item(key = "channels-title") { SectionTitle(channelsTitle) }
            items(foundChannels) { item ->
                LiveRow(item, memory) { onOpen(item, foundChannels) }
            }
        }
    }
}
