package com.kennyb1201.kbstream.ui.addons

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Best-effort LAN IPv4 address of this TV, used to build the phone pairing
 * URL. Returns null when the device isn't on a network.
 */
fun localIpv4Address(): String? {
    return runCatching {
        // Skip virtual/tethering-style interfaces so the pairing URL doesn't
        // point at an unreachable address (common on TV boxes with VPN or
        // Ethernet+dummy interfaces).
        val virtualIfaceNameParts =
            listOf(
                "tun",
                "tap",
                "docker",
                "dummy",
                "ppp",
                "wpan",
                "bnep",
                "bt-"
            )

        val candidates =
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { networkInterface ->
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        virtualIfaceNameParts.none { part ->
                            networkInterface.name.contains(part, ignoreCase = true)
                        }
                }
                ?.flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                }
                ?.filter { address ->
                    !address.isLoopbackAddress && address is Inet4Address
                }
                ?.toList()
                .orEmpty()

        // Prefer a private LAN address (the phone has to reach this IP), then
        // fall back to any non-loopback IPv4.
        candidates.firstOrNull { address ->
            address.isSiteLocalAddress
        }?.hostAddress
            ?: candidates.firstOrNull()?.hostAddress
    }.getOrNull()
}

/**
 * Tiny HTTP server that runs while the Add Add-on dialog is open so a
 * manifest URL can be pasted from a phone browser instead of typed on the
 * TV remote. Serves a dark, phone-friendly form; a successful submission
 * delivers the URL back through [onUrlReceived].
 */
class PhoneUrlServer(
    private val onUrlReceived: (String) -> Unit,
    private val onReceived: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    /** Local port the pairing page is served on (0 until [start]). */
    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun start() {
        if (serverSocket != null) return
        // Bind synchronously so [port] is valid immediately; the accept loop
        // then runs on the background scope.
        serverSocket = ServerSocket(
            0,
            4,
            InetAddress.getByName("0.0.0.0")
        )
        scope.launch { acceptLoop() }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun acceptLoop() {
        val socket = serverSocket ?: return
        try {
            socket.soTimeout = 1000
            while (scope.isActive) {
                val client = try {
                    socket.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }

                try {
                    handleClient(client)
                } catch (_: Exception) {
                    // A single malformed request must not kill the server.
                } finally {
                    runCatching { client.close() }
                }
            }
        } catch (_: Exception) {
            // Socket closed via stop(); nothing to do.
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5000

        val reader = client.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty()

        var url: String? = null

        if (method == "GET" && path.startsWith("/send")) {
            url = queryParam(path, "url")
        } else if (method == "POST") {
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength =
                        line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength > 0 && contentLength <= MAX_BODY_BYTES) {
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                url = queryParam(String(body, 0, read), "url")
            }
        }

        val pageHtml: String
        if (!url.isNullOrBlank()) {
            val decoded = runCatching {
                URLDecoder.decode(url, "UTF-8")
            }.getOrDefault(url)
            onUrlReceived(decoded)
            onReceived()
            pageHtml = SUCCESS_PAGE
        } else {
            pageHtml = FORM_PAGE
        }

        val responseBody = pageHtml.toByteArray()
        val response =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${responseBody.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        client.getOutputStream().run {
            write(response.toByteArray())
            write(responseBody)
            flush()
        }
    }

    private fun queryParam(
        uriPath: String,
        key: String
    ): String? {
        val query = uriPath.substringAfter('?', "")
        if (query.isEmpty()) return null

        return query.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index > 0 && pair.substring(0, index) == key) {
                    pair.substring(index + 1)
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private companion object {
        const val MAX_BODY_BYTES = 8192

        const val FORM_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>KBStream</title><style>body{background:#0e131a;color:#eaeef5;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}div{width:100%;max-width:420px}h1{font-size:20px;margin:0}p{color:#9aa7b5;font-size:14px;margin:6px 0 18px}input{width:100%;padding:14px;font-size:16px;border-radius:10px;border:1px solid #2a3442;background:#161d27;color:#fff;box-sizing:border-box;margin-bottom:12px}button{width:100%;padding:14px;font-size:16px;font-weight:700;border:none;border-radius:10px;background:#e8a33d;color:#101318;cursor:pointer}</style></head><body><div><h1>KBStream</h1><p>Paste the add-on manifest URL and send it to your TV.</p><form method="post" action="/"><input name="url" type="text" inputmode="url" placeholder="https://example.com/manifest.json" autocomplete="off" autofocus><button type="submit">Send to TV</button></form></div></body></html>"""

        const val SUCCESS_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sent</title><style>body{background:#0e131a;color:#eaeef5;font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;text-align:center;padding:16px}div{max-width:400px}h1{margin:0}</style></head><body><div><h1>Sent &check;</h1><p>The manifest URL was sent to your TV. You can close this page.</p></div></body></html>"""
    }
}

/**
 * Renders [content] as a QR code bitmap, or null if it can't be encoded.
 */
fun qrcodeBitmap(
    content: String,
    sizePx: Int
): Bitmap? {
    return runCatching {
        val matrix = QRCodeWriter()
            .encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx
            )

        val bitmap =
            Bitmap.createBitmap(
                sizePx,
                sizePx,
                Bitmap.Config.RGB_565
            )

        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(
                    x,
                    y,
                    if (matrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }

        bitmap
    }.getOrNull()
}