package com.kennyb1201.kbstream.data.trakt

import com.kennyb1201.kbstream.data.tmdb.TmdbAuthorDetails
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Supplementary review source: Trakt's public comments API. Most titles
 * carry only a handful of written TMDB reviews; Trakt's community comments
 * (sorted by likes, so the substantive reviews surface first) pad them out.
 *
 * Endpoints used:
 *   GET /movies/{id}/comments?sort=likes&page=N
 *   GET /shows/{id}/comments?sort=likes&page=N
 * The {id} path segment accepts an IMDb id (tt...), which the app already
 * resolves for OMDb. Auth is the same unauthenticated client-id header the
 * Nuvio list browsing uses (see NuvioContentLoader.TRAKT_CLIENT_ID) —
 * comments are public data, no OAuth needed.
 *
 * Everything fails soft (empty list): reviews are supplementary and must
 * never block or break the detail screen.
 */
object TraktCommentsClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Nuvio's own bundled Trakt app client id — the same key NuvioContentLoader
    // already uses for unauthenticated public-list browsing.
    private const val CLIENT_ID =
        "0183a5b53aef4c46b1b42a4cb1f9afc0e68a1e4f13b78017e5b3a26c8b63f57c"

    private const val API_BASE = "https://api.trakt.tv"

    /** Trakt comments page size is 10; two pages cap the request cost. */
    private const val MAX_PAGES = 2

    /**
     * Top-liked user comments/reviews for a movie or series, looked up by
     * IMDb id (e.g. tt0111161). Returned as TmdbReview so the existing
     * ReviewCard/overlay render them unchanged; ids are "trakt:" prefixed
     * so they never collide with TMDB review ids during de-dup.
     */
    suspend fun fetchReviews(imdbId: String, type: String): List<TmdbReview> =
        withContext(Dispatchers.IO) {
            if (!imdbId.startsWith("tt")) return@withContext emptyList()
            val kind = if (type == "series") "shows" else "movies"
            val results = mutableListOf<TmdbReview>()

            (1..MAX_PAGES).forEach { page ->
                val url = "$API_BASE/$kind/$imdbId/comments?sort=likes&page=$page"
                val body = runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("trakt-api-key", CLIENT_ID)
                            .header("trakt-api-version", "2")
                            .header("Accept", "application/json")
                            .build()
                    ).execute().use { response ->
                        if (!response.isSuccessful) null else response.body?.string()
                    }
                }.getOrNull() ?: return@withContext results

                val arr = runCatching { JSONArray(body) }.getOrNull()
                    ?: return@withContext results

                for (i in 0 until arr.length()) {
                    val comment = parseComment(arr.optJSONObject(i) ?: continue)
                    if (comment != null) results += comment
                }

                // Short page = last page, stop early.
                if (arr.length() < 10) return@withContext results
            }

            results
        }

    /**
     * Comment JSON -> TmdbReview. Skips blank bodies; "trakt:{id}" keys
     * keep merged lists distinct from TMDB reviews.
     */
    private fun parseComment(obj: JSONObject): TmdbReview? {
        val text = obj.optString("comment")
        if (text.isBlank()) return null
        val user = obj.optJSONObject("user")
        val author = user?.optString("name")?.takeIf { it.isNotBlank() }
            ?: user?.optString("username")?.takeIf { it.isNotBlank() }
            ?: "Trakt user"
        val rating = if (obj.has("user_rating") && !obj.isNull("user_rating")) {
            obj.optDouble("user_rating")
        } else {
            null
        }
        return TmdbReview(
            id = "trakt:${obj.optLong("id")}",
            author = author,
            authorDetails = rating?.let { TmdbAuthorDetails(rating = it) },
            content = text,
            createdAt = obj.optString("created_at").takeIf { it.isNotBlank() }
        )
    }
}
