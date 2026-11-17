package com.azsaidi.shasha.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.BuildConfig
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.Category
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Language
import com.azsaidi.shasha.data.Library
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.player.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp

private enum class HomeTab(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    LIVE(R.string.tab_live, Icons.Filled.LiveTv),
    MOVIES(R.string.tab_movies, Icons.Filled.Movie),
    SERIES(R.string.tab_series, Icons.Filled.VideoLibrary),
    FAVOURITES(R.string.tab_favourites, Icons.Filled.Favorite),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

enum class SortOrder(@StringRes val label: Int) {
    DEFAULT(R.string.sort_default),
    ADDED(R.string.sort_added),
    NAME(R.string.sort_name),
    RATING(R.string.sort_rating),
}

internal val ButtonShape = RoundedCornerShape(24.dp)
internal val FavoriteYellow = Color(0xFFFFC107)
internal val FavoritePink = Color(0xFFFF5A79)

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Adds or removes a favourite and says so, for long-press on TV and phones. */
internal fun toggleFavouriteWithToast(context: Context, item: StreamItem) {
    val wasFavourite = Session.isFavorite(item)
    Session.toggleFavorite(item)
    val text = context.getString(if (wasFavourite) R.string.removed_from_favourites else R.string.added_to_favourites, item.name)
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

@Composable
fun HomeScreen(
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onSwitchAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onAccountRemoved: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    val holder = rememberSaveableStateHolder()

    val open: (StreamItem, List<StreamItem>) -> Unit = { item, list ->
        when (item.type) {
            ContentType.LIVE -> {
                val channels = list.filter { it.type == ContentType.LIVE }
                Playback.channels(context, channels, channels.indexOf(item).coerceAtLeast(0))
            }
            ContentType.MOVIE -> onOpenMovie(item.id)
            ContentType.SERIES -> onOpenSeries(item.id)
        }
    }

    val isTv = rememberIsTv()
    val wide = isTv || LocalConfiguration.current.screenWidthDp >= 600
    val railFocus = remember { FocusRequester() }

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            RefreshBanner()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                holder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        HomeTab.HOME -> HomePage(open)
                        HomeTab.LIVE -> ContentTab(ContentType.LIVE, open)
                        HomeTab.MOVIES -> ContentTab(ContentType.MOVIE, open)
                        HomeTab.SERIES -> ContentTab(ContentType.SERIES, open)
                        HomeTab.FAVOURITES -> FavouritesTab(open)
                        HomeTab.SETTINGS -> SettingsTab(onSwitchAccount, onEditAccount, onAccountRemoved)
                    }
                }
            }
        }
    }

    if (wide) {
        // TV and tablets: side rail, reached with the remote's left button. Tabs change on OK,
        // not on focus, so coming back from the player never jumps to another tab.
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
                            label = { Text(stringResource(t.label), maxLines = 1) },
                            modifier = Modifier
                                .then(if (t == tab) Modifier.focusRequester(railFocus) else Modifier)
                                .focusRing(RoundedCornerShape(16.dp)),
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
            LaunchedEffect(Unit) {
                if (!FocusMemory.hasPending()) runCatching { railFocus.requestFocus() }
            }
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
                            label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            alwaysShowLabel = false,
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

/** Throws away cached lists and downloads them again (progress shows in [RefreshBanner]). */
internal fun refreshContent() {
    Session.refreshAll()
}

@Composable
private fun RefreshBanner() {
    val state = Session.refresh ?: return
    val context = LocalContext.current
    val bytes = Session.downloadBytes[state.type] ?: 0L
    // Byte totals usually aren't known, so ease towards the end of each step as data arrives.
    val partial = if (state.done) 1f else (1 - exp(-bytes / 4_000_000.0)).toFloat() * 0.95f
    val target = if (state.done) 1f else ((state.step - 1) + partial) / state.total
    val progress by animateFloatAsState(target, label = "refreshProgress")
    val error = state.error
    val text = when {
        state.done && error != null -> stringResource(R.string.refresh_failed, friendlyError(context, error))
        state.done -> stringResource(R.string.refresh_done)
        else -> stringResource(R.string.refresh_progress, stringResource(state.type.titleRes), state.step, state.total) +
            " · " + stringResource(R.string.downloaded_mb, megabytes(bytes))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun ContentTab(type: ContentType, onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    val context = LocalContext.current
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
            Load.Failed(friendlyError(context, e))
        }
    }

    val noun = stringResource(type.nounRes)
    when (val s = state) {
        is Load.Loading -> {
            val bytes = Session.downloadBytes[type] ?: 0L
            LoadingView(
                stringResource(R.string.loading_x, noun),
                if (bytes > 0) stringResource(R.string.downloaded_mb, megabytes(bytes)) else null,
            )
        }
        is Load.Failed -> MessageView(stringResource(R.string.couldnt_load_x, noun), s.message, stringResource(R.string.try_again)) { retry++ }
        is Load.Ready -> {
            val hideAdult = Session.hideAdult
            val library = remember(s.value, hideAdult) { Session.visible(s.value) }
            Browser(type, library.first, library.second, onOpen)
        }
    }
}

internal fun List<StreamItem>.ordered(order: SortOrder): List<StreamItem> = when (order) {
    SortOrder.DEFAULT -> this
    SortOrder.ADDED -> sortedByDescending { it.added ?: 0L }
    SortOrder.NAME -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    SortOrder.RATING -> sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
}

/** Search box for phones, with recent searches shown while it is focused and empty. */
@Composable
internal fun PhoneSearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { Session.rememberSearch(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        )
        if (focused && query.isEmpty() && Session.searches.isNotEmpty()) {
            RecentSearchChips(onPick = onQueryChange)
        }
    }
}

