package com.azsaidi.shasha.player

import android.content.Context
import android.content.Intent

data class PlayItem(
    val title: String,
    val url: String,
    val isLive: Boolean,
    /** Key used to remember the playback position (movies and episodes only). */
    val resumeKey: String? = null,
)

/** Hands a playlist to [PlayerActivity] without squeezing thousands of URLs through an Intent. */
object PlayerQueue {
    var items: List<PlayItem> = emptyList()
    var startIndex: Int = 0
}

fun launchPlayer(context: Context, items: List<PlayItem>, index: Int) {
    if (items.isEmpty()) return
    PlayerQueue.items = items
    PlayerQueue.startIndex = index.coerceIn(0, items.lastIndex)
    context.startActivity(Intent(context, PlayerActivity::class.java))
}
