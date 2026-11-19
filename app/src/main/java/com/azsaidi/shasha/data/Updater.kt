package com.azsaidi.shasha.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.azsaidi.shasha.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Checks GitHub for a newer release of the app, downloads the APK and hands it to Android's installer. */
object Updater {
    /** Newest releases first. Windows releases live in the same repo, so pick the newest with an APK. */
    private const val RELEASES = "https://api.github.com/repos/Azsaidi/shasha/releases?per_page=30"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float?) : State
        /** Android wants the "install unknown apps" permission for Shasha first. */
        data class NeedsPermission(val file: File) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun dismiss() {
        state = State.Idle
    }

    /** "1.0.20" > "1.0.19"; compares number by number. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Without GitHub's API (which allows only 60 checks an hour per connection and then answers
     * 403): the "latest release" page redirects to the newest Android release (Windows releases
     * are never marked latest), and the APK's address follows from its tag.
     */
    private fun latestWithoutApi(): Release {
        val request = Request.Builder()
            .url("https://github.com/Azsaidi/shasha/releases/latest")
            .header("User-Agent", "Shasha/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val tag = response.request.url.pathSegments.lastOrNull().orEmpty()
            if (!tag.startsWith("v")) throw IllegalStateException("no release found")
            val version = tag.trimStart('v', 'V')
            return Release(version, "https://github.com/Azsaidi/shasha/releases/download/$tag/Shasha-$version.apk", 0)
        }
    }

    suspend fun check() {
        state = State.Checking
        state = try {
            val release = withContext(Dispatchers.IO) {
                runCatching { fromApi() }.getOrElse { latestWithoutApi() }
            }
            if (isNewer(release.version, currentVersion)) State.Available(release) else State.UpToDate(currentVersion)
        } catch (e: Exception) {
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fromApi(): Release {
        val request = Request.Builder()
            .url(RELEASES)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Shasha/$currentVersion")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val releases = org.json.JSONArray(response.body?.string().orEmpty())
            for (r in 0 until releases.length()) {
                val json = releases.getJSONObject(r)
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) continue
                val tag = json.optString("tag_name")
                if (!tag.startsWith("v")) continue
                val assets = json.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        return Release(tag.trimStart('v', 'V'), asset.optString("browser_download_url"), asset.optLong("size"))
                    }
                }
            }
            throw IllegalStateException("no APK in the releases")
        }
    }

    /** Downloads the update (showing progress), then opens the installer. */
    suspend fun download(context: Context, release: Release) {
        state = State.Downloading(release, 0f)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "Shasha-${release.version}.apk")
                val request = Request.Builder().url(release.apkUrl).header("User-Agent", "Shasha/$currentVersion").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    var done = 0L
                    var lastShown = 0L
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (done - lastShown > 256 * 1024) {
                                    lastShown = done
                                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                                    withContext(Dispatchers.Main) { state = State.Downloading(release, fraction) }
                                }
                            }
                        }
                    }
                }
                out
            }
            install(context, file)
        } catch (e: kotlinx.coroutines.CancellationException) {
            state = State.Idle
            throw e
        } catch (e: Exception) {
            state = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            state = State.NeedsPermission(file)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        state = try {
            context.startActivity(intent)
            State.Idle
        } catch (e: ActivityNotFoundException) {
            State.Failed(e.message ?: "no installer")
        }
    }

    /** Opens the system screen where "Allow from this source" is turned on for Shasha. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
