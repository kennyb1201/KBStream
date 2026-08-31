package com.kennyb1201.kbstream.data.youtube

import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube trailer through a cobalt-compatible endpoint.
 *
 * Cobalt solves YouTube's po-token / proof-of-origin (pot) anti-bot check
 * server-side and returns a directly playable stream URL (`status = "stream"`)
 * or a proxied tunnel URL (`status = "tunnel"`). A self-hosted cobalt instance
 * is the most durable way to keep trailers playing as YouTube keeps locking
 * down anonymous InnerTube clients — headers/range tweaks in the app can't fix
 * a stream URL googlevideo rejects for a missing pot token.
 *
 * The endpoint base URL is supplied via the `TRAILER_PROXY_URL` build setting
 * (local.properties `TRAILER_PROXY_URL=...` or env). When it is blank this
 * resolver reports it is unconfigured and the caller falls back to the local
 * InnerTube/NewPipe extraction path.
 */
object CobaltTrailerResolver {

    private const val TAG = "CobaltTrailer"
    private const val REQUEST_TIMEOUT_MS = 25_000L

    private val jsonMediaType = "application/json".toMediaType()

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** True when a proxy base URL has been configured for the build. */
    fun isConfigured(): Boolean =
        !BuildConfig.TRAILER_PROXY_URL.isBlank()

    /**
     * Resolves [videoId] to a directly playable source, or null if the proxy
     * is unconfigured or the endpoint did not yield a playable URL.
     */
    suspend fun resolve(videoId: String): PlayableSource? {
        val base = BuildConfig.TRAILER_PROXY_URL.trim().trimEnd('/')
        if (base.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("url", "https://www.youtube.com/watch?v=$videoId")
                    put("downloadMode", "auto")
                }.toString()

                // cobalt v10 serves its processing endpoint at the instance root
                // (POST /) - not /api. It requires Accept: application/json.
                val request = Request.Builder()
                    .url("$base/")
                    .post(payload.toRequestBody(jsonMediaType))
                    .header("accept", "application/json")
                    .header("user-agent", USER_AGENT)
                    .header("origin", base)
                    .header("referer", "$base/")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "cobalt endpoint returned ${response.code}")
                        return@use null
                    }

                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: run {
                            Log.w(TAG, "cobalt returned non-JSON body")
                            return@use null
                        }

                    val status = json.optString("status")
                    val url = json.optString("url").takeIf { it.isNotBlank() }

                    // Direct-playable responses: 'tunnel' (cobalt proxies/remuxes)
                    // or 'redirect' (direct service URL). 'picker' / 'local-processing'
                    // / 'error' are not usable for direct playback.
                    if (url != null && (status == "tunnel" || status == "redirect")) {
                        // W level: Fire TV suppresses debug-level logs, and this
                        // line is the proof cobalt responded at all.
                        Log.w(TAG, "cobalt status=$status url=" + url.take(140))
                        PlayableSource.Muxed(url)
                    } else {
                        Log.w(TAG, "cobalt status=$status without playable url")
                        null
                    }
                }
            } catch (t: Throwable) {
                // Surface the real failure (connection refused, timeout, cleartext
                // block, etc.) instead of swallowing it -- it's exactly the log line
                // needed to tell whether the TV can actually reach the proxy.
                Log.w(
                    TAG,
                    "cobalt request failed at $base: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    t
                )
                null
            }
        }
    }
}