@Composable
private fun RecentSearchChips(onPick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(Session.searches.toList()) { q ->
            AssistChip(
                onClick = { onPick(q) },
                label = { Text(q, maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
        item {
            TextButton(onClick = { Session.clearSearches() }) { Text(stringResource(R.string.clear)) }
        }
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
    var sort by rememberSaveable { mutableStateOf(SortOrder.DEFAULT) }
    var showCategories by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val isTv = rememberIsTv()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val memory = rememberFocusMemory("browser-${type.name}")
    val noun = stringResource(type.nounRes)

    val sortOptions = if (type == ContentType.LIVE) {
        listOf(SortOrder.DEFAULT, SortOrder.NAME)
    } else {
        listOf(SortOrder.DEFAULT, SortOrder.ADDED, SortOrder.NAME, SortOrder.RATING)
    }

    val counts = remember(items) { items.groupingBy { it.categoryId ?: "" }.eachCount() }
    val categoryList = remember(categories, counts) {
        categories.mapNotNull { c -> counts[c.id]?.let { c to it } }
    }
    val filtered = remember(items, query, categoryId, sort) {
        val q = query.trim()
        items.filter {
            (categoryId.isEmpty() || it.categoryId == categoryId) &&
                (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }.ordered(sort)
    }
    val categoryName = categories.firstOrNull { it.id == categoryId }?.name ?: stringResource(R.string.all_x, noun)

    fun scrollTop() {
        scope.launch {
            if (type == ContentType.LIVE) listState.scrollToItem(0) else gridState.scrollToItem(0)
        }
    }

    val openItem: (StreamItem) -> Unit = { item ->
        if (query.isNotBlank()) Session.rememberSearch(query)
        onOpen(item, filtered)
    }

    Column(Modifier.fillMaxSize()) {
        if (!isTv) {
            PhoneSearchField(
                query = query,
                hint = stringResource(R.string.search_x, noun),
                onQueryChange = { query = it; scrollTop() },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (isTv) 8.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTv) {
                // A text field would pop the TV keyboard every time focus passed over it,
                // so on TV search opens in a dialog instead.
                item {
                    FilterChip(
                        selected = query.isNotEmpty(),
                        onClick = { showSearch = true },
                        label = { Text(query.ifEmpty { stringResource(R.string.search) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                    )
                }
                if (query.isNotEmpty()) {
                    item {
                        IconButton(onClick = { query = ""; scrollTop() }, modifier = Modifier.focusRing(CircleShape)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                }
            }
            item {
                FilterChip(
                    selected = categoryId.isNotEmpty(),
                    onClick = { showCategories = true },
                    label = {
                        Text(categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    },
                    leadingIcon = { Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
            }
            item {
                FilterChip(
                    selected = sort != SortOrder.DEFAULT,
                    onClick = {
                        sort = sortOptions[(sortOptions.indexOf(sort) + 1) % sortOptions.size]
                        scrollTop()
                    },
                    label = { Text(stringResource(sort.label), maxLines = 1) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
            }
            item {
                IconButton(onClick = { refreshContent() }, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_content))
                }
            }
            item {
                Text(
                    "${filtered.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (filtered.isEmpty()) {
            MessageView(
                if (items.isEmpty()) stringResource(R.string.no_x_on_account, noun) else stringResource(R.string.nothing_matches),
                if (items.isEmpty()) null else stringResource(R.string.try_different),
            )
        } else if (type == ContentType.LIVE) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered) { item -> LiveRow(item, memory) { openItem(item) } }
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
                    PosterCard(item, memory = memory) { openItem(item) }
                }
            }
        }
    }

    if (showSearch) {
        SearchDialog(
            initial = query,
            hint = stringResource(R.string.search_x, noun),
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
            allLabel = stringResource(R.string.all_x, noun),
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

/** Search box in a dialog (for TV), with recent searches underneath. */
@Composable
internal fun SearchDialog(initial: String, hint: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val submit: (String) -> Unit = { q ->
        Session.rememberSearch(q)
        onSearch(q.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                val recent = Session.searches.toList()
                if (recent.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.recent_searches),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { Session.clearSearches() }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(recent) { q ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .focusRing(RoundedCornerShape(8.dp))
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { submit(q) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(text) }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text(stringResource(R.string.search))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text(stringResource(R.string.cancel))
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
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.categories)) },
        text = {
            Column {
                if (showFilter) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text(stringResource(R.string.find_category)) },
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

/**
 * A channel row. OK plays it; the remote's right button moves to the star; press-and-hold
 * (or tapping the star) adds or removes it from favourites.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveRow(item: StreamItem, memory: FocusMemory? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    val favorite = Session.isFavorite(item)
    val rowFocus = remember { FocusRequester() }
    val starFocus = remember { FocusRequester() }
    val key = "live-${item.id}"
    ListItem(
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Thumb(item.icon, item.name, Modifier.size(width = 64.dp, height = 44.dp)) },
        trailingContent = {
            IconButton(
                onClick = { Session.toggleFavorite(item) },
                modifier = Modifier
                    .focusRequester(starFocus)
                    .focusProperties { start = rowFocus }
                    .focusRing(CircleShape),
            ) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(if (favorite) R.string.remove_favourite else R.string.add_favourite),
                    tint = if (favorite) FavoriteYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier
            .remembered(memory, key)
            .focusRequester(rowFocus)
            .focusProperties { end = starFocus }
            .focusRing(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = {
                    memory?.opened(key)
                    onClick()
                },
                onLongClick = { toggleFavouriteWithToast(context, item) },
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PosterCard(
    item: StreamItem,
    modifier: Modifier = Modifier,
    memory: FocusMemory? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val favorite = Session.isFavorite(item)
    val key = "${item.type.name}-${item.id}"
    Column(
        modifier
            .remembered(memory, key)
            .focusRing(RoundedCornerShape(12.dp), focusedScale = 1.06f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    memory?.opened(key)
                    onClick()
                },
                onLongClick = { toggleFavouriteWithToast(context, item) },
            )
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
private fun FavouritesTab(onOpen: (StreamItem, List<StreamItem>) -> Unit) {
    val favourites = Session.favorites.toList()
    val memory = rememberFocusMemory("favourites")
    if (favourites.isEmpty()) {
        MessageView(stringResource(R.string.no_favourites), stringResource(R.string.no_favourites_hint))
        return
    }
    val channels = favourites.filter { it.type == ContentType.LIVE }
    val movies = favourites.filter { it.type == ContentType.MOVIE }
    val series = favourites.filter { it.type == ContentType.SERIES }
    val channelsTitle = stringResource(R.string.tab_live)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (movies.isNotEmpty()) {
            shelf(R.string.tab_movies) {
                items(movies, key = { "m-${it.id}" }) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, movies) }
                }
            }
        }
        if (series.isNotEmpty()) {
            shelf(R.string.tab_series) {
                items(series, key = { "s-${it.id}" }) { item ->
                    PosterCard(item, Modifier.width(118.dp), memory) { onOpen(item, series) }
                }
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "channels-title") { SectionTitle(channelsTitle) }
            items(channels, key = { "c-${it.id}" }) { item ->
                LiveRow(item, memory) { onOpen(item, channels) }
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsTab(
    onSwitchAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onAccountRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val account = Session.account
    if (account == null) {
        MessageView(stringResource(R.string.no_account_open), null, stringResource(R.string.choose_account), onSwitchAccount)
        return
    }
    val auth = Session.auth
    var format by remember { mutableStateOf(Store.liveFormat) }
    var autoPip by remember { mutableStateOf(Store.autoPip) }
    val language = remember { Language.current(context) }
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
                InfoLine(stringResource(R.string.server), account.server)
                InfoLine(stringResource(R.string.username), account.username)
                InfoLine(stringResource(R.string.status), auth?.status?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.checking))
                InfoLine(
                    stringResource(R.string.expires),
                    when {
                        auth == null -> stringResource(R.string.checking)
                        auth.expDate == null -> stringResource(R.string.never)
                        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(auth.expDate * 1000))
                    },
                )
                if (auth != null) {
                    InfoLine(
                        stringResource(R.string.connections),
                        stringResource(R.string.connections_in_use, auth.activeConnections ?: "?", auth.maxConnections ?: "?"),
                    )
                }
            }
        }

        OutlinedButton(onClick = { onEditAccount(account.id) }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.edit_playlist))
        }

        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Language.SYSTEM to R.string.lang_system,
                Language.ENGLISH to R.string.lang_en,
                Language.ARABIC to R.string.lang_ar,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = language == value,
                    onClick = {
                        if (language != value) {
                            Language.set(context, value)
                            context.findActivity()?.recreate()
                        }
                    },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                )
            }
        }

        SettingSwitch(
            title = stringResource(R.string.hide_adult),
            description = stringResource(R.string.hide_adult_desc),
            checked = Session.hideAdult,
            onChange = { Session.updateHideAdult(it) },
        )
        SettingSwitch(
            title = stringResource(R.string.auto_pip),
            description = stringResource(R.string.auto_pip_desc),
            checked = autoPip,
            onChange = {
                autoPip = it
                Store.autoPip = it
            },
        )

        Text(stringResource(R.string.live_format), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to stringResource(R.string.format_auto), "ts" to "TS", "m3u8" to "HLS").forEach { (value, label) ->
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
            stringResource(R.string.live_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.size(4.dp))
        OutlinedButton(onClick = { refreshContent() }, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.refresh_all))
        }
        OutlinedButton(onClick = onSwitchAccount, modifier = Modifier.fillMaxWidth().focusRing(ButtonShape)) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.switch_account))
        }
        TextButton(
            onClick = { confirmRemove = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().focusRing(ButtonShape),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.remove_account))
        }
        Text(
            stringResource(R.string.version_line, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_account_title, account.name)) },
            text = { Text(stringResource(R.string.remove_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    Store.removeAccount(account.id)
                    Session.close()
                    onAccountRemoved()
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
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
