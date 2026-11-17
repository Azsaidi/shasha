package com.azsaidi.shasha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.BuildConfig
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.Category
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Library
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.player.PlayItem
import com.azsaidi.shasha.player.launchPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HomeTab(val label: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Filled.LiveTv),
    MOVIES("Movies", Icons.Filled.Movie),
    SERIES("Series", Icons.Filled.VideoLibrary),
    FAVORITES("Favorites", Icons.Filled.Favorite),
    ACCOUNT("Account", Icons.Filled.AccountCircle),
}

internal val ButtonShape = RoundedCornerShape(24.dp)
private val FavoriteYellow = Color(0xFFFFC107)
private val FavoritePink = Color(0xFFFF5A79)

@Composable
fun HomeScreen(
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onSwitchAccount: () -> Unit,
    onAccountRemoved: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIVE) }
    val holder = rememberSaveableStateHolder()

    val open: (StreamItem, List<StreamItem>) -> Unit = { item, list ->
        when (item.type) {
            ContentType.LIVE -> {
                val api = Session.api
                if (api != null) {
                    val ext = Session.liveExtension()
                    val channels = list.filter { it.type == ContentType.LIVE }
                    launchPlayer(
                        context,
                        channels.map { PlayItem(it.name, api.liveUrl(it.id, ext), isLive = true) },
                        channels.indexOf(item),
                    )
                }
            }
            ContentType.MOVIE -> onOpenMovie(item.id)
            ContentType.SERIES -> onOpenSeries(item.id)
        }
    }

    val isTv = rememberIsTv()
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val firstFocus = remember { FocusRequester() }

    val content: @Composable () -> Unit = {
        holder.SaveableStateProvider(tab.name) {
            when (tab) {
                HomeTab.LIVE -> ContentTab(ContentType.LIVE, open)
                HomeTab.MOVIES -> ContentTab(ContentType.MOVIE, open)
                HomeTab.SERIES -> ContentTab(ContentType.SERIES, open)
                HomeTab.FAVORITES -> FavoritesTab(open)
                HomeTab.ACCOUNT -> AccountTab(onSwitchAccount, onAccountRemoved)
            }
        }
    }

    if (wide) {
        // TV and tablets: side rail, which is natural to reach with the remote's left button.
        Scaffold { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = if (isTv) 12.dp else 0.dp)
            ) {
                NavigationRail(
                    header = {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                        )
                    },
                ) {
                    Spacer(Modifier.weight(1f))
                    HomeTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) },
                            modifier = Modifier
                                .then(if (t == tab) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusRing(RoundedCornerShape(16.dp))
                                .onFocusChanged { if (isTv && it.hasFocus) tab = t },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) { content() }
            }
        }
        if (isTv) {
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    HomeTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) { content() }
        }
    }
}

