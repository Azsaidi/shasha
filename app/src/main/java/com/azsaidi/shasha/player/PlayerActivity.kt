package com.azsaidi.shasha.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.graphics.drawable.StateListDrawable
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import com.azsaidi.shasha.data.Http
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.ui.isTelevision

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var resizeView: TextView

    private var items: List<PlayItem> = emptyList()
    private var startIndex = 0
    private val retried = HashSet<Int>()

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    )
    private var resizeIndex = 0
    private var isTv = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player?.currentMediaItemIndex ?: return
            items.getOrNull(index)?.let { titleView.text = it.title }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        isTv = isTelevision()
        buildViews()
        hideSystemBars()

        // On TV, Back first hides the on-screen controls, then leaves the player.
        onBackPressedDispatcher.addCallback(this) {
            if (isTv && playerView.isControllerFullyVisible) playerView.hideController() else finish()
        }

        addOnPictureInPictureModeChangedListener { info ->
            val inPip = info.isInPictureInPictureMode
            playerView.useController = !inPip
            if (inPip) {
                playerView.hideController()
                topBar.visibility = View.GONE
            }
        }
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
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            val controlsShown = playerView.isControllerFullyVisible
            val live = items.getOrNull(p.currentMediaItemIndex)?.isLive == true
            val zappable = live && items.size > 1
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (zappable) { changeChannel(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zappable) { changeChannel(-1); return true }
                // With the controls hidden: up/down changes channel, left/right skips 10 s.
                KeyEvent.KEYCODE_DPAD_UP -> if (!controlsShown && zappable) { changeChannel(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsShown && zappable) { changeChannel(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!controlsShown && !live) { p.seekBack(); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsShown && !live) { p.seekForward(); playerView.showController(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun changeChannel(step: Int) {
        val p = player ?: return
        val count = items.size
        if (count < 2) return
        val target = ((p.currentMediaItemIndex + step) % count + count) % count
        p.seekToDefaultPosition(target)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        items.getOrNull(target)?.let { titleView.text = it.title }
        topBar.visibility = View.VISIBLE
        playerView.showController()
    }

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
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            text = items.getOrNull(startIndex)?.title.orEmpty()
        }
        resizeView = pill(resizeModes[resizeIndex].second) {
            resizeIndex = (resizeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeIndex].first
            resizeView.text = resizeModes[resizeIndex].second
        }

        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(resizeView)
        if (pipSupported()) {
            val pip = pill("PiP") { enterPip() }
            topBar.addView(pip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowNextButton(items.size > 1)
            setShowPreviousButton(items.size > 1)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
            resizeMode = resizeModes[resizeIndex].first
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
                if (visibility != View.VISIBLE) hideSystemBars()
            })
        }

        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
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

    private fun mediaItem(item: PlayItem, url: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
        if (url.endsWith(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
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
        exo.addListener(listener)

        val start = items[startIndex]
        val startPosition = if (start.isLive) C.TIME_UNSET else start.resumeKey?.let { Store.position(it) } ?: 0L
        exo.setMediaItems(items.map { mediaItem(it, it.url) }, startIndex, startPosition)
        exo.prepare()
        exo.playWhenReady = true

        playerView.player = exo
        player = exo
        titleView.text = start.title
    }

    private fun releasePlayer() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        startIndex = index.coerceIn(0, items.lastIndex)
        items.getOrNull(index)?.let { item ->
            val key = item.resumeKey
            if (key != null && !item.isLive) {
                val duration = p.duration
                val position = p.currentPosition
                when {
                    duration != C.TIME_UNSET && duration > 0 && position > duration - 60_000 -> Store.savePosition(key, 0)
                    position > 10_000 -> Store.savePosition(key, position)
                }
            }
        }
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
    }

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

    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPip() {
        if (!pipSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
