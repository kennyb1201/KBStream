package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IptvHttpClient {

    /** The status a guide server answers when the stored validators still hold. */
    private const val HTTP_NOT_MODIFIED = 304

    /**
     * The longest a single call may take — connect, download, and the parse
     * that consumes the body.
     *
     * The parse is why this is minutes and not seconds: an XMLTV body is read
     * by the importer AS IT STREAMS, so the call stays open through the
     * download and every batch insert that follows it. The field device's
     * "multi-minute download plus a few hundred thousand inserts" is a healthy
     * import, and a cap sized for a download alone would cut it off mid-import.
     *
     * It is not zero, which is what it used to be. `callTimeout(0)` means no
     * bound at all, and the read window below does not supply one: that fails a
     * read that STALLS, so a server trickling a byte every couple of minutes
     * resets it forever while the import never finishes and the socket is never
     * given up. Twenty minutes is twice the window the background refresh works
     * inside ([com.kennyb1201.kbstream.work.WorkPolicies.GUIDE_SOURCE_BUDGET_MS],
     * which is what actually bounds a source within a refresh pass), so no
     * healthy import is ever cut, while a source that will never finish lets go
     * of its connection within the same session.
     */
    const val CALL_TIMEOUT_MS = 20L * 60L * 1000L

    /**
     * What one guide fetch did.
     *
     * [NotModified] is a first-class outcome rather than an error: the server
     * is saying the file it holds is the one the caller already imported, which
     * is the whole point of sending validators - a 10-100 MB guide that has not
     * changed must not be downloaded and re-parsed every refresh.
     */
    sealed interface XmltvFetch<out T> {
        /** The body was streamed through the caller's block. [etag] and
         *  [lastModified] are the validators to store for next time. */
        data class Streamed<T>(
            val value: T,
            val etag: String?,
            val lastModified: String?
        ) : XmltvFetch<T>

        /** The server answered 304: nothing to download. */
        data object NotModified : XmltvFetch<Nothing>
    }

    /**
     * The request headers that make a fetch conditional.
     *
     * Pure and separate so the mapping is pinned by a test: a stored validator
     * that never reaches the request is a silent return to full downloads, and
     * that is invisible without one.
     */
    internal fun cacheRequestHeaders(
        validators: GuideCacheValidators?
    ): List<Pair<String, String>> {
        if (validators == null) return emptyList()
        val headers = ArrayList<Pair<String, String>>(2)
        validators.etag?.takeIf(String::isNotBlank)?.let {
            headers += "If-None-Match" to it
        }
        validators.lastModified?.takeIf(String::isNotBlank)?.let {
            headers += "If-Modified-Since" to it
        }
        return headers
    }

    /**
     * Derived from the process-wide base client. The HTTP/1.1-only probe, the
     * long read window and the VLC user agent are all still this client's own
     * - only the connection pool and dispatcher are shared, and the pool is
     * keyed by host, so an HTTP/2 connection another feature opened is never
     * handed to this HTTP/1.1-only client.
     */
    fun create(): OkHttpClient {
        return BaseHttpClient.derived {
            protocols(listOf(Protocol.HTTP_1_1))
            connectTimeout(20, TimeUnit.SECONDS)
            readTimeout(180, TimeUnit.SECONDS)
            writeTimeout(180, TimeUnit.SECONDS)
            callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            retryOnConnectionFailure(true)
            addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                chain.proceed(request)
            }
        }
    }

    suspend fun fetchTextWithRetry(
    client: OkHttpClient,
    url: String,
    maxAttempts: Int = 4,
    initialDelayMs: Long = 1_000,
    maxDelayMs: Long = 8_000
): String = withContext(Dispatchers.IO) {
    retryWithBackoff(maxAttempts, initialDelayMs, maxDelayMs) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            // A playlist served as a raw .m3u.gz (no Content-Encoding - OkHttp
            // only decodes what IT negotiated, and a file body is not that)
            // used to fail loadPlaylist's #EXTM3U check as mojibake. The magic
            // bytes are the only reliable signal, exactly as the guide fetch
            // uses them.
            // `ByteArray?.orEmpty()` does not exist in the stdlib (the
            // primitive arrays have no such overload), so the empty fallback
            // is explicit.
            val raw = response.body?.bytes() ?: ByteArray(0)
            val decoded = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                runCatching {
                    GZIPInputStream(ByteArrayInputStream(raw), 64 * 1024)
                        .use { it.readBytes() }
                }.getOrDefault(raw)
            } else {
                raw
            }
            val bodyText = decoded.toString(Charsets.UTF_8)
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${response.message}: ${bodyText.take(300)}")
            }
            if (bodyText.isBlank()) {
                error("Empty response body")
            }
            bodyText
        }
    }
}

    internal suspend fun <T> streamXmltvWithRetry(
    client: OkHttpClient,
    url: String,
    validators: GuideCacheValidators? = null,
    maxAttempts: Int = 4,
    initialDelayMs: Long = 1_000,
    maxDelayMs: Long = 8_000,
    block: suspend (InputStream) -> T
): XmltvFetch<T> = withContext(Dispatchers.IO) {
    retryWithBackoff(maxAttempts, initialDelayMs, maxDelayMs) {
        val request = Request.Builder().url(url).get().apply {
            cacheRequestHeaders(validators).forEach { (name, value) -> header(name, value) }
        }.build()
        val call = client.newCall(request)
        withCancellableCall(call) {
            call.execute().use { response ->
                // Before anything reads a body: a 304 has none, and treating it
                // as one would fail on the empty stream rather than skipping
                // the import.
                if (response.code == HTTP_NOT_MODIFIED) {
                    return@use XmltvFetch.NotModified
                }
                val body = response.body ?: error("Empty response body")
                if (!response.isSuccessful) {
                    val preview = response.peekBody(4096).string().take(300)
                    error("HTTP ${response.code} ${response.message}: $preview")
                }

                val buffered = BufferedInputStream(body.byteStream(), 64 * 1024)
                buffered.mark(2)
                val magic1 = buffered.read()
                val magic2 = buffered.read()
                buffered.reset()
                val isGzipByMagic = magic1 == 0x1f && magic2 == 0x8b
                // MAGIC BYTES DECIDE, not the URL or the headers. ORing the
                // three together let a plain-XML body at a "*.gz" URL (or one
                // whose Content-Type merely says gzip) be wrapped in
                // GZIPInputStream, throw, be retried four times and fail an
                // import the importer's own magic-only sniff would have parsed
                // happily. OkHttp has already decoded anything it negotiated
                // (that is why a decompressed body has no magic), so a magic
                // hit here is the remaining case: a raw gzip file.
                val isGzip = isGzipByMagic

                val stream: InputStream =
                    if (isGzip) GZIPInputStream(buffered, 64 * 1024) else buffered

                val value = stream.use { input -> block(input) }
                XmltvFetch.Streamed(
                    value = value,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified")
                )
            }
        }
    }
}

    /**
     * Runs [block] with [call] open, and closes the connection the moment this
     * coroutine is cancelled.
     *
     * A blocking socket read is not interrupted by coroutine cancellation. A
     * cancelled guide fetch therefore kept its connection - and
     * [XmltvImporter]'s import mutex, which it holds for the whole parse -
     * until the read window gave up, so the retry that follows a stall would
     * have queued behind the attempt it was meant to replace. `Call.cancel()`
     * closes the socket under the blocked read, which is what makes the abort
     * real rather than nominal.
     *
     * The guard is a child coroutine parked on [awaitCancellation], so its
     * `finally` runs when the parent is cancelled, not when the work finishes.
     */
    private suspend fun <T> withCancellableCall(
        call: Call,
        block: suspend () -> T
    ): T = coroutineScope {
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            guard.cancel()
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int,
        initialDelayMs: Long,
        maxDelayMs: Long,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                // Cancellation is not a failure to retry: the caller (or the
                // worker's own timeout) has already given up on this import,
                // and retrying would both swallow the cancellation and hold
                // the coroutine for another backoff window. Rethrow it so the
                // cancel propagates like any other suspend call.
                if (t is CancellationException) throw t
                lastError = t
                val isLastAttempt = attempt == maxAttempts - 1
                if (isLastAttempt) throw t
                delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
            }
        }

        throw lastError ?: IllegalStateException("Retry failed without exception")
    }
}
