package com.azsaidi.shasha.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.ContentType
import com.azsaidi.shasha.data.Episode
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.StreamItem
import com.azsaidi.shasha.data.WatchEntry
import com.azsaidi.shasha.data.WatchKind
import com.azsaidi.shasha.data.tidyEpisodeTitle
import com.azsaidi.shasha.player.Playback
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

private class MenuRequest(val title: String, val actions: List<MenuAction>)

/** The long-press options menu. One host at the root shows whatever was asked for. */
object ItemMenu {
    private var request by mutableStateOf<MenuRequest?>(null)

    fun show(title: String, actions: List<MenuAction>) {
        if (actions.isNotEmpty()) request = MenuRequest(title, actions)
    }

    @Composable
    fun Host() {
        val current = request ?: return
        val firstFocus = remember(current) { FocusRequester() }
        // The menu usually opens while OK is still held down. Ignore OK until it has been let go
        // and pressed again, so the release doesn't pick the first option by itself.
        val armed = remember(current) { booleanArrayOf(false) }
        AlertDialog(
            onDismissRequest = { request = null },
            title = { Text(current.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    Modifier.onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        val isOk = key.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            key.keyCode == KeyEvent.KEYCODE_ENTER ||
                            key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        if (!isOk) return@onPreviewKeyEvent false
                        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) armed[0] = true
                        !armed[0]
                    },
                ) {
                    current.actions.forEachIndexed { index, action ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusRing(RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    request = null
                                    action.onClick()
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { request = null }, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                    Text(stringResource(R.string.close))
                }
            },
        )
        LaunchedEffect(current) { runCatching { firstFocus.requestFocus() } }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun favouriteAction(context: Context, item: StreamItem): MenuAction {
    val favourite = Session.isFavorite(item)
    return MenuAction(
        context.getString(if (favourite) R.string.remove_favourite else R.string.add_favourite),
        if (favourite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
    ) {
        Session.toggleFavorite(item)
        toast(context, context.getString(if (favourite) R.string.removed_from_favourites else R.string.added_to_favourites, item.name))
    }
}

/** Options for a channel, film or series from any list. */
fun showStreamItemMenu(context: Context, item: StreamItem, onOpen: (() -> Unit)?) {
    val actions = ArrayList<MenuAction>()
    when (item.type) {
        ContentType.LIVE -> if (onOpen != null) {
            actions.add(MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow, onOpen))
        }
        ContentType.MOVIE -> {
            actions.add(MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow) {
                // Resumes from the saved position if there is one.
                Playback.movie(context, item.id, item.name, item.icon, item.extension)
            })
            if (onOpen != null) actions.add(MenuAction(context.getString(R.string.open), Icons.Filled.OpenInNew, onOpen))
        }
        ContentType.SERIES -> {
            actions.add(MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow) { playSeries(context, item) })
            if (onOpen != null) actions.add(MenuAction(context.getString(R.string.open), Icons.Filled.OpenInNew, onOpen))
        }
    }
    actions.add(favouriteAction(context, item))
    when (item.type) {
        ContentType.LIVE -> {
            if (Session.historyEntry(WatchKind.LIVE, item.id) != null) {
                actions.add(MenuAction(context.getString(R.string.remove_from_recent), Icons.Filled.HistoryToggleOff) {
                    Session.removeHistoryKey(WatchEntry.keyOf(WatchKind.LIVE, item.id))
                })
            }
        }
        ContentType.MOVIE -> {
            val entry = Session.historyEntry(WatchKind.MOVIE, item.id)
            if (entry?.resumable == true) {
                actions.add(MenuAction(context.getString(R.string.remove_from_continue), Icons.Filled.HistoryToggleOff) {
                    Session.removeHistoryKey(entry.key)
                })
            }
            actions.add(watchedAction(context, entry ?: WatchEntry(WatchKind.MOVIE, item.id, item.name, image = item.icon, extension = item.extension)))
        }
        ContentType.SERIES -> {
            if (Session.hasSeriesHistory(item.id)) {
                actions.add(MenuAction(context.getString(R.string.remove_from_continue), Icons.Filled.HistoryToggleOff) {
                    Session.removeSeriesHistory(item.id)
                })
            }
        }
    }
    ItemMenu.show(item.name, actions)
}

