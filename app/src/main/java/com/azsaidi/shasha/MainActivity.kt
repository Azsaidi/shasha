package com.azsaidi.shasha

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import com.azsaidi.shasha.player.Playback
import androidx.lifecycle.lifecycleScope
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store
import com.azsaidi.shasha.data.WatchNext
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.sortedFavourites
import com.azsaidi.shasha.ui.playSeries
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import com.azsaidi.shasha.data.Language
import com.azsaidi.shasha.ui.ShashaNav
import com.azsaidi.shasha.ui.ShashaTheme

/** Spinner over the app while a series is looked up before the player opens; blocks more taps. */
@Composable
private fun OpeningOverlay() {
    if (!Playback.opening.value) return
    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColor(0x73000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ComposeColor.White)
    }
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ShashaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box {
                        ShashaNav()
                        OpeningOverlay()
                    }
                }
            }
        }
        if (savedInstanceState == null) resumeFromHomeScreen(intent)
    }

    override fun onStart() {
        super.onStart()
        WatchNext.syncNow()
    }

    override fun onStop() {
        super.onStop()
        // Before the TV closes the app in the background.
        WatchNext.syncNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resumeFromHomeScreen(intent)
    }

    /** Something chosen in the TV home screen's Continue watching row: open its account and resume it. */
    private fun resumeFromHomeScreen(intent: Intent?) {
        val action = intent?.action
        if (action != WatchNext.ACTION_RESUME && action != WatchNext.ACTION_OPEN) return
        val accountId = intent.getStringExtra(WatchNext.EXTRA_ACCOUNT) ?: Store.lastAccountId
        // Handled once (not again when the screen is rebuilt).
        setIntent(Intent(this, MainActivity::class.java))
        if (accountId != null && Session.account?.id != accountId) {
            Store.accounts().firstOrNull { it.id == accountId }?.let { Session.open(it) }
        }
        if (action == WatchNext.ACTION_RESUME) {
            val key = intent.getStringExtra(WatchNext.EXTRA_KEY) ?: return
            val entry = Session.history.firstOrNull { it.key == key } ?: return
            lifecycleScope.launch { Playback.resume(this@MainActivity, entry) }
            return
        }
        // A favourite: a channel plays (with the other favourite channels to zap through), a film
        // plays from where it was left, a series carries on with the right episode.
        val type = intent.getStringExtra(WatchNext.EXTRA_TYPE) ?: return
        val id = intent.getStringExtra(WatchNext.EXTRA_ID) ?: return
        val favourites = Session.favorites.toList().sortedFavourites()
        val item = favourites.firstOrNull { it.type.name == type && it.id == id } ?: return
        when (item.type) {
            ContentType.LIVE -> {
                val channels = favourites.filter { it.type == ContentType.LIVE }
                Playback.channels(this, channels, channels.indexOf(item).coerceAtLeast(0))
            }
            ContentType.MOVIE -> Playback.movie(this, item.id, item.name, item.icon, item.extension)
            ContentType.SERIES -> playSeries(this, item)
        }
    }
}
