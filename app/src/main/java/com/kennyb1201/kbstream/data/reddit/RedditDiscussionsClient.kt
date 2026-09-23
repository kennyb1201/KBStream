package com.kennyb1201.kbstream.data.reddit

import android.util.Log
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Supplementary review source: Reddit community discussions, fetched keyless
 * from the site's Atom search feed.
 *
 * This used to call `www.reddit.com/search.json`, which Reddit no longer
 * answers: the unauthenticated JSON endpoints return 403 (usually the site's
 * "Blocked" page) to anything that is not an OAuth client, which is why the
 * reviews row showed nothing from here. `search.rss` is the same search behind
 * a different door and still answers without a key — verified live: the same
 * query returns 403 from `.json` and 200 with 25 entries from `.rss`.
 *
 * Two properties of that route shape this client:
 *  - the feed is metered to roughly one request per minute per IP (a second
 *    request inside the window answers 429), so requests are paced and each
 *    title's result is cached for the session;
 *  - the feed carries no upvote counts, so the composed review text leads with
 *    the subreddit and a link instead of a score.
 *
 * Everything fails soft (empty list): reviews are supplementary and must
 * never block or break the detail screen.
 */
object RedditDiscussionsClient {

    private const val TAG = "REDDIT_REVIEWS"

    private const val SEARCH_URL = "https://www.reddit.com/search.rss"

    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // Reddit challenges default/okhttp User-Agents; a normal browser-style UA
    // is the variant that reliably gets the feed.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Hard cap so the reviews row can never balloon. */
    private const val MAX_REVIEWS = 12

    /** Selftext floor: a real write-up, not a one-liner. */
    private const val MIN_BODY_LENGTH = 120

    private const val HTTP_RATE_LIMITED = 429

    /**
     * Measured spacing of the feed: one request per ~60s per IP (a probe that
     * fired every 20s answered 200, 429, 429, 200, 429). Requests are spaced
     * to that budget instead of being refused.
     */
    private const val MIN_INTERVAL_MS = 60_000L

    /** 429: the window has not rolled over yet — wait a little past it. */
    private const val RATE_LIMIT_BACKOFF_MS = 90_000L

    /** Anything else (403, 5xx, network): stop asking for a while. */
    private const val ERROR_BACKOFF_MS = 10 * 60_000L

    /** Titles already looked up, so reopening one costs no request. */
    private const val CACHE_SIZE = 12

    @Volatile
    private var nextRequestAtMs = 0L

