package com.azsaidi.shasha.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.EpgEntry
import com.azsaidi.shasha.data.Http
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.ui.isTelevision
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var resizeView: TextView
    private lateinit var sleepView: TextView

    private var items: List<PlayItem> = emptyList()
    private var startIndex = 0
    private val retried = HashSet<Int>()
    private var isTv = false
    private var inPip = false

    // Last known position of the current item, refreshed every couple of seconds.
    private var trackedIndex = -1
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var ticks = 0

    private val handler = Handler(Looper.getMainLooper())
    private var epgJob: Job? = null

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    )
    private var resizeIndex = 0

    private val sleepOptions = listOf(0, 15, 30, 60, 90, 120)
    private var sleepIndex = 0
    private val sleepRunnable = Runnable {
        player?.pause()
        Toast.makeText(this, "Sleep timer: playback stopped", Toast.LENGTH_SHORT).show()
        finish()
    }

    private val ticker = object : Runnable {
        override fun run() {
            track()
            if (++ticks % 5 == 0 && player?.isPlaying == true) saveCurrent()
            handler.postDelayed(this, 2_000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val previous = trackedIndex
            val index = p.currentMediaItemIndex
            if (previous >= 0 && previous != index) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markFinished(previous)
                else saveItem(previous, lastPosition, lastDuration)
            }
            trackedIndex = index
            lastPosition = 0
            lastDuration = 0
            onItemStarted(index)
            updatePipParams()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.let { markFinished(it.currentMediaItemIndex) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = player ?: return
            when (intent?.getStringExtra(EXTRA_COMMAND)) {
                CMD_PLAY_PAUSE -> if (p.isPlaying) {
                    p.pause()
                } else {
                    if (p.playbackState == Player.STATE_IDLE) p.prepare()
                    p.play()
                }
                CMD_NEXT -> changeItem(+1)
                CMD_PREVIOUS -> changeItem(-1)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = PlayerQueue.items
        startIndex = PlayerQueue.startIndex
        if (items.isEmpty()) {
            finish()
            return
        }
        isTv = isTelevision()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildViews()
        hideSystemBars()

        // On TV, Back first hides the on-screen controls, then leaves the player.
        onBackPressedDispatcher.addCallback(this) {
            if (isTv && playerView.isControllerFullyVisible) playerView.hideController() else finish()
        }

        addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            playerView.useController = !inPip
            if (inPip) {
                playerView.hideController()
                topBar.visibility = View.GONE
            }
        }

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private var receiverRegistered = false

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) runCatching { unregisterReceiver(pipReceiver) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (PlayerQueue.items.isEmpty()) return
        releasePlayer()
        items = PlayerQueue.items
        startIndex = PlayerQueue.startIndex
        retried.clear()
        playerView.setShowNextButton(items.size > 1)
        playerView.setShowPreviousButton(items.size > 1)
        initializePlayer()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 2_000)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        releasePlayer()
        // The PiP window was closed: don't leave an invisible player behind.
        if (inPip) finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (auto-enter); older versions need a nudge.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Store.autoPip && player?.isPlaying == true) enterPip()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            val controlsShown = playerView.isControllerFullyVisible
            val live = items.getOrNull(p.currentMediaItemIndex)?.isLive == true
            val zappable = live && items.size > 1
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (zappable) { changeItem(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zappable) { changeItem(-1); return true }
                // With the controls hidden: up/down changes channel, left/right skips 10 s.
                KeyEvent.KEYCODE_DPAD_UP -> if (!controlsShown && zappable) { changeItem(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsShown && zappable) { changeItem(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsShown && !live) { p.seekBack(); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsShown && !live) { p.seekForward(); playerView.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ views

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
        }

        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = buttonBackground(0x00FFFFFF, dp(22).toFloat())
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = items.getOrNull(startIndex)?.title.orEmpty()
        }
        infoView = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        texts.addView(titleView)
        texts.addView(infoView)

        resizeView = pill(resizeModes[resizeIndex].second) {
            resizeIndex = (resizeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeIndex].first
            resizeView.text = resizeModes[resizeIndex].second
        }
        sleepView = pill("Sleep") { cycleSleepTimer() }

        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(sleepView, pillParams())
        topBar.addView(resizeView, pillParams())
        if (pipSupported()) topBar.addView(pill("PiP") { enterPip() }, pillParams())

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowNextButton(items.size > 1)
            setShowPreviousButton(items.size > 1)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
            resizeMode = resizeModes[resizeIndex].first
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (inPip) View.GONE else visibility
                if (visibility != View.VISIBLE) hideSystemBars()
            })
        }

        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
    }

    private fun pillParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = buttonBackground(0x33FFFFFF, dp(18).toFloat())
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Normal fill, plus a bright outline when selected with the remote. */
    private fun buttonBackground(fill: Int, radius: Float): StateListDrawable {
        fun shape(color: Int, stroke: Boolean) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke) setStroke(dp(3), 0xFF38BDF8.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0x55FFFFFF, true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x55FFFFFF, false))
            addState(intArrayOf(), shape(fill, false))
        }
    }

    private fun cycleSleepTimer() {
        sleepIndex = (sleepIndex + 1) % sleepOptions.size
        val minutes = sleepOptions[sleepIndex]
        handler.removeCallbacks(sleepRunnable)
        if (minutes == 0) {
            sleepView.text = "Sleep"
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
        } else {
            handler.postDelayed(sleepRunnable, minutes * 60_000L)
            sleepView.text = "Sleep ${minutes}m"
            Toast.makeText(this, "Stops in $minutes minutes", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ playback

    private fun mediaItem(item: PlayItem, url: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        if (url.endsWith(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun savedPosition(item: PlayItem): Long {
        if (item.isLive) return 0
        val entry = Session.historyEntry(item.kind, item.streamId) ?: return 0
        return if (!entry.finished && entry.position > 10_000) entry.position else 0
    }

    private fun initializePlayer() {
        if (player != null || items.isEmpty()) return

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )

        val start = items[startIndex]
        val startPosition = when {
            start.isLive -> C.TIME_UNSET
            PlayerQueue.fromStart -> 0L
            else -> savedPosition(start)
        }
        PlayerQueue.fromStart = false

        exo.setMediaItems(items.map { mediaItem(it, it.url) }, startIndex, startPosition)
        exo.addListener(listener)
        exo.prepare()
        exo.playWhenReady = true

        playerView.player = exo
        player = exo
        trackedIndex = startIndex
        lastPosition = 0
        lastDuration = 0
        onItemStarted(startIndex, seekToSaved = false)
        updatePipParams()
    }

    private fun releasePlayer() {
        val p = player ?: return
        track()
        saveCurrent()
        startIndex = p.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
        epgJob?.cancel()
    }

    private fun onItemStarted(index: Int, seekToSaved: Boolean = true) {
        val item = items.getOrNull(index) ?: return
        titleView.text = item.title
        if (item.isLive) {
            record(item, 0, 0)
            loadEpg(item)
        } else {
            epgJob?.cancel()
            infoView.text = item.subtitle.orEmpty()
            infoView.visibility = if (item.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            if (seekToSaved) {
                val saved = savedPosition(item)
                if (saved > 0) player?.seekTo(saved)
            }
        }
    }

    private fun changeItem(step: Int) {
        val p = player ?: return
        val count = items.size
        if (count < 2) return
        val target = ((p.currentMediaItemIndex + step) % count + count) % count
        p.seekToDefaultPosition(target)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        if (!inPip) {
            topBar.visibility = View.VISIBLE
            playerView.showController()
        }
    }

    // ------------------------------------------------------------------ history

    private fun track() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        if (index != trackedIndex) return
        val duration = p.duration
        lastPosition = p.currentPosition
        lastDuration = if (duration == C.TIME_UNSET || duration < 0) 0 else duration
    }

    private fun saveCurrent() {
        if (trackedIndex >= 0) saveItem(trackedIndex, lastPosition, lastDuration)
    }

    private fun saveItem(index: Int, position: Long, duration: Long) {
        val item = items.getOrNull(index) ?: return
        if (item.isLive || position <= 0) return
        val nearEnd = duration > 0 && (position >= duration - 60_000 || position >= duration * 0.95)
        if (nearEnd) markFinished(index, duration) else record(item, position, duration)
    }

    private fun markFinished(index: Int, duration: Long = 0) {
        val item = items.getOrNull(index) ?: return
        if (item.isLive) return
        val knownDuration = duration.takeIf { it > 0 } ?: Session.historyEntry(item.kind, item.streamId)?.duration ?: 0
        record(item, 0, knownDuration, finished = true)
        // Put the following episode in "Continue watching" as up next.
        val next = items.getOrNull(index + 1)
        if (item.kind == WatchKind.EPISODE && next != null && next.seriesId == item.seriesId) {
            val existing = Session.historyEntry(next.kind, next.streamId)
            if (existing == null || existing.finished || existing.position <= 10_000) {
                record(next, 0, 0, upNext = true, at = System.currentTimeMillis() + 1)
            }
        }
    }

    private fun record(
        item: PlayItem,
        position: Long,
        duration: Long,
        finished: Boolean = false,
        upNext: Boolean = false,
        at: Long = System.currentTimeMillis(),
    ) {
        val episode = item.kind == WatchKind.EPISODE
        Session.record(
            item.accountId,
            WatchEntry(
                kind = item.kind,
                streamId = item.streamId,
                title = if (episode) item.seriesName ?: item.title else item.title,
                subtitle = if (episode) {
                    item.title.substringAfter(" · ", item.title) + (item.subtitle?.let { " · $it" } ?: "")
                } else item.subtitle,
                image = item.image,
                extension = item.extension,
                seriesId = item.seriesId,
                seriesName = item.seriesName,
                position = position,
                duration = duration,
                finished = finished,
                upNext = upNext,
                updatedAt = at,
            ),
        )
    }

    // ------------------------------------------------------------------ TV guide

    private fun loadEpg(item: PlayItem) {
        epgJob?.cancel()
        infoView.visibility = View.GONE
        val api = Session.api ?: return
        if (Session.account?.id != item.accountId) return
        epgJob = lifecycleScope.launch {
            val entries = runCatching { api.shortEpg(item.streamId) }.getOrDefault(emptyList())
            showEpg(entries)
            // Refresh when the current programme ends.
            val now = System.currentTimeMillis()
            val current = entries.firstOrNull { now >= it.start && now < it.end }
            if (current != null) {
                delay((current.end - now).coerceAtLeast(30_000))
                val playing = player?.let { items.getOrNull(it.currentMediaItemIndex) }
                if (playing == item) loadEpg(item)
            }
        }
    }

    private fun showEpg(entries: List<EpgEntry>) {
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val current = entries.firstOrNull { now >= it.start && now < it.end }
        val next = entries.firstOrNull { it.start >= (current?.end ?: now) }
        val lines = ArrayList<String>()
        if (current != null) lines.add("Now: ${current.title}  (${time.format(Date(current.start))}–${time.format(Date(current.end))})")
        if (next != null) lines.add("Next: ${next.title}  ${time.format(Date(next.start))}")
        infoView.text = lines.joinToString("\n")
        infoView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ errors

    private fun handleError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }

        // Live channel failed: try the other format once (TS <-> HLS) before giving up.
        val index = p.currentMediaItemIndex
        val item = items.getOrNull(index)
        if (item != null && item.isLive && retried.add(index)) {
            val url = item.url
            val alternative = when {
                url.endsWith(".m3u8") -> url.dropLast(5) + ".ts"
                url.endsWith(".ts") -> url.dropLast(3) + ".m3u8"
                else -> null
            }
            if (alternative != null) {
                p.replaceMediaItem(index, mediaItem(item, alternative))
                p.prepare()
                p.play()
                return
            }
        }

        val message = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "The server refused this stream. Your account may be expired or using all its connections."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Couldn't reach the stream. Check your internet connection."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This stream's format isn't supported on this device."
            else -> "Can't play this stream (${error.errorCodeName})."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ picture-in-picture

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipSupported()) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipSupported()) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val p = player
        val builder = PictureInPictureParams.Builder()

        val size = p?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            val raw = size.width * size.pixelWidthHeightRatio / size.height
            when {
                raw > 2.39f -> Rational(239, 100)
                raw < 1 / 2.39f -> Rational(100, 239)
                else -> Rational((raw * 1000).toInt(), 1000)
            }
        } else {
            Rational(16, 9)
        }
        builder.setAspectRatio(ratio)

        val actions = ArrayList<RemoteAction>()
        if (items.size > 1) actions.add(pipAction(CMD_PREVIOUS, R.drawable.ic_pip_prev, "Previous", 1))
        val playing = p?.isPlaying == true
        actions.add(
            pipAction(
                CMD_PLAY_PAUSE,
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                if (playing) "Pause" else "Play",
                2,
            )
        )
        if (items.size > 1) actions.add(pipAction(CMD_NEXT, R.drawable.ic_pip_next, "Next", 3))
        builder.setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(Store.autoPip && playing)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(command: String, icon: Int, label: String, requestCode: Int): RemoteAction {
        val intent = Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_COMMAND, command)
        val pending = PendingIntent.getBroadcast(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private companion object {
        const val ACTION_PIP = "com.azsaidi.shasha.PIP_CONTROL"
        const val EXTRA_COMMAND = "command"
        const val CMD_PLAY_PAUSE = "play_pause"
        const val CMD_NEXT = "next"
        const val CMD_PREVIOUS = "previous"
    }
}