@Composable
private fun ContentTab(type: ContentType, onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    var state by remember(type) {
        mutableStateOf<Load<Library>>(Session.cached(type)?.let { Load.Ready(it) } ?: Load.Loading)
    }
    var retry by remember { mutableIntStateOf(0) }
    val version = Session.cacheVersion

    LaunchedEffect(type, version, retry) {
        val cached = Session.cached(type)
        if (cached != null) {
            state = Load.Ready(cached)
            return@LaunchedEffect
        }
        state = Load.Loading
        state = try {
            Load.Ready(Session.load(type))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }

    when (val s = state) {
        is Load.Loading -> LoadingView("Loading ${type.noun}…")
        is Load.Failed -> MessageView("Couldn't load ${type.noun}", s.message, "Try again") { retry++ }
        is Load.Ready -> Browser(type, s.value.first, s.value.second, onOpen)
    }
}

@Composable
private fun Browser(
    type: ContentType,
    categories: List<Category>,
    items: List<StreamItem>,
    onOpen: (StreamItem, List<StreamItem>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var showCategories by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val isTv = rememberIsTv()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val counts = remember(items) { items.groupingBy { it.categoryId ?: "" }.eachCount() }
    val categoryList = remember(categories, counts) {
        categories.mapNotNull { c -> counts[c.id]?.let { c to it } }
    }
    val filtered = remember(items, query, categoryId) {
        val q = query.trim()
        items.filter {
            (categoryId.isEmpty() || it.categoryId == categoryId) &&
                (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }
    }
    val categoryName = categories.firstOrNull { it.id == categoryId }?.name ?: "All ${type.noun}"

    fun scrollTop() {
        scope.launch {
            if (type == ContentType.LIVE) listState.scrollToItem(0) else gridState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!isTv) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; scrollTop() },
                placeholder = { Text("Search ${type.noun}") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; scrollTop() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (isTv) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTv) {
                // A text field would pop the TV keyboard every time focus passed over it,
                // so on TV search opens in a dialog instead.
                FilterChip(
                    selected = query.isNotEmpty(),
                    onClick = { showSearch = true },
                    label = { Text(query.ifEmpty { "Search" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = ""; scrollTop() },
                        modifier = Modifier.focusRing(CircleShape),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            FilterChip(
                selected = categoryId.isNotEmpty(),
                onClick = { showCategories = true },
                label = { Text(categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .focusRing(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${filtered.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (filtered.isEmpty()) {
            MessageView(
                if (items.isEmpty()) "No ${type.noun} on this account" else "Nothing matches",
                if (items.isEmpty()) null else "Try a different search or category.",
            )
        } else if (type == ContentType.LIVE) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered) { item -> LiveRow(item) { onOpen(item, filtered) } }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered.size) { index ->
                    val item = filtered[index]
                    PosterCard(item) { onOpen(item, filtered) }
                }
            }
        }
    }

    if (showSearch) {
        SearchDialog(
            initial = query,
            hint = "Search ${type.noun}",
            onSearch = {
                query = it
                showSearch = false
                scrollTop()
            },
            onDismiss = { showSearch = false },
        )
    }

    if (showCategories) {
        CategoryDialog(
            categories = categoryList,
            total = items.size,
            allLabel = "All ${type.noun}",
            showFilter = !isTv,
            selected = categoryId,
            onSelect = {
                categoryId = it
                showCategories = false
                scrollTop()
            },
            onDismiss = { showCategories = false },
        )
    }
}

@Composable
private fun SearchDialog(initial: String, hint: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSearch(text.trim()) }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text("Cancel")
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun CategoryDialog(
    categories: List<Pair<Category, Int>>,
    total: Int,
    allLabel: String,
    showFilter: Boolean,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(categories, filter) {
        val f = filter.trim()
        if (f.isEmpty()) categories else categories.filter { it.first.name.contains(f, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) { Text("Close") }
        },
        title = { Text("Categories") },
        text = {
            Column {
                if (showFilter) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Find a category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (filter.isBlank()) {
                        item { CategoryRow(allLabel, total, selected.isEmpty()) { onSelect("") } }
                    }
                    items(shown) { (category, count) ->
                        CategoryRow(category.name, count, selected == category.id) { onSelect(category.id) }
                    }
                }
            }
        },
    )
}

@Composable
private fun CategoryRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = color,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveRow(item: StreamItem, onClick: () -> Unit) {
    val favorite = Session.isFavorite(item)
    ListItem(
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Thumb(item.icon, item.name, Modifier.size(width = 64.dp, height = 44.dp)) },
        trailingContent = {
            IconButton(
                onClick = { Session.toggleFavorite(item) },
                modifier = Modifier.focusRing(CircleShape),
            ) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (favorite) FavoriteYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PosterCard(item: StreamItem, onClick: () -> Unit) {
    val favorite = Session.isFavorite(item)
    Column(
        Modifier
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.06f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box {
            Thumb(
                item.icon,
                item.name,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                crop = true,
                corner = 10.dp,
            )
            if (favorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = FavoritePink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
            val rating = item.rating?.toDoubleOrNull()
            if (rating != null && rating > 0) {
                Text(
                    String.format(Locale.US, "★ %.1f", rating),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
    }
}

@Composable
private fun FavoritesTab(onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    val favorites = Session.favorites
    if (favorites.isEmpty()) {
        MessageView(
            "No favorites yet",
            "Tap the star next to a channel, or the heart on a movie or series, to keep it here.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        for (type in ContentType.entries) {
            val group = favorites.filter { it.type == type }
            if (group.isEmpty()) continue
            item(key = "header-${type.name}") {
                Text(
                    type.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(group, key = { "${it.type}-${it.id}" }) { item ->
                ListItem(
                    headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        if (type == ContentType.LIVE) {
                            Thumb(item.icon, item.name, Modifier.size(width = 64.dp, height = 44.dp))
                        } else {
                            Thumb(item.icon, item.name, Modifier.size(width = 44.dp, height = 66.dp), crop = true)
                        }
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { Session.toggleFavorite(item) },
                            modifier = Modifier.focusRing(CircleShape),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from favorites")
                        }
                    },
                    modifier = Modifier
                        .focusRing(RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(item, group) },
                )
            }
        }
    }
}

@Composable
private fun AccountTab(onSwitchAccount: () -> Unit, onAccountRemoved: () -> Unit) {
    val account = Session.account
    if (account == null) {
        MessageView("No account open", null, "Choose account", onSwitchAccount)
        return
    }
    val auth = Session.auth
    var format by remember { mutableStateOf(Store.liveFormat) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(account.name, style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                InfoLine("Server", account.server)
                InfoLine("Username", account.username)
                InfoLine("Status", auth?.status?.replaceFirstChar { it.uppercase() } ?: "Checking…")
                InfoLine(
                    "Expires",
                    when {
                        auth == null -> "Checking…"
                        auth.expDate == null -> "Never"
                        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(auth.expDate * 1000))
                    },
                )
                if (auth != null) {
                    InfoLine("Connections", "${auth.activeConnections ?: "?"} of ${auth.maxConnections ?: "?"} in use")
                }
            }
        }

        Text("Live channel format", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "ts" to "TS", "m3u8" to "HLS").forEach { (value, label) ->
                FilterChip(
                    selected = format == value,
                    onClick = {
                        format = value
                        Store.liveFormat = value
                    },
                    label = { Text(label) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
            }
        }
        Text(
            "If channels won't play or keep buffering, try switching between TS and HLS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(4.dp))
        OutlinedButton(onClick = { Session.clearCache() }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reload channels and library")
        }
        OutlinedButton(onClick = onSwitchAccount, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Switch or add account")
        }
        TextButton(
            onClick = { confirmRemove = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().focusRing(ButtonShape),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Remove this account")
        }
        Text(
            "Shasha ${BuildConfig.VERSION_NAME} · free, no ads",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${account.name}?") },
            text = { Text("Its login details and favorites will be deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    Store.removeAccount(account.id)
                    Session.close()
                    onAccountRemoved()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
