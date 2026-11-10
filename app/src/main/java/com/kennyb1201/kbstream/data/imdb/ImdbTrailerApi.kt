package com.kennyb1201.kbstream.data.imdb

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * A title's primary video, straight from IMDb's own data service - no title
 * page, no WebView.
 *
 * The same endpoint the reference implementation uses, with KBStream's OWN
 * client name: `x-imdb-client-name` identifies this app to IMDb, and borrowing
 * another app's name would make a refusal impossible to attribute to this
 * build. The query is the smallest one that answers the question:
 * `title.primaryVideos(first: 1)` with each rendition's URL, mime type and
 * definition label.
 *
 * A refusal or a failure pauses the endpoint rather than the trailer: the title
 * page is the fallback while paused, and a pause that expires is how the
 * service comes back without a restart.
 */
internal object ImdbTrailerApi {

    private const val TAG = "ImdbTrailerApi"

    private const val ENDPOINT = "https://api.graphql.imdb.com/"

    /**
     * KBStream's own name on the wire. Deliberately not the reference app's:
     * this is our traffic and must be attributable to this build.
     */
    private const val CLIENT_NAME = "kbstream"

    /** The whole exchange's budget: IMDb answers fast or the page takes over. */
    internal const val BUDGET_MS = 4_000L

    private const val QUERY =
        "query TitleTrailer(\$id: ID!) { title(id: \$id) { primaryVideos(first: 1) " +
            "{ edges { node { id playbackURLs { url videoMimeType videoDefinition } } } } } }"

    private val backoff = ImdbApiBackoff()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(BUDGET_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * The title's primary video, or [ImdbTrailerData.Result.Pending] when IMDb
     * did not answer (paused, refused, failed, timed out). A Pending answer is
     * what sends the resolver to the title page.
     */
    internal suspend fun primaryVideo(imdbId: String): ImdbTrailerData.Result {
        if (!IMDB_ID_REGEX.matches(imdbId)) return ImdbTrailerData.Result.Pending
        if (!backoff.allowed(System.currentTimeMillis())) {
            Log.d(TAG, "IMDb API paused; the title page answers")
            return ImdbTrailerData.Result.Pending
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-imdb-client-name", CLIENT_NAME)
            .post(bodyFor(imdbId).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = withTimeoutOrNull(BUDGET_MS) { post(request) }
        if (body == null) {
            backoff.failed(System.currentTimeMillis())
            return ImdbTrailerData.Result.Pending
        }
        val result = ImdbTrailerData.parseApiResponse(body)
        when {
            // A refusal says the request was not answered, never that the title
            // has nothing: pause, and let the page decide.
            ImdbTrailerData.hasErrors(body) -> backoff.refused(System.currentTimeMillis())
            result != ImdbTrailerData.Result.Pending -> backoff.succeeded()
            else -> backoff.failed(System.currentTimeMillis())
        }
        if (ImdbTrailerData.hasErrors(body)) {
            Log.w(TAG, "IMDb refused the lookup for $imdbId; pausing the API")
        }
        return result
    }

    /** Test seam: the request body this client sends. */
    internal fun bodyFor(imdbId: String): String = JSONObject().apply {
        put("query", QUERY)
        put("variables", JSONObject().put("id", imdbId))
    }.toString()

    /** The answer body, or null for a non-2xx / transport failure. */
    private suspend fun post(request: Request): String? =
        suspendCancellableCoroutine<String?> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.use {
                            if (!it.isSuccessful) {
                                null
                            } else {
                                runCatching { it.body?.string() }.getOrNull()
                            }
                        }
                        if (continuation.isActive) continuation.resume(body)
                    }
                }
            )
        }

    /** Test seam: forget any pause. */
    internal fun resetBackoff() = backoff.reset()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val IMDB_ID_REGEX = Regex("^tt\\d+$")
}
