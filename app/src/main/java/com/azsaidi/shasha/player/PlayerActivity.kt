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
import android.os.SystemClock
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.media3.ui.DefaultTimeBar
import com.azsaidi.shasha.data.Language
import kotlin.math.ceil
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
import androidx.media3.common.ForwardingPlayer
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
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.Downloads
import com.azsaidi.shasha.data.EpgEntry
import com.azsaidi.shasha.data.Http
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.ui.formatDuration
import com.azsaidi.shasha.ui.isTelevision
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    /** Tells the system what's playing (headphone/Bluetooth buttons, car displays, Google TV). */
    private var mediaSession: MediaSession? = null
    /** The playback card in the notification shade and on the lock screen. */
    private var playbackNotification: PlayerNotificationManager? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var resizeView: TextView
    private lateinit var sleepView: TextView
    private lateinit var clarityView: TextView
    /** Rebuilds the top end of dull, muffled soundtracks ("Clearer sound"). */
    private var clarity: ClarityProcessor? = null
    private lateinit var curtain: View
    private lateinit var skipBack: TextView
    private lateinit var skipForward: TextView
    private var skipSide = 0
    private var skipTotal = 0
    private var lastSkipAt = 0L
    // Recent touches on the picture, to tell a double-tap that strays onto the previous/next
    // button from a real press of it.
    private var lastTouchAt = 0L
    private var lastTouchX = 0.5f
    private var ignoreTapsUntil = 0L
    private var pendingZap: Runnable? = null
    private var pendingZapAt = 0L
    private var pendingZapSide = 0
    private lateinit var titleCard: LinearLayout
    private lateinit var titleCardText: TextView
    private lateinit var upNextCard: LinearLayout
    private lateinit var upNextTitle: TextView
    private lateinit var upNextCountdown: TextView
    private lateinit var upNextProgress: ProgressBar
    private lateinit var upNextPlay: TextView
    private var timeBar: DefaultTimeBar? = null
    private var upNextShownFor = -1
    private var upNextCancelledFor = -1

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

    private val resizeModes by lazy {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to getString(R.string.fit),
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to getString(R.string.fill),
            AspectRatioFrameLayout.RESIZE_MODE_FILL to getString(R.string.stretch),
        )
    }
    private var resizeIndex = 0

    private val sleepOptions = listOf(0, 15, 30, 60, 90, 120)
    private var sleepIndex = 0
    private val sleepRunnable = Runnable {
        player?.pause()
        Toast.makeText(this, getString(R.string.sleep_done), Toast.LENGTH_SHORT).show()
        finish()
    }

    private val ticker = object : Runnable {
        override fun run() {
            track()
            if (++ticks % 5 == 0 && player?.isPlaying == true) saveCurrent()
            handler.postDelayed(this, 2_000)
        }
    }

    /** Checks four times a second whether to show the "next episode" countdown. */
    private val upNextTicker = object : Runnable {
        override fun run() {
            updateUpNext()
            handler.postDelayed(this, 250)
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
            applyCurrentClarity()
            hideUpNext()
            upNextCancelledFor = -1
            p.pauseAtEndOfMediaItems = false
            if (previous >= 0 && previous != index && items.getOrNull(index)?.kind == WatchKind.EPISODE) {
                announceEpisode(index)
            }
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
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
                hideUpNext()
                titleCard.visibility = View.GONE
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
        handler.removeCallbacks(upNextTicker)
        handler.postDelayed(upNextTicker, 250)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(upNextTicker)
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
            val bar = timeBar
            val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (horizontal && !live && bar != null) {
                // Seeking with the remote: small steps for single presses, bigger the longer it's held.
                // The time bar previews the position and only seeks once you stop, so it stays smooth.
                bar.setKeyTimeIncrement(seekStep(event.repeatCount, p.duration))
                if (bar.hasFocus()) return super.dispatchKeyEvent(event)
                if (!controlsShown && upNextCard.visibility != View.VISIBLE) {
                    playerView.showController()
                    bar.requestFocus()
                    return bar.onKeyDown(event.keyCode, event)
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> if (zappable) { changeItem(+1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zappable) { changeItem(-1); return true }
                // With the controls hidden: up/down changes channel, left/right skips 10 s.
                KeyEvent.KEYCODE_DPAD_UP -> if (!controlsShown && zappable) { changeItem(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsShown && zappable) { changeItem(-1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 10 s per tap; holding the button speeds up to 30 s, 1 min and then 3 min steps. */
    private fun seekStep(repeatCount: Int, duration: Long): Long {
        val step = when {
            repeatCount < 4 -> 10_000L
            repeatCount < 12 -> 30_000L
            repeatCount < 24 -> 60_000L
            else -> 180_000L
        }
        // Keep short videos controllable.
        return if (duration > 0) step.coerceAtMost(maxOf(5_000L, duration / 30)) else step
    }

    // ------------------------------------------------------------------ views

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Shown and hidden with the player's controls (see the controller visibility listener).
            visibility = View.GONE
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
            contentDescription = getString(R.string.back)
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
        sleepView = pill(getString(R.string.sleep)) { cycleSleepTimer() }
        clarityView = pill(getString(R.string.clarity_0)) { showSoundPanel() }

        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(clarityView, pillParams())
        topBar.addView(sleepView, pillParams())
        topBar.addView(resizeView, pillParams())
        // Adds what's playing to split screen, to watch it alongside other streams.
        // Split: keep this stream and go straight to picking a second one.
        topBar.addView(pill(getString(R.string.split_short)) {
            val current = player?.let { items.getOrNull(it.currentMediaItemIndex) }
            if (current != null && MultiView.items.none { it.key == current.key }) MultiView.add(this, current)
            MultiViewActivity.open(this, pick = MultiView.items.size < MultiView.MAX)
            finish()
        }, pillParams())
        if (pipSupported()) topBar.addView(pill(getString(R.string.pip)) { enterPip() }, pillParams())

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setShowNextButton(items.size > 1)
            setShowPreviousButton(items.size > 1)
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 3500
            // No staged hide: with it the buttons fade, but the controller only reports itself
            // hidden ~2 s later (after an extra progress-line stage, invisible on live TV), so the
            // top bar lingered on its own.
            setControllerAnimationEnabled(false)
            resizeMode = resizeModes[resizeIndex].first
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (inPip) View.GONE else visibility
                if (visibility != View.VISIBLE) hideSystemBars()
            })
        }

        timeBar = playerView.findViewById(androidx.media3.ui.R.id.exo_progress)
        if (isTv) highlightFocus(playerView)

        // Black curtain that fades away when a new episode starts, so the switch isn't abrupt.
        curtain = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
        }

        // "Next episode · S1 E5 · Title" card shown in the middle as the new episode fades in.
        titleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC101418.toInt())
            }
            visibility = View.GONE
        }
        titleCard.addView(TextView(this).apply {
            text = getString(R.string.next_episode)
            setTextColor(0xFF5EEAD4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        })
        titleCardText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCard.addView(titleCardText)

        // Countdown card near the end of an episode.
        upNextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE6101418.toInt())
            }
            visibility = View.GONE
        }
        upNextCountdown = TextView(this).apply {
            setTextColor(0xFF5EEAD4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        upNextTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(10))
        }
        upNextProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF38BDF8.toInt())
        }
        val upNextButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        upNextPlay = pill(getString(R.string.play_now)) { player?.seekToNextMediaItem() }
        val upNextCancel = pill(getString(R.string.cancel)) {
            upNextCancelledFor = player?.currentMediaItemIndex ?: -1
            player?.pauseAtEndOfMediaItems = true
            hideUpNext()
        }
        upNextButtons.addView(upNextPlay)
        upNextButtons.addView(upNextCancel, pillParams())
        upNextCard.addView(upNextCountdown)
        upNextCard.addView(upNextTitle)
        upNextCard.addView(upNextProgress, LinearLayout.LayoutParams(dp(260), dp(4)))
        upNextCard.addView(upNextButtons)

        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        skipBack = skipBubble()
        skipForward = skipBubble()
        root.addView(skipBack, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER_VERTICAL or Gravity.LEFT).apply { leftMargin = dp(56) })
        root.addView(skipForward, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = dp(56) })
        setUpDoubleTap()
        root.addView(curtain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(titleCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(
            upNextCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(24), dp(24), dp(24), dp(96))
            },
        )
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
    }

    /** Round bubble showing "◀◀ 10 sec" / "▶▶ 10 sec" after a double tap. */
    private fun skipBubble() = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textDirection = View.TEXT_DIRECTION_LTR
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x40FFFFFF)
        }
        alpha = 0f
        visibility = View.GONE
    }

    /**
     * Phones: double-tap the left third to go back 10 s, the right third to go forward. Repeated taps
     * add up (once skipping, single taps on that side keep going).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setUpDoubleTap() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (SystemClock.elapsedRealtime() < ignoreTapsUntil) return true
                val side = sideOf(e.x)
                if (side != 0 && side == skipSide && isVod()) {
                    skipBy(side)
                    return true
                }
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (SystemClock.elapsedRealtime() < ignoreTapsUntil) return true
                when (val side = sideOf(e.x)) {
                    0 -> if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
                    else -> skipBy(side)
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchedDown(event.x)
            detector.onTouchEvent(event)
        }
    }

    /** -1 left third, 1 right third, 0 middle. */
    private fun sideOf(x: Float): Int {
        val third = playerView.width / 3f
        return when {
            x < third -> -1
            x > playerView.width - third -> 1
            else -> 0
        }
    }

    private fun isVod(): Boolean {
        val p = player ?: return false
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return false
        return !item.isLive && p.duration != C.TIME_UNSET
    }

    private fun touchedDown(x: Float) {
        val now = SystemClock.elapsedRealtime()
        lastTouchAt = now
        lastTouchX = x
        // Previous/next pressed a moment ago and now the picture: that was a double-tap to skip.
        val zap = pendingZap
        if (zap != null && now - pendingZapAt < 400) {
            handler.removeCallbacks(zap)
            pendingZap = null
            ignoreTapsUntil = now + 600
            skipBy(pendingZapSide)
        }
    }

    /**
     * The controller's previous/next buttons. On films and episodes they wait a moment, and a press
     * that is really half of a double-tap skips 10 s instead of changing (or restarting) the episode.
     */
    private fun zapFromButton(side: Int, exo: ExoPlayer, action: () -> Unit) {
        if (!isVod()) {
            action()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTouchAt < 400) {
            val touchSide = sideOf(lastTouchX)
            ignoreTapsUntil = now + 600
            skipBy(if (touchSide != 0) touchSide else side)
            return
        }
        pendingZap?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            pendingZap = null
            if (player === exo) action()
        }
        pendingZap = run
        pendingZapAt = now
        pendingZapSide = side
        handler.postDelayed(run, 350)
    }

    /** What the on-screen controls drive: the player, with previous/next going through [zapFromButton]. */
    private fun controlsPlayer(exo: ExoPlayer): Player = object : ForwardingPlayer(exo) {
        override fun seekToPrevious() = zapFromButton(-1, exo) { exo.seekToPrevious() }
        override fun seekToNext() = zapFromButton(1, exo) { exo.seekToNext() }
        override fun seekToPreviousMediaItem() = zapFromButton(-1, exo) { exo.seekToPreviousMediaItem() }
        override fun seekToNextMediaItem() = zapFromButton(1, exo) { exo.seekToNextMediaItem() }
    }


    private fun skipBy(side: Int) {
        val p = player ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        if (item.isLive || p.duration == C.TIME_UNSET) return
        p.seekTo((p.currentPosition + side * 10_000L).coerceIn(0L, p.duration))
        val now = SystemClock.elapsedRealtime()
        skipTotal = if (skipSide == side && now - lastSkipAt < 1_000) skipTotal + 10 else 10
        skipSide = side
        lastSkipAt = now
        val bubble = if (side > 0) skipForward else skipBack
        val other = if (side > 0) skipBack else skipForward
        other.animate().cancel()
        other.visibility = View.GONE
        bubble.text = (if (side > 0) "▶▶" else "◀◀") + "\n" + formatDuration(this, skipTotal.toDouble())
        bubble.animate().cancel()
        if (bubble.visibility != View.VISIBLE) {
            bubble.visibility = View.VISIBLE
            bubble.alpha = 0f
        }
        bubble.scaleX = 0.85f
        bubble.scaleY = 0.85f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
            .withEndAction {
                bubble.animate().alpha(0f).setStartDelay(650).setDuration(300)
                    .withEndAction {
                        bubble.visibility = View.GONE
                        skipSide = 0
                    }
                    .start()
            }
            .start()
    }

    /**
     * TV: the control the remote is on gets a solid blue disc with a white ring and grows a little.
     * The player's own highlight is a faint grey circle that's hard to see over a bright picture.
     */
    private fun highlightFocus(view: View) {
        if (view is DefaultTimeBar) return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) highlightFocus(view.getChildAt(i))
            return
        }
        if (!view.isFocusable || !(view is android.widget.ImageView || view is android.widget.Button)) return
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE638BDF8.toInt())
                setStroke(dp(3), Color.WHITE)
            })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66FFFFFF)
            })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        view.setOnFocusChangeListener { v, focused ->
            val scale = if (focused) 1.18f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
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
            if (stroke) setStroke(dp(3), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xE638BDF8.toInt(), true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x55FFFFFF, false))
            addState(intArrayOf(), shape(fill, false))
        }
    }

    /** Titles share a setting: every episode of a series, or one film or channel. */
    private fun clarityKey(item: PlayItem): String = when (item.kind) {
        WatchKind.EPISODE -> "series:${item.seriesId ?: item.streamId}"
        WatchKind.MOVIE -> "movie:${item.streamId}"
        WatchKind.LIVE -> "live:${item.streamId}"
    }

    private fun clarityLabel(s: ClaritySettings?) = getString(
        when (s) {
            null -> R.string.clarity_0
            ClaritySettings.CLEARER -> R.string.clarity_1
            ClaritySettings.MUCH_CLEARER -> R.string.clarity_2
            else -> R.string.clarity_custom
        },
    )

    private fun currentItem(): PlayItem? = player?.let { items.getOrNull(it.currentMediaItemIndex) }

    private fun setClarity(s: ClaritySettings?) {
        val item = currentItem() ?: return
        Store.setClarity(clarityKey(item), s)
        applyClarity(s)
    }

    private fun applyCurrentClarity() {
        val item = currentItem() ?: return
        applyClarity(Store.clarity(clarityKey(item)))
    }

    /** Changes the sound straight away (the processor retunes without a click). */
    private fun applyClarity(s: ClaritySettings?) {
        if (::clarityView.isInitialized) clarityView.text = clarityLabel(s)
        clarity?.settings = s
    }

    /** The Sound panel: presets and sliders, applied as they move, saved for this title. */
    private fun showSoundPanel() {
        val item = currentItem() ?: return
        var current = Store.clarity(clarityKey(item))
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(8))
        }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presetViews = mutableListOf<Pair<TextView, ClaritySettings?>>()
        val sliders = mutableListOf<() -> Unit>()

        fun refresh() {
            for ((view, preset) in presetViews) {
                val on = preset == current
                view.background = buttonBackground(if (on) 0xFF38BDF8.toInt() else 0x33FFFFFF, dp(18).toFloat())
                view.setTextColor(if (on) 0xFF0B1320.toInt() else Color.WHITE)
            }
            sliders.forEach { it() }
        }

        for ((label, preset) in listOf(
            getString(R.string.sound_normal) to null,
            getString(R.string.sound_clearer) to ClaritySettings.CLEARER,
            getString(R.string.sound_much) to ClaritySettings.MUCH_CLEARER,
        )) {
            val v = pill(label) {
                current = preset
                setClarity(preset)
                refresh()
            }
            presetViews += v to preset
            presets.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        column.addView(presets)
        column.addView(TextView(this).apply {
            text = getString(R.string.sound_hint)
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(4))
        })

        fun slider(title: Int, max: Float, unit: String?, get: (ClaritySettings) -> Float, set: (ClaritySettings, Float) -> ClaritySettings) {
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(14), 0, 0)
            }
            val bar = android.widget.SeekBar(this).apply {
                this.max = (max * 10).toInt()
                isFocusable = true
            }
            fun show() {
                val s = current
                val value = get(s ?: ClaritySettings.CLEARER)
                val text = if (unit != null) String.format(java.util.Locale.US, "%.1f %s", value, unit) else "${(value / max * 100).toInt()}%"
                label.text = "${getString(title)}   $text"
                if (bar.progress != (value * 10).toInt()) bar.progress = (value * 10).toInt()
                bar.isEnabled = s != null
                label.alpha = if (s != null) 1f else 0.45f
            }
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val s = current ?: return
                    current = set(s, progress / 10f)
                    setClarity(current)
                    refresh()
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
            })
            sliders += { show() }
            column.addView(label)
            column.addView(bar)
        }
        slider(R.string.sound_dialogue, 10f, "dB", { it.dialogue }) { s, v -> s.copy(dialogue = v) }
        slider(R.string.sound_air, 1.5f, null, { it.air }) { s, v -> s.copy(air = v) }
        slider(R.string.sound_treble, 8f, "dB", { it.treble }) { s, v -> s.copy(treble = v) }
        slider(R.string.sound_mud, 6f, "dB", { it.mud }) { s, v -> s.copy(mud = v) }
        slider(R.string.sound_focus, 1f, null, { it.focus }) { s, v -> s.copy(focus = v) }
        slider(R.string.sound_tame, 1f, null, { it.tame }) { s, v -> s.copy(tame = v) }
        refresh()

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.sound_title)
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setNeutralButton(R.string.sound_reset) { _, _ -> setClarity(ClaritySettings.CLEARER) }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun cycleSleepTimer() {
        sleepIndex = (sleepIndex + 1) % sleepOptions.size
        val minutes = sleepOptions[sleepIndex]
        handler.removeCallbacks(sleepRunnable)
        if (minutes == 0) {
            sleepView.text = getString(R.string.sleep)
            Toast.makeText(this, getString(R.string.sleep_off), Toast.LENGTH_SHORT).show()
        } else {
            handler.postDelayed(sleepRunnable, minutes * 60_000L)
            sleepView.text = getString(R.string.sleep_n, minutes)
            Toast.makeText(this, getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ up next

    private fun nextEpisodeIndex(index: Int): Int? {
        val item = items.getOrNull(index) ?: return null
        val next = items.getOrNull(index + 1) ?: return null
        return if (item.kind == WatchKind.EPISODE && next.kind == WatchKind.EPISODE && next.seriesId == item.seriesId) index + 1 else null
    }

    private fun updateUpNext() {
        val p = player ?: return
        if (inPip) return
        val index = p.currentMediaItemIndex
        val nextIndex = nextEpisodeIndex(index)
        val duration = p.duration
        if (nextIndex == null || duration == C.TIME_UNSET || duration < 60_000 || index == upNextCancelledFor) {
            hideUpNext()
            return
        }
        val remaining = duration - p.currentPosition
        if (remaining > UP_NEXT_MS || remaining < 0) {
            hideUpNext()
            return
        }
        if (upNextShownFor != index) {
            upNextShownFor = index
            val next = items[nextIndex]
            upNextTitle.text = next.title.substringAfter(" · ", next.title) + (next.subtitle?.let { " · $it" } ?: "")
            upNextCard.apply {
                visibility = View.VISIBLE
                alpha = 0f
                translationY = dp(40).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(350).start()
            }
            if (isTv) upNextPlay.requestFocus()
        }
        upNextCountdown.text = getString(R.string.next_episode_in, ceil(remaining / 1000.0).toInt().coerceAtLeast(1))
        upNextProgress.progress = (1000 * (1 - remaining.toFloat() / UP_NEXT_MS)).toInt().coerceIn(0, 1000)
    }

    private fun hideUpNext() {
        if (upNextShownFor == -1 && upNextCard.visibility != View.VISIBLE) return
        upNextShownFor = -1
        if (upNextCard.visibility == View.VISIBLE) {
            upNextCard.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(250)
                .withEndAction { upNextCard.visibility = View.GONE }
                .start()
        }
    }

    /** Fades the new episode in from black and shows its title for a moment. */
    private fun announceEpisode(index: Int) {
        if (inPip) return
        val item = items.getOrNull(index) ?: return
        curtain.animate().cancel()
        curtain.visibility = View.VISIBLE
        curtain.alpha = 1f
        curtain.animate().alpha(0f).setStartDelay(250).setDuration(900)
            .withEndAction { curtain.visibility = View.GONE }
            .start()

        titleCardText.text = item.title.substringAfter(" · ", item.title) + (item.subtitle?.let { "\n$it" } ?: "")
        titleCard.animate().cancel()
        titleCard.visibility = View.VISIBLE
        titleCard.alpha = 0f
        titleCard.scaleX = 0.94f
        titleCard.scaleY = 0.94f
        titleCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(350)
            .withEndAction {
                titleCard.animate().alpha(0f).setStartDelay(2200).setDuration(500)
                    .withEndAction { titleCard.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    // ------------------------------------------------------------------ playback

    /** Media controls in the notification shade and on the lock screen while something plays. */
    private fun startPlaybackNotification(exo: ExoPlayer) {
        playbackNotification?.setPlayer(null)
        val adapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence =
                player.mediaMetadata.title ?: getString(R.string.app_name)

            override fun getCurrentContentText(player: Player): CharSequence? = player.mediaMetadata.artist

            override fun createCurrentContentIntent(player: Player): PendingIntent? =
                PendingIntent.getActivity(
                    this@PlayerActivity, 1,
                    Intent(this@PlayerActivity, PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback,
            ): android.graphics.Bitmap? {
                val uri = player.mediaMetadata.artworkUri ?: return null
                lifecycleScope.launch {
                    val request = coil.request.ImageRequest.Builder(this@PlayerActivity)
                        .data(uri)
                        .allowHardware(false)
                        .size(512)
                        .build()
                    val drawable = runCatching { coil.Coil.imageLoader(this@PlayerActivity).execute(request).drawable }.getOrNull()
                    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { callback.onBitmap(it) }
                }
                return null
            }
        }
        playbackNotification = runCatching {
            PlayerNotificationManager.Builder(this, PLAYBACK_NOTIFICATION_ID, PLAYBACK_CHANNEL_ID)
                .setChannelNameResourceId(R.string.now_playing)
                .setMediaDescriptionAdapter(adapter)
                .build()
                .apply {
                    setUseNextActionInCompactView(true)
                    setUsePreviousActionInCompactView(true)
                    setUseFastForwardAction(false)
                    setUseRewindAction(false)
                    setSmallIcon(R.drawable.ic_stat_play)
                    mediaSession?.let { session ->
                        (session.sessionCompatToken.token as? android.media.session.MediaSession.Token)?.let { setMediaSessionToken(it) }
                    }
                    setPlayer(exo)
                }
        }.getOrNull()
    }

    private fun mediaItem(item: PlayItem, url: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.subtitle)
                    .apply { item.image?.let { runCatching { setArtworkUri(android.net.Uri.parse(it)) } } }
                    .build()
            )
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

        val clarityProcessor = ClarityProcessor()
        clarity = clarityProcessor
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(clarityProcessor))
                    .build()
        }.setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers)
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

        playerView.player = controlsPlayer(exo)
        mediaSession?.release()
        mediaSession = runCatching {
            MediaSession.Builder(this, exo).setId("shasha-${System.nanoTime()}").build()
        }.getOrNull()
        startPlaybackNotification(exo)
        player = exo
        // After `player` is set: it reads the current item from it (before, the saved setting was skipped).
        applyCurrentClarity()
        // Downloads wait while we stream on plans that allow only one connection.
        Downloads.setStreaming(items.any { it.url.startsWith("http") })
        trackedIndex = startIndex
        lastPosition = 0
        lastDuration = 0
        onItemStarted(startIndex, seekToSaved = false)
        updatePipParams()
        // Start with the full controls (top bar included), for channels as for films and episodes;
        // they hide together after a few seconds.
        if (!inPip) playerView.showController()
    }

    private fun releasePlayer() {
        val p = player ?: return
        pendingZap?.let { handler.removeCallbacks(it) }
        pendingZap = null
        track()
        saveCurrent()
        startIndex = p.currentMediaItemIndex.coerceIn(0, items.lastIndex)
        p.removeListener(listener)
        clarity = null
        playbackNotification?.setPlayer(null)
        playbackNotification = null
        mediaSession?.release()
        mediaSession = null
        p.release()
        player = null
        Downloads.setStreaming(false)
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
        } else if (item.kind == WatchKind.EPISODE) {
            // Last episode the player had (end of a season): find what follows in the series.
            Playback.markNextAcrossSeasons(this, item)
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
        if (current != null) {
            lines.add(getString(R.string.epg_now, current.title, time.format(Date(current.start)), time.format(Date(current.end))))
        }
        if (next != null) lines.add(getString(R.string.epg_next, next.title, time.format(Date(next.start))))
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
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.err_stream_refused)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> getString(R.string.err_stream_network)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> getString(R.string.err_stream_format)
            else -> getString(R.string.err_stream_generic, error.errorCodeName)
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
        if (items.size > 1) actions.add(pipAction(CMD_PREVIOUS, R.drawable.ic_pip_prev, getString(R.string.previous), 1))
        val playing = p?.isPlaying == true
        actions.add(
            pipAction(
                CMD_PLAY_PAUSE,
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                getString(if (playing) R.string.pause else R.string.play),
                2,
            )
        )
        if (items.size > 1) actions.add(pipAction(CMD_NEXT, R.drawable.ic_pip_next, getString(R.string.next), 3))
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
        const val UP_NEXT_MS = 15_000L
        private const val PLAYBACK_NOTIFICATION_ID = 4202
        private const val PLAYBACK_CHANNEL_ID = "playback"
    }
}
