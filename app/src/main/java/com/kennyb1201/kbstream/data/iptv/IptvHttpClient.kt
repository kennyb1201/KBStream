package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IptvHttpClient {

    /** The status a guide server answers when the stored validators still hold. */
    private const val HTTP_NOT_MODIFIED = 304

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
            callTimeout(0, TimeUnit.SECONDS)
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
            val bodyText = response.body?.string().orEmpty()
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
        client.newCall(request).execute().use { response ->
            // Before anything reads a body: a 304 has none, and treating it as
            // one would fail on the empty stream rather than skipping the
            // import.
            if (response.code == HTTP_NOT_MODIFIED) {
                return@use XmltvFetch.NotModified
            }
            val body = response.body ?: error("Empty response body")
            if (!response.isSuccessful) {
                val preview = response.peekBody(4096).string().take(300)
                error("HTTP ${response.code} ${response.message}: $preview")
            }

            val contentType = response.header("Content-Type").orEmpty()
            val contentEncoding = response.header("Content-Encoding").orEmpty()
            val isGzipByUrl = url.substringAfterLast('/', missingDelimiterValue = "")
                .contains(".gz", ignoreCase = true)
            val isGzipByHeader = contentType.contains("gzip", ignoreCase = true) ||
                contentEncoding.contains("gzip", ignoreCase = true)

            val buffered = BufferedInputStream(body.byteStream(), 64 * 1024)
            buffered.mark(2)
            val magic1 = buffered.read()
            val magic2 = buffered.read()
            buffered.reset()
            val isGzipByMagic = magic1 == 0x1f && magic2 == 0x8b
            val isGzip = isGzipByUrl || isGzipByHeader || isGzipByMagic

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
