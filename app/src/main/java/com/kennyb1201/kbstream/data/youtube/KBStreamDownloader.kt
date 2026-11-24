package com.kennyb1201.kbstream.data.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The extractor's HTTP transport: one OkHttp stack with generous timeouts.
 *
 * Constructed by the graph now (spec 2026-11-22, item 3). Its only other caller
 * is [NewPipeManager], which receives it through `install()` from
 * `MainApplication` instead of reaching for a static accessor - NewPipe's
 * `init` is a process-wide install, so the transport has to be the graph's
 * single instance and not one the extractor builds for itself.
 */
class KBStreamDownloader : Downloader() {

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Safari/537.36"
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(
                request.httpMethod(),
                request.dataToSend()?.let {
                    it.toRequestBody()
                }
            )
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            requestBuilder.removeHeader(name)

            values.forEach { value ->
                requestBuilder.addHeader(name, value)
            }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException(
                    "reCAPTCHA challenge requested",
                    request.url()
                )
            }

            val body = response.body?.string()

            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString()
            )
        }
    }
}
