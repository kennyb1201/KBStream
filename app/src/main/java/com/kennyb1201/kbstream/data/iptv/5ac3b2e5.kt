package com.kennyb1201.kbstream.data.iptv

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

object IptvHttpClient {

    fun create(): OkHttpClient {
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    suspend fun fetchTextWithRetry(
        client: OkHttpClient,
        url: String,
        maxAttempts: Int = 4,
        initialDelayMs: Long = 1_000,
        maxDelayMs: Long = 8_000
    ): String {
        return retryWithBackoff(maxAttempts, initialDelayMs, maxDelayMs) {
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

    suspend fun <T> streamXmltvWithRetry(
        client: OkHttpClient,
        url: String,
        maxAttempts: Int = 4,
        initialDelayMs: Long = 1_000,
        maxDelayMs: Long = 8_000,
        block: (InputStream) -> T
    ): T {
        return retryWithBackoff(maxAttempts, initialDelayMs, maxDelayMs) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
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

                val stream: InputStream = if (isGzip) {
                    GZIPInputStream(buffered, 64 * 1024)
                } else {
                    buffered
                }

                stream.use(block)
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
