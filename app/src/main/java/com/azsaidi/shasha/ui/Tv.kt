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
import androidx.compose.ui.focus.onFocusChanged
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
    width: Dp = 3.dp,
    focusedScale: Float = 1f,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(if (focused) Modifier.border(width, color, shape) else Modifier)
}
