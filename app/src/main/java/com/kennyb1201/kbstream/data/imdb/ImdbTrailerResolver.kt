package com.kennyb1201.kbstream.data.imdb

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a title's IMDb trailer to one playable URL.
 *
 * Two paths, in this order:
 *
 *  1. IMDb's data service ([ImdbTrailerApi]) - a four-second exchange that
 *     answers with the video and its renditions directly. This is the path a
 *     normal lookup takes.
 *  2. The title page, in a WebView ([__NEXT_DATA__]), only when the service did
 *     not answer usefully. Slower (~8 s measured by the reference app), which
 *     is why callers pre-resolve it in the background instead of putting it on
 *     a play press, and why [resolve] takes `allowPage`.
 *
 * The answer is cached 30 minutes on a hit and 5 minutes on a miss, capped by
 * the signed URLs' own `Expires` so a cached URL is never served past the
 * moment it can serve - the same discipline the YouTube cache follows.
 */
internal object ImdbTrailerResolver {

    private const val TAG = "ImdbTrailer"

    /** How long a resolved source is served without asking IMDb again. */
    internal const val HIT_TTL_MS = 30 * 60_000L

    /** How long "this title has no IMDb trailer" is remembered. */
    internal const val MISS_TTL_MS = 5 * 60_000L

    /** The title-page path's whole budget, across both load attempts. */
    internal const val PAGE_BUDGET_MS = 18_000L

    /** One page load's own wait: a page that has not answered by then is reloaded. */
    private const val LOAD_BUDGET_MS = 8_000L

    private const val LOAD_ATTEMPTS = 2

    private const val POLL_MS = 150L

    private const val EVAL_BUDGET_MS = 1_000L

    private const val MAX_SOURCE_ENTRIES = 64

    private const val PAGE_URL_PREFIX = "https://www.imdb.com/title/"

    private const val REFERER = "https://www.imdb.com/"

