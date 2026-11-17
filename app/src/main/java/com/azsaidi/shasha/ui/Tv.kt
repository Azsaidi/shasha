package com.azsaidi.shasha.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True on Google TV / Android TV. */
fun Context.isTelevision(): Boolean {
    val manager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember { context.isTelevision() }
}

/**
 * Draws a bright outline (and optionally grows slightly) when this element or anything
 * inside it has focus. Focus only happens with a remote or keyboard, so phones never see it.
 * Put it before `clickable` in the modifier chain.
 */
fun Modifier.focusRing(
    shape: Shape = RoundedCornerShape(10.dp),
    width: Dp = 4.dp,
    focusedScale: Float = 1f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    // Solid white: easy to spot on any background from across the room.
    val color = androidx.compose.ui.graphics.Color.White
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.hasFocus }
        // Draw the focused item above its neighbours so the enlarged card isn't covered.
        .zIndex(if (focused) 1f else 0f)
        // Scale only when drawing. A graphicsLayer scale changes the item's measured bounds, so
        // lists kept re-scrolling to "bring it into view" while the zoom animated (the jitter).
        .drawWithContent {
            if (scale == 1f) {
                drawContent()
            } else {
                scale(scale) { this@drawWithContent.drawContent() }
            }
        }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}

/**
 * Remembers which item was opened on a screen, so that when you come back (from the player or a
 * detail page) the remote's focus lands on that same item instead of jumping to the menu.
 */
class FocusMemory internal constructor(private val screen: String) {
    val requester = FocusRequester()
    var key by mutableStateOf(saved[screen])
        private set

    fun opened(itemKey: String) {
        key = itemKey
        saved[screen] = itemKey
        pending.add(screen)
    }

    internal suspend fun restore() {
        if (screen !in pending) return
        // The item may take a frame or two to be laid out again; retry briefly.
        repeat(15) {
            try {
                requester.requestFocus()
                pending.remove(screen)
                return
            } catch (e: IllegalStateException) {
                delay(60)
            }
        }
        pending.remove(screen)
    }

    companion object {
        private val saved = HashMap<String, String>()
        private val pending = HashSet<String>()
        fun hasPending(): Boolean = pending.isNotEmpty()
    }
}

@Composable
fun rememberFocusMemory(screen: String): FocusMemory {
    val memory = remember(screen) { FocusMemory(screen) }
    val scope = rememberCoroutineScope()
    val isTv = rememberIsTv()
    // Only needed with a remote; on touch screens the scroll position is already kept.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (isTv) scope.launch { memory.restore() } }
    return memory
}

/** Attach to the item with [key] so [FocusMemory] can focus it again. */
fun Modifier.remembered(memory: FocusMemory?, key: String): Modifier =
    if (memory != null && memory.key == key) this.focusRequester(memory.requester) else this

private class RemotePressState {
    var down = false
    var fired = false
    /** True only when this item itself has focus, not a button inside it (like a download icon). */
    var focused = false
}

/**
 * Click and long-press that also work with a TV remote: holding OK (D-pad centre / Enter)
 * for about half a second triggers [onLongClick]; a short press clicks. Touch works as usual.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val state = remember { RemotePressState() }
    val latestClick by rememberUpdatedState(onClick)
    val latestLongClick by rememberUpdatedState(onLongClick)
    this
        .onFocusChanged { state.focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            // OK on a button inside the row (e.g. its download icon) belongs to that button.
            if (!state.focused) return@onPreviewKeyEvent false
            val key = event.nativeKeyEvent
            val isOk = key.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                key.keyCode == KeyEvent.KEYCODE_ENTER ||
                key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            val longClick = latestLongClick
            if (!isOk || longClick == null) return@onPreviewKeyEvent false
            when (key.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (key.repeatCount == 0) {
                        state.down = true
                        state.fired = false
                    } else if (state.down && !state.fired) {
                        // The remote repeats the key while it's held: that's a long press.
                        state.fired = true
                        longClick()
                    }
                    true
                }
                KeyEvent.ACTION_UP -> {
                    if (state.down && !state.fired) latestClick()
                    state.down = false
                    state.fired = false
                    true
                }
                else -> false
            }
        }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}
