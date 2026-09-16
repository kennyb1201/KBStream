package com.kennyb1201.kbstream.data.reddit

import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Supplementary review source: Reddit community discussions, fetched via the
 * public unauthenticated search JSON API — no API key needed. Trakt now
 * rejects most widely-copied third-party client ids (403) and TMDB only
 * carries a handful of written reviews per title, so this is the keyless
 * backfill that pads out the detail-page reviews row for popular titles.
 *
 * Search query: "<Title>" <year> (review OR discussion OR thoughts) with
 * sort=top/t=all, so the community's most-upvoted write-ups surface first.
 *
 * Everything fails soft (empty list): reviews are supplementary and must
 * never block or break the detail screen.
 */
object RedditDiscussionsClient {

    private const val TAG = "REDDIT_REVIEWS"

    private const val SEARCH_URL = "https://www.reddit.com/search.json"

    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Reddit heavily challenges default/okhttp User-Agents; a normal
    // browser-style UA is the variant that reliably gets the public JSON.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Hard cap so the reviews row can never balloon. */
    private const val MAX_REVIEWS = 12

    /** Selftext floor: a real write-up, not a one-liner. */
    private const val MIN_BODY_LENGTH = 120

    /**
     * Top-upvoted discussion/review posts for a title, as TmdbReview so the
     * existing ReviewCard/overlay render them unchanged. ids are "reddit:"
     * prefixed so they never collide with TMDB/Trakt review ids.
     */
    suspend fun fetchReviews(
        title: String,
        year: String?,
        type: String
    ): List<TmdbReview> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return@withContext emptyList()

        // AND-groups: quoted title + year + review-ish intent word. A post
        // must plausibly be about THIS title, not a namesake.
        val query = buildString {
            append('"').append(cleanTitle).append('"')
            if (!year.isNullOrBlank()) append(' ').append(year.trim())
            append(" (review OR discussion OR thoughts)")
        }

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("sort", "top")
            .addQueryParameter("t", "all")
            .addQueryParameter("limit", "25")
            .build()

        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    Log.w(TAG, "search HTTP ${response.code} for \"$cleanTitle\"")
                    null
                }
            }
        }.onFailure {
            Log.w(TAG, "search request failed: ${it.message}")
        }.getOrNull() ?: return@withContext emptyList()

        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return@withContext emptyList()
        val children = root.optJSONObject("data")?.optJSONArray("children")
            ?: return@withContext emptyList()

        val reviews = mutableListOf<TmdbReview>()
        for (i in 0 until children.length()) {
            val post = children.optJSONObject(i)?.optJSONObject("data") ?: continue
            val review = parsePost(post, cleanTitle) ?: continue
            reviews += review
            if (reviews.size >= MAX_REVIEWS) break
        }
        // Search is already sort=top/t=all, so insertion order preserves the
        // community's own ranking.
        reviews
    }

    /**
     * Post JSON -> TmdbReview. Quality gates: title must actually mention
     * the searched title (defends against namesake drift), body must be a
     * real write-up. authorDetails stays null — the shared cards render any
     * non-null rating as a star/10 score, and an upvote count is not one.
     * Upvotes + subreddit instead lead the review text, where they read
     * naturally in the overlay.
     */
    private fun parsePost(post: JSONObject, title: String): TmdbReview? {
        val postTitle = post.optString("title")
        if (!postTitle.contains(title, ignoreCase = true)) return null
        val text = post.optString("selftext")
        if (text.length < MIN_BODY_LENGTH) return null

        val author = post.optString("author").takeIf { it.isNotBlank() }
            ?: "reddit user"
        val score = post.optDouble("score", 0.0)
        val subreddit = post.optString("subreddit").takeIf { it.isNotBlank() }
        val permalink = post.optString("permalink")
        val createdUtc = post.optDouble("created_utc", 0.0)

        val composed = buildString {
            append(postTitle)
            append("\n\n")
            append("▲ ").append(formatScore(score))
            if (subreddit != null) append(" · r/").append(subreddit)
            if (permalink.isNotBlank()) {
                append("\nhttps://www.reddit.com").append(permalink)
            }
            append("\n\n")
            append(text)
        }

        return TmdbReview(
            id = "reddit:${post.optString("id", permalink)}",
            author = "u/$author",
            authorDetails = null,
            content = composed,
            createdAt = isoFromUtcSeconds(createdUtc),
            spoiler = false
        )
    }

    /** 12405 -> "12.4k", 863 -> "863". */
    private fun formatScore(score: Double): String =
        if (score >= 1000.0) {
            "%.1fk".format(score / 1000.0)
        } else {
            score.toInt().toString()
        }

    /** created_utc (epoch seconds) -> ISO yyyy-MM-dd… for the shared date renderer. */
    private fun isoFromUtcSeconds(seconds: Double): String? {
        if (seconds <= 0.0) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.format(Date((seconds * 1000).toLong()))
        }.getOrNull()
    }
}