    /** A desktop UA: the title page serves its Next.js payload to that one. */
    private const val PAGE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    /**
     * Static sub-resources the page needs no part of: `__NEXT_DATA__` is in the
     * document itself. Extension-only, so it never matches scripts, XHR/fetch
     * (extensionless), the main document, or a challenge page.
     */
    private val BLOCK_EXT = Regex(
        """\.(?:css|woff2?|ttf|otf|eot|jpe?g|png|gif|webp|bmp|ico|svg|mp4|m3u8|ts|mp3|aac)(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_SOURCE_ENTRIES
    }

    /**
     * ONE temporary WebView at a time, on Main, destroyed before the permit is
     * handed on. The gate is the whole point: a hero rotation or two surfaces
     * asking at once must not stack WebViews on a TV.
     */
    private val webViewGate = Semaphore(permits = 1)

    private val manifestClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    private data class Entry(
        val pick: ImdbStreamSelector.Pick?,
        val validUntilMs: Long
    )

    /**
     * The title's trailer, or null when IMDb has nothing suitable.
     *
     * [allowPage] is what separates a play press from a pre-resolve: a play may
     * spend the four-second API exchange, but not the eighteen-second title
     * page, so a slow page never becomes a black screen. The pre-resolve
     * ([prefetch]) allows it, and fills the cache the play then reads.
     */
    internal suspend fun resolve(
        imdbId: String,
        allowPage: Boolean = false
    ): ImdbStreamSelector.Pick? {
        val id = ImdbTrailerData.imdbIdOf(imdbId) ?: return null
        cached(id)?.let { entry -> return entry.pick }

        when (val answer = ImdbTrailerApi.primaryVideo(id)) {
            is ImdbTrailerData.Result.Found -> {
                val pick = select(answer.video)
                store(id, pick)
                return pick
            }

            ImdbTrailerData.Result.Missing -> {
                Log.d(TAG, "IMDb holds no primary video for $id")
                store(id, null)
                return null
            }

            ImdbTrailerData.Result.Pending -> Unit
        }

        // The service did not answer. A play press must not wait for the page,
        // and this is NOT a miss: nothing is stored, so the next attempt is
        // free to ask the service again.
        if (!allowPage) return null

        val fromPage = resolveFromPage(id)
        val pick = fromPage?.let { select(it) }
        store(id, pick)
        return pick
    }

    /**
     * The background leg: the full lookup, title page included, so the answer
     * a play press needs is already cached. Never on a play press's critical
     * path.
     */
    internal suspend fun prefetch(imdbId: String) {
        resolve(imdbId, allowPage = true)
    }

    /** Drops a cached answer (a URL that died, say) so the next resolve re-asks. */
    internal fun invalidate(url: String) {
        synchronized(cache) {
            cache.entries.removeAll { it.value.pick?.url == url }
        }
    }

    /** Test seam. */
    internal fun clear() {
        synchronized(cache) { cache.clear() }
        ImdbTrailerApi.resetBackoff()
    }

    /** The cached answer for [imdbId], or null when absent or expired. */
    private fun cached(imdbId: String): Entry? = synchronized(cache) {
        val entry = cache[imdbId] ?: return null
        if (System.currentTimeMillis() >= entry.validUntilMs) {
            cache.remove(imdbId)
            return null
        }
        entry
    }

    /**
     * Stores an answer, capped by the signed URL's own expiry: a 30-minute TTL
     * must never outlive the URL it describes.
     */
    private fun store(imdbId: String, pick: ImdbStreamSelector.Pick?) {
        val now = System.currentTimeMillis()
        val ttl = if (pick == null) MISS_TTL_MS else HIT_TTL_MS
        val urlExpiry = pick?.let { expiresAtMs(it.url) } ?: Long.MAX_VALUE
        val validUntil = minOf(now + ttl, urlExpiry - EXPIRY_MARGIN_MS)
        if (validUntil <= now) return
        synchronized(cache) {
            cache.remove(imdbId)
            cache[imdbId] = Entry(pick, validUntil)
        }
    }

    /**
     * A signed IMDb URL's `Expires` claim, in milliseconds, or [Long.MAX_VALUE]
     * when it names none.
     *
     * IMDb's video host is CloudFront, which signs with `Expires=<epoch
     * seconds>` - the same `expire` discipline the YouTube cache reads, under
     * the other spelling.
     */
    internal fun expiresAtMs(url: String): Long {
        val raw = runCatching {
            android.net.Uri.parse(url).getQueryParameter("Expires")
                ?: android.net.Uri.parse(url).getQueryParameter("expires")
        }.getOrNull()?.trim()?.toLongOrNull() ?: return Long.MAX_VALUE
        // CloudFront uses seconds; a milliseconds value would be absurdly large.
        return if (raw > 100_000_000_000L) raw else raw * 1000L
    }

    private const val EXPIRY_MARGIN_MS = 60_000L

    /** The pick for one video, probing nothing: the MP4 wins unless a master is taller. */
    private suspend fun select(video: ImdbTrailerData.Video): ImdbStreamSelector.Pick? {
        val mp4 = ImdbStreamSelector.progressive(video)
        val wantsManifest = (mp4 == null || mp4.height < ImdbStreamSelector.CEILING_HEIGHT) &&
            video.encodings.any { it.hls }
        val heights = if (wantsManifest) manifestHeights(video) else emptyMap()
        return ImdbStreamSelector.pick(mp4, video, heights)
    }

    /**
     * Each candidate master's own height, read from the master itself.
     *
     * IMDb's `DEF_AUTO` entry is the only thing that says how tall the master
     * is, so it has to be opened - bounded to two masters and a two-second call,
     * and a master that does not answer simply is not a candidate.
     */
    private suspend fun manifestHeights(video: ImdbTrailerData.Video): Map<String, Int> {
        val heights = mutableMapOf<String, Int>()
        for (encoding in video.encodings.filter { it.hls }.distinctBy { it.url }
            .take(ImdbStreamSelector.MAX_MANIFESTS)) {
            val body = fetchManifest(encoding.url) ?: continue
            val height = ImdbStreamSelector.highestManifestHeight(body)
            if (height > 0) heights[encoding.url] = height
        }
        return heights
    }

    private suspend fun fetchManifest(url: String): String? =
        withTimeoutOrNull(2_000L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    manifestClient.newCall(Request.Builder().url(url).get().build()).execute()
                        .use { response ->
                            if (!response.isSuccessful) return@use null
                            val stream = response.body?.byteStream() ?: return@use null
                            val buffer = ByteArray(8 * 1024)
                            val out = java.io.ByteArrayOutputStream()
                            while (out.size() <= MAX_MANIFEST_BYTES) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                            if (out.size() > MAX_MANIFEST_BYTES) null else out.toString("UTF-8")
                        }
                }.getOrNull()
            }
        }

    private const val MAX_MANIFEST_BYTES = 256 * 1024

    // ── Title page (WebView) ──────────────────────────────────────────

    /**
     * The video the title page names, or null when the page has none.
     *
     * The WebView is built on Main, used, and destroyed before the gate is
     * released - including on cancellation - so a resolve that is abandoned
     * (the viewer swiped away) cannot leave a live web engine behind on a TV.
     */
    private suspend fun resolveFromPage(imdbId: String): ImdbTrailerData.Video? {
        val context = AppContextHolder.appContext ?: return null
        return webViewGate.withPermit {
            withContext(Dispatchers.Main.immediate) {
                val view = runCatching { buildView(context) }.getOrNull()
                    ?: return@withContext null
                try {
                    withTimeoutOrNull(PAGE_BUDGET_MS) { loadTrailerVideo(view, imdbId) }
                } finally {
                    withContext(NonCancellable) { destroy(view) }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView(context: Context): WebView {
        val view = WebView(context)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = PAGE_USER_AGENT
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // A visible, laid-out view: an unmeasured WebView can refuse to run the
        // page's scripts on some TV builds.
        val width = 1280
        val height = 720
        view.layoutParams = ViewGroup.LayoutParams(width, height)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        view.visibility = View.VISIBLE
        runCatching { view.resumeTimers() }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Never the document itself; only static assets by extension.
                if (request?.isForMainFrame == true) return null
                val url = request?.url?.toString() ?: return null
                if (!BLOCK_EXT.containsMatchIn(url)) return null
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0))
                )
            }
        }
        return view
    }

    private suspend fun destroy(view: WebView) {
        withContext(Dispatchers.Main.immediate) {
            runCatching { view.stopLoading() }
            runCatching { view.webViewClient = WebViewClient() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.destroy() }
        }
    }

    /**
     * Loads the title page (twice at most) and polls it for `__NEXT_DATA__`.
     *
     * `missing` is a real answer and ends the search; anything unreadable keeps
     * polling until that load's budget runs out, then the page is reloaded
     * without its cache once.
     */
    private suspend fun loadTrailerVideo(
        view: WebView,
        imdbId: String
    ): ImdbTrailerData.Video? {
        val script = ImdbTrailerData.probeScript(imdbId)
        repeat(LOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            runCatching {
                view.loadUrl(PAGE_URL_PREFIX + imdbId + "/", mapOf("Referer" to REFERER))
            }
            val answer = withTimeoutOrNull(LOAD_BUDGET_MS) {
                var result: PageAnswer = PageAnswer.Pending
                while (result == PageAnswer.Pending) {
                    delay(POLL_MS)
                    val raw = evalJs(view, script) ?: continue
                    result = when (val parsed = ImdbTrailerData.parseProbeJson(raw)) {
                        is ImdbTrailerData.Result.Found -> PageAnswer.Found(parsed.video)
                        ImdbTrailerData.Result.Missing -> PageAnswer.Missing
                        ImdbTrailerData.Result.Pending -> PageAnswer.Pending
                    }
                }
                result
            } ?: PageAnswer.Pending
            when (answer) {
                is PageAnswer.Found -> return answer.video
                PageAnswer.Missing -> return null
                PageAnswer.Pending -> Unit
            }
            if (attempt < LOAD_ATTEMPTS - 1) delay(150)
        }
        return null
    }

    /** One page load's answer: found, definitively nothing, or not readable yet. */
    private sealed interface PageAnswer {
        data class Found(val video: ImdbTrailerData.Video) : PageAnswer
        data object Missing : PageAnswer
        data object Pending : PageAnswer
    }

    /**
     * Runs the probe script and unwraps the JSON-encoded string
     * `evaluateJavascript` hands back.
     */
    private suspend fun evalJs(view: WebView, script: String): String? =
        withTimeoutOrNull(EVAL_BUDGET_MS) {
            suspendCancellableCoroutine<String?> { continuation ->
                runCatching {
                    view.evaluateJavascript(script) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
}
