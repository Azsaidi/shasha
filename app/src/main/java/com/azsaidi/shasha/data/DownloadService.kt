package com.azsaidi.shasha.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.azsaidi.shasha.MainActivity
import com.azsaidi.shasha.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads films and episodes in the background, one at a time, with a notification. Being a
 * foreground service it keeps running after you leave the app. If the connection drops it waits
 * and continues from where it stopped (when the server allows), and it pauses while you stream
 * on one-connection plans.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var currentKey: String? = null
    @Volatile private var currentCall: Call? = null
    @Volatile private var stopReason = 0

    private sealed class Outcome {
        data class Done(val size: Long) : Outcome()
        data class Retry(val code: Int) : Outcome()
        data class Fail(val reason: Int) : Outcome()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(getString(R.string.downloading), null, null)
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        currentCall?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            while (true) {
                val item = withContext(Dispatchers.Main) { Downloads.next() } ?: break
                download(item)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun download(item: DownloadItem) {
        val file = Downloads.file(item)
        if (file == null || item.url.isBlank()) {
            withContext(Dispatchers.Main) { Downloads.markFailed(item.key, Downloads.REASON_NEEDS_REDOWNLOAD) }
            return
        }
        currentKey = item.key
        stopReason = 0
        var attempt = 0
        try {
            while (true) {
                val outcome = try {
                    attemptOnce(item, file)
                } catch (e: IOException) {
                    when (stopReason) {
                        STOP_CANCEL -> return
                        STOP_PAUSE -> {
                            withContext(Dispatchers.Main) { Downloads.markPaused(item.key) }
                            return
                        }
                    }
                    if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true) {
                        Outcome.Fail(Downloads.REASON_NO_SPACE)
                    } else {
                        Outcome.Retry(Downloads.REASON_DROPPED)
                    }
                }
                when (outcome) {
                    is Outcome.Done -> {
                        withContext(Dispatchers.Main) { Downloads.markDone(item.key, outcome.size) }
                        return
                    }
                    is Outcome.Fail -> {
                        withContext(Dispatchers.Main) { Downloads.markFailed(item.key, outcome.reason) }
                        return
                    }
                    is Outcome.Retry -> {
                        attempt++
                        if (attempt > MAX_ATTEMPTS) {
                            withContext(Dispatchers.Main) { Downloads.markFailed(item.key, outcome.code) }
                            return
                        }
                        withContext(Dispatchers.Main) { Downloads.markPaused(item.key) }
                        // Wait a little longer each time (the connection may be busy or flaky).
                        delay((attempt * 5_000L).coerceAtMost(60_000L))
                        if (stopReason != 0 || withContext(Dispatchers.Main) { Downloads.findByKey(item.key) == null }) return
                        if (!withContext(Dispatchers.Main) { Downloads.canDownloadNow() }) {
                            withContext(Dispatchers.Main) { Downloads.markPaused(item.key) }
                            return
                        }
                    }
                }
            }
        } finally {
            currentKey = null
            currentCall = null
        }
    }

    /** One HTTP attempt; resumes from the end of the partial file when the server supports it. */
    private suspend fun attemptOnce(item: DownloadItem, file: File): Outcome {
        val existing = if (file.exists()) file.length() else 0L
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", Http.USER_AGENT)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        val call = client.newCall(request)
        currentCall = call
        call.execute().use { response ->
            if (response.code == 416 && existing > 0) return Outcome.Done(existing)
            if (!response.isSuccessful) {
                return if (response.code in RETRYABLE) Outcome.Retry(response.code) else Outcome.Fail(response.code)
            }
            val append = response.code == 206 && existing > 0
            val body = response.body ?: throw IOException("Empty response")
            val length = body.contentLength()
            val total = if (length > 0) (if (append) existing + length else length) else 0L
            var bytes = if (append) existing else 0L
            var lastReport = 0L
            FileOutputStream(file, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (stopReason != 0) throw IOException("Stopped")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 700) {
                            lastReport = now
                            report(item, bytes, total)
                        }
                    }
                }
            }
            // The server closed early: keep what we have and continue on the next attempt.
            if (total > 0 && bytes < total) throw IOException("Connection closed early")
            return Outcome.Done(file.length())
        }
    }

    private suspend fun report(item: DownloadItem, bytes: Long, total: Long) {
        withContext(Dispatchers.Main) { Downloads.markProgress(item.key, bytes, total) }
        val percent = if (total > 0) ((bytes * 100) / total).toInt() else null
        val title = listOfNotNull(item.title, item.subtitle).joinToString(" · ")
        notify(buildNotification(title, percent, formatBytes(bytes)))
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.tab_downloads), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, percent: Int?, detail: String?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(
                listOfNotNull(percent?.let { "$it%" }, detail).joinToString(" · ").ifEmpty { getString(R.string.downloading) }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun notify(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun acquireWakeLock() {
        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shasha:downloads")?.apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_ATTEMPTS = 12
        private const val STOP_CANCEL = 1
        private const val STOP_PAUSE = 2
        private val RETRYABLE = setOf(401, 403, 408, 429, 456, 458, 500, 502, 503, 504, 509)

        @Volatile private var instance: DownloadService? = null

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Stops the download of [key] if it's the one running (it's being deleted). */
        fun cancel(key: String) {
            val service = instance ?: return
            if (service.currentKey == key) {
                service.stopReason = STOP_CANCEL
                service.currentCall?.cancel()
            }
        }

        /** Pauses whatever is downloading (to free the connection for streaming). */
        fun pauseCurrent() {
            val service = instance ?: return
            if (service.currentKey != null) {
                service.stopReason = STOP_PAUSE
                service.currentCall?.cancel()
            }
        }
    }
}
