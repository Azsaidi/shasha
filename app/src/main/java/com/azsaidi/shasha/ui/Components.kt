package com.azsaidi.shasha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

sealed class Load<out T> {
    data object Loading : Load<Nothing>()
    data class Ready<T>(val value: T) : Load<T>()
    data class Failed(val message: String) : Load<Nothing>()
}

/** Image with a letter placeholder underneath, for logos and posters. */
@Composable
fun Thumb(
    url: String?,
    label: String,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    corner: Dp = 8.dp,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                text = label.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                onSuccess = { loaded = true },
                modifier = Modifier
                    .matchParentSize()
                    .padding(if (crop) 0.dp else 4.dp),
            )
        }
    }
}

@Composable
fun LoadingView(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MessageView(title: String, message: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) { Text(action) }
        }
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is UnknownHostException -> "Can't find that server. Check the address and your internet connection."
    is SocketTimeoutException -> "The server took too long to respond. Try again."
    is ConnectException -> "Couldn't connect to the server. Check the address and port."
    is SSLException -> "Secure connection failed. Try the address with http:// instead of https://."
    is IOException -> e.message ?: "Network error"
    else -> e.message ?: "Something went wrong"
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
