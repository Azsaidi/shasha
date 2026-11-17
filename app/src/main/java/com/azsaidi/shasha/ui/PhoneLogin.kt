package com.azsaidi.shasha.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.azsaidi.shasha.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azsaidi.shasha.data.Account
import com.azsaidi.shasha.data.AuthInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * TV sign-in from a phone: the TV runs a tiny web page on the local network and shows its address
 * as a QR code. The phone opens it, the person types their details comfortably, and the TV logs in.
 */
@Composable
fun PhoneLoginPanel(onLoggedIn: (Account, AuthInfo) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val latestOnLoggedIn by rememberUpdatedState(onLoggedIn)
    var address by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val server = PhoneLoginServer(context) { form ->
            withContext(Dispatchers.Main) { status = context.getString(R.string.phone_signing_in) }
            when (val result = performLogin(context, form.name, form.username, form.password, form.server)) {
                is LoginResult.Success -> {
                    withContext(Dispatchers.Main) {
                        status = context.getString(R.string.phone_signed_in)
                        latestOnLoggedIn(result.account, result.auth)
                    }
                    null
                }
                is LoginResult.Error -> {
                    withContext(Dispatchers.Main) { status = result.message }
                    result.message
                }
            }
        }
        val ip = localIpAddress()
        address = if (ip != null && server.start()) "http://$ip:${server.port}/${server.token}" else null
        onDispose { server.stop() }
    }

    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.phone_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        val url = address
        if (url == null) {
            Text(
                stringResource(R.string.phone_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            val qr = remember(url) { qrImage(url, 480) }
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Image(bitmap = qr, contentDescription = stringResource(R.string.qr_description), modifier = Modifier.size(220.dp))
            }
            Text(
                stringResource(R.string.phone_or_open, url.substringAfter("http://")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun qrImage(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) black else white }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun localIpAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

data class LoginForm(val name: String, val username: String, val password: String, val server: String)

/** Minimal HTTP server for the phone sign-in page. [onSubmit] returns an error message, or null on success. */
private class PhoneLoginServer(
    private val context: Context,
    private val onSubmit: suspend (LoginForm) -> String?,
) {
    val token: String = buildString {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
    var port = 0
        private set

    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start(): Boolean {
        val server = bind(8765) ?: bind(0) ?: return false
        socket = server
        port = server.localPort
        running = true
        thread(name = "phone-login", isDaemon = true) {
            while (running) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
        return true
    }

    private fun bind(port: Int): ServerSocket? = runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    }.getOrNull()

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 20_000
            val input = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?").trimEnd('/')

            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(":").trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val out = c.getOutputStream()
            fun respond(code: String, html: String) {
                val body = html.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }

            if (path != "/$token") {
                respond("404 Not Found", page(str(R.string.web_expired_title), "<p>${esc(str(R.string.web_expired))}</p>"))
                return
            }
            if (method == "POST") {
                val bytes = ByteArray(contentLength.coerceIn(0, 16_384))
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                val fields = parseForm(String(bytes, 0, read, Charsets.UTF_8))
                val form = LoginForm(
                    name = fields["name"].orEmpty(),
                    username = fields["username"].orEmpty(),
                    password = fields["password"].orEmpty(),
                    server = fields["server"].orEmpty(),
                )
                val error = runBlocking { onSubmit(form) }
                if (error == null) {
                    respond(
                        "200 OK",
                        page(
                            str(R.string.phone_signed_in),
                            "<div class=ok>&#10003;</div><p class=center>${esc(str(R.string.web_done))}</p>",
                        ),
                    )
                } else {
                    respond("200 OK", formPage(form, error))
                }
            } else {
                respond("200 OK", formPage(LoginForm("", "", "", ""), null))
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 8_192) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate { pair ->
            val key = URLDecoder.decode(pair.substringBefore("="), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            key to value
        }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun str(id: Int): String = context.getString(id)

    private fun formPage(form: LoginForm, error: String?): String = page(
        str(R.string.web_title),
        """
        <p class=muted>${esc(str(R.string.web_intro))}</p>
        ${if (error != null) "<p class=error>${esc(error)}</p>" else ""}
        <form method=post>
          <label>${esc(str(R.string.playlist_name))}<input name=name value="${esc(form.name)}"></label>
          <label>${esc(str(R.string.username))}<input name=username dir=ltr value="${esc(form.username)}" autocapitalize=off autocorrect=off required></label>
          <label>${esc(str(R.string.password))}<input name=password dir=ltr type=password value="${esc(form.password)}" required></label>
          <label>${esc(str(R.string.server_field))}<input name=server dir=ltr type=url inputmode=url value="${esc(form.server)}" placeholder="http://example.com:8080" autocapitalize=off autocorrect=off required></label>
          <button type=submit>${esc(str(R.string.web_button))}</button>
        </form>
        <p class="muted small">${esc(str(R.string.login_tip))}</p>
        """.trimIndent(),
    )

    private val direction: String
        get() = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"

    private fun page(title: String, body: String) = """
        <!doctype html><html dir="$direction"><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>${esc(title)}</title>
        <style>
          body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0e1013;color:#e6e8eb}
          main{max-width:440px;margin:0 auto;padding:28px 20px}
          h1{font-size:24px;margin:0 0 8px}
          .muted{color:#a9b1bc;font-size:15px;line-height:1.4}
          .small{font-size:13px}
          .error{background:#3b1d1d;color:#fca5a5;padding:12px;border-radius:10px}
          label{display:block;margin:14px 0;font-size:14px;color:#a9b1bc}
          input{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:14px;font-size:17px;border-radius:12px;border:1px solid #3a414b;background:#161a1f;color:#e6e8eb}
          input:focus{outline:2px solid #38bdf8;border-color:#38bdf8}
          button{width:100%;margin-top:10px;padding:15px;font-size:17px;font-weight:600;border:0;border-radius:24px;background:#38bdf8;color:#00121f}
          .ok{font-size:64px;color:#5eead4;text-align:center;margin-top:24px}
          .center{text-align:center}
        </style></head>
        <body><main><h1>${esc(title)}</h1>$body</main></body></html>
    """.trimIndent()
}
