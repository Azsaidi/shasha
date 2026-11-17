// Plain (non-mirrored) speaker icons on purpose, so they don't flip in Arabic.
@file:Suppress("DEPRECATION")

package com.azsaidi.shasha.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Downloads
import com.azsaidi.shasha.data.Http
import com.azsaidi.shasha.data.Language
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.ui.ShashaTheme
import com.azsaidi.shasha.ui.Thumb
import com.azsaidi.shasha.ui.focusRing
import kotlinx.coroutines.delay

/**
 * Split screen: up to four streams at once, each with its own play/pause, mute and volume.
 * Controls appear at the bottom on any touch or remote press and fade away after a few seconds,
 * together with the stream names.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : ComponentActivity() {

    private val players = mutableStateMapOf<String, ExoPlayer>()
    private val muted = mutableStateMapOf<String, Boolean>()
    private val volume = mutableStateMapOf<String, Float>()
    private val playing = mutableStateMapOf<String, Boolean>()
    private val errors = mutableStateMapOf<String, String>()
    private val urls = HashMap<String, String>()
    private val retried = HashSet<String>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = intent.getBooleanExtra(EXTRA_PICK, false)
        if (MultiView.items.isEmpty() && !pick) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // The first stream has sound; the rest start muted.
        MultiView.items.forEachIndexed { i, item ->
            if (item.key !in muted) muted[item.key] = i > 0
            if (item.key !in volume) volume[item.key] = 1f
        }

        setContent {
            ShashaTheme {
                MultiViewScreen(startWithPicker = pick)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncPlayers()
        Downloads.setStreaming(true)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        players.values.forEach { it.release() }
        players.clear()
        Downloads.setStreaming(false)
    }

    // ------------------------------------------------------------------ players

    /** Creates players for new streams and releases removed ones. */
    private fun syncPlayers() {
        val keys = MultiView.items.map { it.key }.toSet()
        players.keys.filter { it !in keys }.forEach { key -> players.remove(key)?.release() }
        MultiView.items.forEach { item -> if (item.key !in players) players[item.key] = createPlayer(item) }
    }

    private fun createPlayer(item: PlayItem): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        val url = urls[item.key] ?: item.url
        urls[item.key] = url
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing[item.key] = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errors.remove(item.key)
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = urls[item.key] ?: item.url
                val alternative = when {
                    !item.isLive || item.key in retried -> null
                    current.endsWith(".m3u8") -> current.dropLast(5) + ".ts"
                    current.endsWith(".ts") -> current.dropLast(3) + ".m3u8"
                    else -> null
                }
                if (alternative != null) {
                    retried.add(item.key)
                    urls[item.key] = alternative
                    exo.setMediaItem(mediaItem(alternative))
                    exo.prepare()
                    exo.play()
                    return
                }
                errors[item.key] = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.err_stream_refused)
                    else -> getString(R.string.err_stream_generic, error.errorCodeName)
                }
            }
        })
        exo.setMediaItem(mediaItem(url))
        applyVolume(item.key, exo)
        exo.prepare()
        exo.playWhenReady = true
        return exo
    }

    private fun mediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (url.endsWith(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun applyVolume(key: String, player: ExoPlayer? = players[key]) {
        player?.volume = if (muted[key] == true) 0f else (volume[key] ?: 1f)
    }

    private fun togglePlay(key: String) {
        val p = players[key] ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    private fun toggleMute(key: String) {
        muted[key] = muted[key] != true
        applyVolume(key)
    }

    private fun changeVolume(key: String, delta: Float) {
        val next = ((volume[key] ?: 1f) + delta).coerceIn(0f, 1f)
        volume[key] = next
        muted[key] = next == 0f
        applyVolume(key)
    }

    private fun add(item: PlayItem?) {
        if (item == null) return
        val wasEmpty = MultiView.items.isEmpty()
        if (!MultiView.add(this, item)) return
        if (item.key !in muted) muted[item.key] = !wasEmpty
        if (item.key !in volume) volume[item.key] = 1f
        syncPlayers()
    }

    private fun remove(key: String) {
        MultiView.items.removeAll { it.key == key }
        players.remove(key)?.release()
        if (MultiView.items.isEmpty()) finish()
    }

    /** Leaves split screen and keeps watching just this one in the normal player. */
    private fun watchAlone(item: PlayItem) {
        players.values.forEach { it.release() }
        players.clear()
        PlayerQueue.items = listOf(item)
        PlayerQueue.startIndex = 0
        PlayerQueue.fromStart = false
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun MultiViewScreen(startWithPicker: Boolean) {
        val items = MultiView.items.toList()
        var selected by remember { mutableIntStateOf(0) }
        var solo by remember { mutableStateOf<String?>(null) }
        var controlsVisible by remember { mutableStateOf(true) }
        var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var picking by remember { mutableStateOf(startWithPicker) }
        val tileFocus = remember { List(MultiView.MAX) { FocusRequester() } }
        val barFocus = remember { FocusRequester() }

        fun poke() {
            controlsVisible = true
            lastTouch = System.currentTimeMillis()
        }

        // Names and controls fade away after a few seconds without interaction.
        LaunchedEffect(lastTouch, picking) {
            if (picking) return@LaunchedEffect
            delay(5_000)
            controlsVisible = false
        }

        if (selected > items.lastIndex) selected = items.lastIndex.coerceAtLeast(0)
        val current = items.getOrNull(selected)
        if (solo != null && items.none { it.key == solo }) solo = null

        BackHandler {
            if (solo != null) solo = null else finish()
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent {
                    poke()
                    false
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            poke()
                        }
                    }
                },
        ) {
            val visible = solo?.let { key -> items.filter { it.key == key } } ?: items
            val perRow = if (visible.size <= 2) visible.size.coerceAtLeast(1) else 2
            Column(Modifier.fillMaxSize()) {
                visible.chunked(perRow).forEach { row ->
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        row.forEach { item ->
                            val index = items.indexOf(item)
                            Tile(
                                item = item,
                                isSelected = index == selected && controlsVisible,
                                showLabel = controlsVisible,
                                focus = tileFocus[index.coerceIn(0, MultiView.MAX - 1)],
                                onFocused = { selected = index },
                                onClick = {
                                    selected = index
                                    poke()
                                    runCatching { barFocus.requestFocus() }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        if (row.size < perRow) Spacer(Modifier.weight(1f))
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible && current != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (current != null) {
                    ControlBar(
                        item = current,
                        isSolo = solo == current.key,
                        firstFocus = barFocus,
                        onSolo = { solo = if (solo == current.key) null else current.key },
                        onRemove = { remove(current.key) },
                        onAlone = { watchAlone(current) },
                        onAdd = { picking = true },
                        onExit = {
                            MultiView.clear()
                            finish()
                        },
                    )
                }
            }

            if (picking) {
                StreamPicker(
                    onPick = {
                        add(it)
                        picking = false
                        poke()
                    },
                    onDismiss = {
                        picking = false
                        if (MultiView.items.isEmpty()) finish()
                    },
                )
            }
        }

        LaunchedEffect(Unit) { runCatching { tileFocus[0].requestFocus() } }
    }

    @Composable
    private fun Tile(
        item: PlayItem,
        isSelected: Boolean,
        showLabel: Boolean,
        focus: FocusRequester,
        onFocused: () -> Unit,
        onClick: () -> Unit,
        modifier: Modifier,
    ) {
        val player = players[item.key]
        val isMuted = muted[item.key] == true
        Box(
            modifier
                .padding(2.dp)
                // The blue outline only shows while the controls are up; otherwise just a thin divider.
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF15181D),
                )
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .clickable(onClick = onClick),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        isFocusable = false
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            errors[item.key]?.let { message ->
                Text(
                    message,
                    color = Color(0xFFFCA5A5),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            ) {
                Row(
                    Modifier
                        .background(Color(0x99000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun ControlBar(
        item: PlayItem,
        isSolo: Boolean,
        firstFocus: FocusRequester,
        onSolo: () -> Unit,
        onRemove: () -> Unit,
        onAlone: () -> Unit,
        onAdd: () -> Unit,
        onExit: () -> Unit,
    ) {
        val isPlaying = playing[item.key] == true
        val isMuted = muted[item.key] == true
        val level = ((volume[item.key] ?: 1f) * 100).toInt()
        Surface(
            color = Color(0xE6101418),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    item.title + if (isMuted) "" else "  ·  " + stringResource(R.string.volume_n, level),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarButton(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (isPlaying) R.string.pause else R.string.play),
                        Modifier.focusRequester(firstFocus),
                    ) { togglePlay(item.key) }
                    BarButton(
                        if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        stringResource(if (isMuted) R.string.unmute else R.string.mute),
                    ) { toggleMute(item.key) }
                    BarButton(Icons.Filled.VolumeDown, stringResource(R.string.volume_down)) { changeVolume(item.key, -0.1f) }
                    BarButton(Icons.Filled.VolumeUp, stringResource(R.string.volume_up)) { changeVolume(item.key, +0.1f) }
                    BarButton(
                        if (isSolo) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        stringResource(if (isSolo) R.string.split_show_all else R.string.split_full_screen),
                        onClick = onSolo,
                    )
                    BarButton(Icons.Filled.OpenInFull, stringResource(R.string.split_watch_single), onClick = onAlone)
                    BarButton(Icons.Filled.RemoveCircleOutline, stringResource(R.string.remove), onClick = onRemove)
                    Box(
                        Modifier
                            .padding(horizontal = 8.dp)
                            .width(1.dp)
                            .heightIn(min = 36.dp)
                            .background(Color(0x33FFFFFF))
                    )
                    if (MultiView.items.size < MultiView.MAX) {
                        BarButton(Icons.Filled.Add, stringResource(R.string.split_add), onClick = onAdd)
                    }
                    BarButton(Icons.Filled.Close, stringResource(R.string.split_exit), onClick = onExit)
                }
            }
        }
    }

    @Composable
    private fun BarButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
            IconButton(onClick = onClick, modifier = modifier.focusRing(CircleShape)) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
            Text(label, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /** Pick another stream: favourite and recent channels, Continue watching, or the list you were watching. */
    @Composable
    private fun StreamPicker(onPick: (PlayItem?) -> Unit, onDismiss: () -> Unit) {
        val taken = MultiView.items.map { it.key }.toSet()
        val favourites = Session.favorites.filter { it.type == ContentType.LIVE && WatchEntry.keyOf(WatchKind.LIVE, it.id) !in taken }
        val recent = Session.recentChannels().filter { it.key !in taken }
        val resume = Session.continueWatching().filter { it.key !in taken }
        val sameList = PlayerQueue.items.filter { it.key !in taken }.take(300)
        val firstFocus = remember { FocusRequester() }
        val favouritesTitle = stringResource(R.string.favourite_channels)
        val recentTitle = stringResource(R.string.recent_channels)
        val resumeTitle = stringResource(R.string.continue_watching)
        val sameTitle = stringResource(R.string.split_pick_current)

        // Only the very first row grabs focus, so the remote starts at the top.
        val firstKey = when {
            favourites.isNotEmpty() -> "f-${favourites.first().id}"
            recent.isNotEmpty() -> "r-${recent.first().key}"
            resume.isNotEmpty() -> "c-${resume.first().key}"
            sameList.isNotEmpty() -> "s-${sameList.first().key}"
            else -> null
        }
        fun focusFor(key: String) = if (key == firstKey) firstFocus else null

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.split_pick_title)) },
            text = {
                if (firstKey == null) {
                    Text(stringResource(R.string.split_pick_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        if (favourites.isNotEmpty()) {
                            item(key = "h-fav") { PickHeader(favouritesTitle) }
                            items(favourites, key = { "f-${it.id}" }) {
                                PickRow(it.name, it.icon, focusFor("f-${it.id}")) { onPick(Playback.liveItem(it)) }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item(key = "h-recent") { PickHeader(recentTitle) }
                            items(recent, key = { "r-${it.key}" }) {
                                PickRow(it.title, it.image, focusFor("r-${it.key}")) { onPick(Playback.entryItem(it)) }
                            }
                        }
                        if (resume.isNotEmpty()) {
                            item(key = "h-resume") { PickHeader(resumeTitle) }
                            items(resume, key = { "c-${it.key}" }) {
                                PickRow(listOfNotNull(it.title, it.subtitle).joinToString(" · "), it.image, focusFor("c-${it.key}")) {
                                    onPick(Playback.entryItem(it))
                                }
                            }
                        }
                        if (sameList.isNotEmpty()) {
                            item(key = "h-same") { PickHeader(sameTitle) }
                            items(sameList, key = { "s-${it.key}" }) {
                                PickRow(it.title, it.image, focusFor("s-${it.key}")) { onPick(it) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                    Text(stringResource(R.string.close))
                }
            },
        )
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }

    @Composable
    private fun PickHeader(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun PickRow(title: String, image: String?, focus: FocusRequester?, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRing(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Thumb(image, title, Modifier.size(width = 64.dp, height = 40.dp))
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }

    companion object {
        const val EXTRA_PICK = "pick"

        /** Opens split screen; with [pick] the "add a stream" list opens straight away. */
        fun open(context: Context, pick: Boolean) {
            context.startActivity(Intent(context, MultiViewActivity::class.java).putExtra(EXTRA_PICK, pick))
        }
    }
}
