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
import android.content.Context
import coil.compose.AsyncImage
import com.azsaidi.shasha.R
import com.azsaidi.shasha.data.RefusedException
import com.azsaidi.shasha.data.InvalidAddressException
import com.azsaidi.shasha.data.NotXtreamException
import com.azsaidi.shasha.data.ServerErrorException
import com.azsaidi.shasha.data.UnexpectedResponseException
import com.azsaidi.shasha.data.WrongLoginException
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
fun LoadingView(message: String, detail: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

fun friendlyError(context: Context, e: Throwable): String = when (e) {
    is UnknownHostException -> context.getString(R.string.err_unknown_host)
    is SocketTimeoutException -> context.getString(R.string.err_timeout)
    is ConnectException -> context.getString(R.string.err_connect)
    is SSLException -> context.getString(R.string.err_ssl)
    is WrongLoginException -> context.getString(R.string.err_wrong_login)
    is NotXtreamException -> context.getString(R.string.err_not_xtream)
    is InvalidAddressException -> context.getString(R.string.err_invalid_address)
    is UnexpectedResponseException -> context.getString(R.string.err_unexpected)
    is RefusedException -> context.getString(R.string.err_denied, e.code)
    is ServerErrorException -> context.getString(R.string.err_server_code, e.code)
    is IOException -> context.getString(R.string.err_network)
    else -> context.getString(R.string.err_generic)
}

/** "12.4" for a byte count, for "x MB downloaded". */
fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