    private val cache = object :
        LinkedHashMap<String, List<TmdbReview>>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<TmdbReview>>
        ): Boolean = size > CACHE_SIZE
    }

    private val cacheLock = Any()

    /**
     * Title+year discussion posts for a title, as TmdbReview so the existing
     * ReviewCard/overlay render them unchanged. ids are "reddit:" prefixed so
     * they never collide with TMDB review ids.
     */
    suspend fun fetchReviews(
        title: String,
        year: String?,
        type: String
    ): List<TmdbReview> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return@withContext emptyList()

        val cacheKey = "$cleanTitle|${year?.trim().orEmpty()}"
        synchronized(cacheLock) { cache[cacheKey] }?.let { return@withContext it }

        val now = System.currentTimeMillis()
        if (now < nextRequestAtMs) {
            Log.i(
                TAG,
                "skipped \"$cleanTitle\": feed is paced, " +
                    "${(nextRequestAtMs - now) / 1000}s until the next slot"
            )
            return@withContext emptyList()
        }

        val url = SEARCH_URL.toHttpUrl().newBuilder()
            // Quoted title and the year: the feed's own relevance ranking is
            // loose, so the query itself has to pin the title down. (The old
            // JSON query's "(review OR discussion OR thoughts)" OR-group
            // returned subreddit listings and off-topic threads.)
            .addQueryParameter("q", "\"$cleanTitle\"" + (year?.trim()?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
            .addQueryParameter("sort", "top")
            .addQueryParameter("t", "all")
            .build()

        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    if (response.code == HTTP_RATE_LIMITED) {
                        nextRequestAtMs = now + RATE_LIMIT_BACKOFF_MS
                        Log.w(
                            TAG,
                            "search.rss 429 — the feed allows about one request " +
                                "per minute; pausing for " +
                                "${RATE_LIMIT_BACKOFF_MS / 1000}s"
                        )
                    } else {
                        nextRequestAtMs = now + ERROR_BACKOFF_MS
                        Log.w(
                            TAG,
                            "search.rss HTTP ${response.code} for \"$cleanTitle\" — " +
                                "pausing for ${ERROR_BACKOFF_MS / 60_000} min"
                        )
                    }
                    null
                }
            }
        }.onFailure {
            nextRequestAtMs = System.currentTimeMillis() + ERROR_BACKOFF_MS
            Log.w(TAG, "search.rss request failed: ${it.message}")
        }.getOrNull() ?: return@withContext emptyList()

        nextRequestAtMs = System.currentTimeMillis() + MIN_INTERVAL_MS

        val reviews = parseFeed(body, cleanTitle)
        synchronized(cacheLock) { cache[cacheKey] = reviews }
        Log.i(TAG, "search.rss returned ${reviews.size} discussion(s) for \"$cleanTitle\"")
        reviews
    }

    /**
     * Atom feed -> reviews, in feed order (the feed is already the search's
     * top-sorted order). No logging: this is the part the unit tests drive.
     */
    internal fun parseFeed(xml: String, title: String): List<TmdbReview> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        // Ignore any DTD/entities the feed might carry; a failed setFeature
        // (some parsers reject it) must not take the whole parse down.
        runCatching {
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
            )
        }

        val builder = runCatching { factory.newDocumentBuilder() }.getOrNull()
            ?: return emptyList()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = runCatching {
            builder.parse(
                ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8))
            )
        }.getOrNull() ?: return emptyList()

        val entries = doc.getElementsByTagName("entry")
        val reviews = mutableListOf<TmdbReview>()
        for (i in 0 until entries.length) {
            val entry = entries.item(i) as? Element ?: continue
            val review = parseEntry(entry, title) ?: continue
            reviews += review
            if (reviews.size >= MAX_REVIEWS) break
        }
        return reviews
    }

    /** One Atom `<entry>` -> TmdbReview, or null when it fails a gate. */
    private fun parseEntry(entry: Element, title: String): TmdbReview? {
        val postTitle = firstText(entry, "title").orEmpty()
        // A post must plausibly be about THIS title, not a namesake.
        if (!postTitle.contains(title, ignoreCase = true)) return null

        val link = firstHref(entry) ?: return null
        // Skip the feed's community results (r/Shrek, r/2001) and any other
        // non-thread link: only posts have a /comments/ path.
        if (!link.contains("/comments/")) return null

        val text = htmlToText(firstText(entry, "content").orEmpty())
        if (text.length < MIN_BODY_LENGTH) return null

        val subreddit = SUBREDDIT_RE.find(link)?.groupValues?.get(1)
        val postId = POST_ID_RE.find(link)?.groupValues?.get(1)
        val author = firstText(entry, "name")
            ?.removePrefix("/u/")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "reddit user"

        val composed = buildString {
            append(postTitle)
            append("\n\n")
            if (subreddit != null) append("r/").append(subreddit)
            append("\n").append(link)
            append("\n\n")
            append(text)
        }

        return TmdbReview(
            id = "reddit:${postId ?: link}",
            author = "u/$author",
            authorDetails = null,
            content = composed,
            // The feed's <updated> starts with an ISO date, which is all the
            // card's date renderer reads (it splits on the "T").
            createdAt = firstText(entry, "updated"),
            spoiler = false
        )
    }

    /** First non-blank text of the entry's [tag], entity-decoded by the parser. */
    private fun firstText(entry: Element, tag: String): String? =
        entry.getElementsByTagName(tag).item(0)?.textContent?.takeIf { it.isNotBlank() }

    /** The entry's thread link (the one pointing at /comments/). */
    private fun firstHref(entry: Element): String? {
        val links = entry.getElementsByTagName("link")
        var fallback: String? = null
        for (i in 0 until links.length) {
            val href = (links.item(i) as? Element)?.getAttribute("href")
                ?.takeIf { it.isNotBlank() } ?: continue
            if (href.contains("/comments/")) return href
            if (fallback == null) fallback = href
        }
        return fallback
    }

    private val SUBREDDIT_RE = Regex("/r/([^/]+)/comments/")
    private val POST_ID_RE = Regex("/comments/([a-z0-9]+)")

    /**
     * The feed's `<content>` is the post's rendered HTML (entity-escaped in
     * the XML, so the parser hands it back as markup). Strip it to readable
     * text: comments/blocks out, block tags to line breaks, tags off, entities
     * decoded, then collapse the whitespace the tags left behind.
     */
    internal fun htmlToText(html: String): String =
        html
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .let(::unescapeEntities)
            .replace(Regex("[ \\t\\u00a0]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun unescapeEntities(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
}