private val menuScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Plays a series straight from its poster: resumes, moves on to the next episode, or starts at S1 E1. */
private fun playSeries(context: Context, item: StreamItem) {
    val api = Session.api ?: return
    menuScope.launch {
        val info = runCatching { api.seriesInfo(item.id) }.getOrNull()
        if (info == null) {
            Toast.makeText(context, context.getString(R.string.couldnt_load_series), Toast.LENGTH_SHORT).show()
            return@launch
        }
        val action = seriesAction(context, info, Session.latestEpisode(item.id)) ?: return@launch
        Playback.episodes(
            context,
            item.id,
            item.name,
            info.cover ?: item.icon,
            info.seasons[action.season].orEmpty(),
            action.index,
            action.fromStart,
        )
    }
}

private fun watchedAction(context: Context, entry: WatchEntry): MenuAction {
    val watched = Session.historyEntry(entry.kind, entry.streamId)?.finished == true
    return if (watched) {
        MenuAction(context.getString(R.string.mark_unwatched), Icons.Filled.RemoveDone) { Session.removeHistoryKey(entry.key) }
    } else {
        MenuAction(context.getString(R.string.mark_watched), Icons.Filled.CheckCircle) { Session.markWatched(entry) }
    }
}

/** Options for a Continue watching card or a recently watched channel. */
fun showHistoryMenu(context: Context, entry: WatchEntry, onPlay: () -> Unit) {
    val actions = ArrayList<MenuAction>()
    actions.add(MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow, onPlay))
    when (entry.kind) {
        WatchKind.LIVE -> {
            actions.add(favouriteAction(context, StreamItem(entry.streamId, entry.title, entry.image, null, ContentType.LIVE, null, null)))
            actions.add(MenuAction(context.getString(R.string.remove_from_recent), Icons.Filled.HistoryToggleOff) {
                Session.removeHistoryKey(entry.key)
            })
        }
        WatchKind.MOVIE -> {
            actions.add(favouriteAction(context, StreamItem(entry.streamId, entry.title, entry.image, null, ContentType.MOVIE, entry.extension, null)))
            actions.add(MenuAction(context.getString(R.string.mark_watched), Icons.Filled.CheckCircle) { Session.markWatched(entry) })
            actions.add(MenuAction(context.getString(R.string.remove_from_continue), Icons.Filled.HistoryToggleOff) {
                Session.removeHistoryKey(entry.key)
            })
        }
        WatchKind.EPISODE -> {
            actions.add(MenuAction(context.getString(R.string.mark_watched), Icons.Filled.CheckCircle) { Session.markWatched(entry) })
            actions.add(MenuAction(context.getString(R.string.remove_from_continue), Icons.Filled.HistoryToggleOff) {
                Session.removeFromHistory(entry)
            })
        }
    }
    ItemMenu.show(entry.title, actions)
}

/** Options for an episode on a series page. */
fun showEpisodeMenu(
    context: Context,
    episode: Episode,
    seriesId: String,
    seriesName: String,
    image: String?,
    onPlay: () -> Unit,
) {
    val code = context.getString(R.string.episode_code, episode.season, episode.number)
    val name = tidyEpisodeTitle(episode.title, seriesName)
    val label = listOfNotNull(code, name).joinToString(" · ")
    val entry = Session.historyEntry(WatchKind.EPISODE, episode.id) ?: WatchEntry(
        kind = WatchKind.EPISODE,
        streamId = episode.id,
        title = seriesName,
        subtitle = label,
        image = episode.image ?: image,
        extension = episode.extension,
        seriesId = seriesId,
        seriesName = seriesName,
    )
    val actions = listOf(
        MenuAction(context.getString(R.string.play), Icons.Filled.PlayArrow, onPlay),
        watchedAction(context, entry),
    )
    ItemMenu.show(label, actions)
}

/** Options for a saved account. */
fun showAccountMenu(title: String, context: Context, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    ItemMenu.show(
        title,
        listOf(
            MenuAction(context.getString(R.string.open), Icons.Filled.OpenInNew, onOpen),
            MenuAction(context.getString(R.string.edit_playlist), Icons.Filled.Edit, onEdit),
            MenuAction(context.getString(R.string.remove), Icons.Filled.Delete, onRemove),
        ),
    )
}
