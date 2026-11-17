package com.azsaidi.shasha.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Library
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.player.Playback
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Home tab: continue watching, recent channels, favorites, new arrivals and search across everything. */
@Composable
fun HomePage(onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    val context = LocalContext.current
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

    Column(Modifier.fillMaxSize()) {
        // Header: search + refresh
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTv) {
                Text(
                    Session.account?.name ?: "Home",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = query.isNotEmpty(),
                    onClick = { showSearch = true },
                    label = { Text(query.ifEmpty { "Search everything" }, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search channels, movies, series") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            IconButton(onClick = { refreshContent(context) }, modifier = Modifier.focusRing(CircleShape)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh content")
            }
        }

        val q = query.trim()
        if (q.length >= 2) {
            SearchResults(q, itemsOf(ContentType.LIVE), itemsOf(ContentType.MOVIE), itemsOf(ContentType.SERIES), libraries.size < 3, onOpen)
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
            hint = "Channels, movies, series",
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
    var toRemove by remember { mutableStateOf<WatchEntry?>(null) }

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

        val nothingYet = continueWatching.isEmpty() && recentChannels.isEmpty() && favorites.isEmpty()
        if (nothingYet) {
            item {
                Text(
                    "Things you watch and favorite will show up here, so you can get back to them in one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            shelf("Continue watching") {
                items(continueWatching, key = { it.key }) { entry ->
                    ContinueCard(
                        entry,
                        onClick = { scope.launch { Playback.resume(context, entry) } },
                        onLongClick = { toRemove = entry },
                    )
                }
            }
        }

        if (recentChannels.isNotEmpty()) {
            shelf("Recently watched channels") {
                itemsIndexed(recentChannels, key = { _, e -> e.key }) { index, entry ->
                    ChannelTile(entry.title, entry.image) { Playback.recentChannels(context, recentChannels, index) }
                }
            }
        }

        if (favoriteChannels.isNotEmpty()) {
            shelf("Favorite channels") {
                items(favoriteChannels, key = { it.id }) { item ->
                    ChannelTile(item.name, item.icon) { onOpen(item, favoriteChannels) }
                }
            }
        }

        if (favoriteVod.isNotEmpty()) {
            shelf("Favorite movies & series") {
                items(favoriteVod, key = { "${it.type}-${it.id}" }) { item ->
                    PosterCard(item, Modifier.width(118.dp)) { onOpen(item, favoriteVod) }
                }
            }
        }

        if (newMovies.isNotEmpty()) {
            shelf("Recently added movies") {
                items(newMovies) { item ->
                    PosterCard(item, Modifier.width(118.dp)) { onOpen(item, newMovies) }
                }
            }
        }

        if (newSeries.isNotEmpty()) {
            shelf("Recently added series") {
                items(newSeries) { item ->
                    PosterCard(item, Modifier.width(118.dp)) { onOpen(item, newSeries) }
                }
            }
        }
    }

    toRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove from Continue watching?") },
            text = { Text(entry.title) },
            confirmButton = {
                TextButton(onClick = {
                    Session.removeFromHistory(entry)
                    toRemove = null
                }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { toRemove = null }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun LazyListScope.shelf(title: String, content: LazyListScope.() -> Unit) {
    item(key = "shelf-$title") {
        Column(Modifier.padding(top = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(entry: WatchEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .width(210.dp)
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.05f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                    "UP NEXT",
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
        if (!entry.upNext && entry.progress > 0f) {
            LinearProgressIndicator(
                progress = { entry.progress },
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
        val detail = listOfNotNull(
            entry.subtitle,
            if (!entry.upNext && entry.duration > 0) "${formatTime((entry.duration - entry.position).coerceAtLeast(0))} left" else null,
        ).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelTile(name: String, logo: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.05f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ExpiryBanner() {
    val exp = Session.auth?.expDate ?: return
    val now = System.currentTimeMillis() / 1000
    val daysLeft = (exp - now) / 86_400
    if (daysLeft > 7) return
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(exp * 1000))
    val text = when {
        exp <= now -> "Your subscription expired on $date. Renew it with your provider to keep watching."
        daysLeft < 1 -> "Your subscription ends today ($date)."
        else -> "Your subscription ends in $daysLeft day${if (daysLeft == 1L) "" else "s"} ($date)."
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
    val foundChannels = remember(query, channels) { channels.filter { it.name.contains(query, ignoreCase = true) }.take(40) }
    val foundMovies = remember(query, movies) { movies.filter { it.name.contains(query, ignoreCase = true) }.take(40) }
    val foundSeries = remember(query, series) { series.filter { it.name.contains(query, ignoreCase = true) }.take(40) }

    if (foundChannels.isEmpty() && foundMovies.isEmpty() && foundSeries.isEmpty()) {
        if (stillLoading) LoadingView("Searching…") else MessageView("Nothing found for \"$query\"")
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (foundMovies.isNotEmpty()) {
            shelf("Movies") {
                items(foundMovies) { item ->
                    PosterCard(item, Modifier.width(118.dp)) { onOpen(item, foundMovies) }
                }
            }
        }
        if (foundSeries.isNotEmpty()) {
            shelf("Series") {
                items(foundSeries) { item ->
                    PosterCard(item, Modifier.width(118.dp)) { onOpen(item, foundSeries) }
                }
            }
        }
        if (foundChannels.isNotEmpty()) {
            item(key = "channels-title") {
                Text(
                    "Channels",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(foundChannels) { item ->
                LiveRow(item) { onOpen(item, foundChannels) }
            }
        }
    }
}
