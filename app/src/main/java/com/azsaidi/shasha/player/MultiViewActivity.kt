package com.azsaidi.shasha.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
import com.azsaidi.shasha.data.Language
import com.azsaidi.shasha.data.Session

/**
 * Split screen: up to four streams at once. Sound follows the selected window. OK (or a second
 * tap) makes the selected window full screen; holding OK (or a long tap) removes it.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : ComponentActivity() {

    private inner class Tile(val item: PlayItem) {
        val frame = FrameLayout(this@MultiViewActivity)
        val view = PlayerView(this@MultiViewActivity)
        val label = TextView(this@MultiViewActivity)
        val error = TextView(this@MultiViewActivity)
        var player: ExoPlayer? = null
        var url = item.url
        var retried = false
    }

    private val tiles = ArrayList<Tile>()
    private lateinit var grid: LinearLayout
    private lateinit var hint: TextView
    private var activeIndex = 0
    private var soloIndex = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = MultiView.items.toList()
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

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xCC101418.toInt())
            }
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(20) },
        )
        setContentView(root)

        items.forEach { tiles.add(buildTile(it)) }
        arrange()
        showHint()
        hideSystemBars()

        onBackPressedDispatcher.addCallback(this) {
            if (soloIndex >= 0) {
                soloIndex = -1
                arrange()
            } else {
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tiles.forEach { startPlayer(it) }
        setActive(activeIndex)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { releasePlayer(it) }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildTile(item: PlayItem): Tile {
        val tile = Tile(item)
        tile.frame.apply {
            isFocusable = true
            isClickable = true
            isLongClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(dp(3), 0xFF38BDF8.toInt())
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.BLACK)
                    setStroke(dp(1), 0xFF22262E.toInt())
                })
            }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) setActive(tiles.indexOf(tile)) }
            setOnClickListener {
                val index = tiles.indexOf(tile)
                if (index == activeIndex) toggleSolo(index) else setActive(index)
            }
            setOnLongClickListener {
                removeTile(tile)
                true
            }
        }
        tile.view.apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            isFocusable = false
        }
        tile.label.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x99000000.toInt())
            }
        }
        tile.error.apply {
            setTextColor(0xFFFCA5A5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            visibility = View.GONE
        }
        tile.frame.addView(tile.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.frame.addView(tile.error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        tile.frame.addView(
            tile.label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(dp(10), dp(10), dp(10), dp(10)) },
        )
        return tile
    }

    /** Lays the windows out: 1 full, 2 side by side, 3–4 in a 2×2 grid, or one window when full screen. */
    private fun arrange() {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? ViewGroup)?.removeAllViews()
        grid.removeAllViews()
        val visible = if (soloIndex in tiles.indices) listOf(tiles[soloIndex]) else tiles.toList()
        val perRow = if (visible.size <= 2) visible.size.coerceAtLeast(1) else 2
        visible.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { tile ->
                (tile.frame.parent as? ViewGroup)?.removeView(tile.frame)
                row.addView(tile.frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            if (chunk.size < perRow) row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        tiles.getOrNull(if (soloIndex >= 0) soloIndex else activeIndex)?.frame?.requestFocus()
    }

    private fun setActive(index: Int) {
        if (index !in tiles.indices) return
        activeIndex = index
        tiles.forEachIndexed { i, tile ->
            tile.player?.volume = if (i == index) 1f else 0f
            tile.label.text = (if (i == index) "🔊  " else "") + tile.item.title
        }
    }

    private fun toggleSolo(index: Int) {
        soloIndex = if (soloIndex == index) -1 else index
        arrange()
    }

    private fun removeTile(tile: Tile) {
        releasePlayer(tile)
        val index = tiles.indexOf(tile)
        tiles.remove(tile)
        MultiView.items.removeAll { it.key == tile.item.key }
        Toast.makeText(this, getString(R.string.split_removed), Toast.LENGTH_SHORT).show()
        if (tiles.isEmpty()) {
            finish()
            return
        }
        soloIndex = -1
        if (index < activeIndex) activeIndex--
        activeIndex = activeIndex.coerceIn(0, tiles.lastIndex)
        arrange()
        setActive(activeIndex)
    }

    private fun startPlayer(tile: Tile) {
        if (tile.player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        // Only one window may hold audio focus; the others stay muted.
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val url = tile.url
                val alternative = when {
                    !tile.item.isLive || tile.retried -> null
                    url.endsWith(".m3u8") -> url.dropLast(5) + ".ts"
                    url.endsWith(".ts") -> url.dropLast(3) + ".m3u8"
                    else -> null
                }
                if (alternative != null) {
                    tile.retried = true
                    tile.url = alternative
                    exo.setMediaItem(mediaItem(alternative))
                    exo.prepare()
                    exo.play()
                    return
                }
                tile.error.text = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.err_stream_refused)
                    else -> getString(R.string.err_stream_generic, error.errorCodeName)
                }
                tile.error.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) tile.error.visibility = View.GONE
            }
        })
        exo.setMediaItem(mediaItem(tile.url))
        exo.volume = if (tiles.indexOf(tile) == activeIndex) 1f else 0f
        exo.prepare()
        exo.playWhenReady = true
        tile.view.player = exo
        tile.player = exo
    }

    private fun mediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (url.endsWith(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun releasePlayer(tile: Tile) {
        tile.view.player = null
        tile.player?.release()
        tile.player = null
    }

    private fun showHint() {
        val max = Session.auth?.maxConnections?.toIntOrNull()
        val lines = mutableListOf(getString(R.string.split_hint))
        if (max != null && tiles.size > max) lines.add(getString(R.string.split_connections, max.toString()))
        hint.text = lines.joinToString("\n")
        hint.alpha = 1f
        hint.visibility = View.VISIBLE
        hint.animate().alpha(0f).setStartDelay(if (lines.size > 1) 9_000 else 5_000).setDuration(600)
            .withEndAction { hint.visibility = View.GONE }
            .start()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
