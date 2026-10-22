package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kennyb1201.kbstream.data.settings.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The trailer pipeline's PO-token provider: a caching layer over a WebView mint.
 *
 * The mint itself lives in [WebViewPoTokenMinter] (the app's own WebView loading
 * the video's embed page, whose player mints the tokens through BotGuard). This
 * class owns everything the behavioral contract pins:
 *
 *  - the kill switch: off means every method answers null without touching the
 *    minter, so requests are byte-identical to pre-spec behavior;
 *  - caching: the player token against the visitor it was minted for (a new
 *    visitor orphans it, because a token is bound to the visitor it was minted
 *    with), the GVS tokens against their video and the visitor they were minted
 *    under (an LRU - trailers resolve one at a time);
 *  - binding: the page's visitor is the identity the tokens were minted under, so
 *    a mint whose page presents a different visitor than the request was scraped
 *    with is ADOPTED - the tokens are cached under the page's visitor, the
 *    resolve is told to present that visitor, and lookups translate the request
 *    visitor through the binding. A token is still never spent against an
 *    identity other than the one it was minted under;
 *  - one mint at a time, with a hard timeout, so a stuck page cannot hold the
 *    resolve;
 *  - invalidation: a refused identity refuses its tokens too.
 *
 * The minter (not a WebView) is a constructor parameter so the whole contract
 * here - cache, timeout, kill switch, invalidation - is unit-testable.
 */
internal class WebViewPoTokenProvider(
    private val minter: PoTokenMinter,
    private val enabled: () -> Boolean = { true },
    private val mintTimeoutMs: Long = DEFAULT_MINT_TIMEOUT_MS,
    private val maxGvsTokens: Int = MAX_GVS_TOKENS
) : PoTokenProvider {

    companion object {
        const val DEFAULT_MINT_TIMEOUT_MS = 10_000L

        private const val MAX_GVS_TOKENS = 8

        /**
         * How many `request visitor -> page visitor` bindings to remember.
         *
         * One per resolve is the normal case (a single config visitor, adopted
         * once); the rest is headroom for a stale-config retry inside the 3 h
         * config TTL. Small on purpose: a binding is a redirect for a live
         * session identifier, not a cache.
         */
        private const val MAX_BINDINGS = 8

        private const val TAG = "PoTokenProvider"

        /**
         * The production provider: the app's WebView mints, and the user's
         * `youtube_po_token_enabled` preference gates every call (read per call,
         * so flipping it needs no restart).
         */
        fun from(context: Context): PoTokenProvider {
            val app = context.applicationContext
            return WebViewPoTokenProvider(
                minter = WebViewPoTokenMinter(app),
                enabled = { AppPreferences.getYouTubePoTokenEnabled(app) }
            )
        }
    }

    private val lock = Any()

    /**
     * A cached token, the page it was minted on, and the effective visitor it was
     * minted under.
     *
     * The origin travels with the token - not as a separate record - so a cache
     * hit can state `source=warm|live` on the same line as `state=cached`. The
     * visitor travels with it for a different reason: a GVS token is keyed by
     * video alone, so without the identity a cache hit could hand a token minted
     * for one session to a request presenting another. Every spend, hit or miss,
     * is accounted to the visitor it was minted under.
     */
    private data class CachedToken(
        val value: String,
        val source: String,
        val visitor: String
    )

    /** The player token, the visitor it was minted for, and where it came from. */
    private var player: Triple<String, String, String>? = null

    /** videoId -> GVS token, least-recently used first out. */
    private val gvsTokens = object : LinkedHashMap<String, CachedToken>(maxGvsTokens, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedToken>?): Boolean =
            size > maxGvsTokens
    }

    /**
     * Request visitor -> effective (page) visitor, per adoption.
     *
     * Bounded and small: a resolve has one request visitor, and only a stale-config
     * retry brings a second. The cookie jar is process-wide and persistent, so the
     * adopted identity can drift across sessions - a drift is a fresh adoption
     * (a new entry), never a stale spend, because every entry is re-read from the
     * mint that just happened.
     */
    private val bindings = object : LinkedHashMap<String, String>(MAX_BINDINGS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_BINDINGS
    }

    private val mintMutex = Mutex()

    /** Whether a player-context token is in hand, for the pre-warm trace line. */
    private fun hasPlayerToken(): Boolean = synchronized(lock) { player != null }

    override suspend fun playerToken(visitorData: String): String? {
        if (!enabled()) {
            PoTokenTrace.record(PoTokenContext.PLAYER, PoTokenState.NONE)
            return null
        }
        // Through the binding: the cached token is keyed by the visitor it was
        // minted for, which for an adopted resolve is the page's, not the scrape's.
        val effective = effectiveVisitor(visitorData)
        synchronized(lock) {
            player?.let { (visitor, token, source) ->
                if (visitor == effective) {
                    PoTokenTrace.record(
                        PoTokenContext.PLAYER,
                        PoTokenState.CACHED,
                        source = source
                    )
                    return token
                }
            }
            // A different visitor is a different identity: the old token was
            // minted for one the watch config no longer holds, so it is dropped
            // rather than kept for a visitor that may never come back.
            player = null
        }
        // A player token is minted from a video's embed page, and this method
        // has no video: it returns the token the video-scoped mint produced
        // (one WebView session yields both contexts). Null = unavailable, which
        // the caller spends as "no serviceIntegrityDimensions".
        PoTokenTrace.record(PoTokenContext.PLAYER, PoTokenState.NONE)
        return null
    }

    override fun effectiveVisitor(visitorData: String): String {
        if (!enabled()) return visitorData
        return synchronized(lock) { bindings[visitorData] ?: visitorData }
    }

    /**
     * The cached GVS token for [videoId], but only if it was minted under the
     * effective visitor the request presents. A hit under another identity is a
     * miss, not a spend.
     */
    private fun cachedGvs(videoId: String, visitorData: String): CachedToken? {
        val effective = effectiveVisitor(visitorData)
        return synchronized(lock) { gvsTokens[videoId]?.takeIf { it.visitor == effective } }
    }

    override suspend fun gvsToken(videoId: String, visitorData: String): String? {
        if (!enabled()) {
            PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.NONE)
            return null
        }
        // The GVS cache is keyed by video alone, so a hit has to be checked
        // against the identity it was minted under before it is spent: a token
        // minted for one session is not a token for a request presenting another.
        cachedGvs(videoId, visitorData)?.let { cached ->
            PoTokenTrace.record(
                PoTokenContext.GVS,
                PoTokenState.CACHED,
                source = cached.source
            )
            return cached.value
        }

        return mintMutex.withLock {
            // A concurrent resolve may have minted while this one waited.
            cachedGvs(videoId, visitorData)?.let { cached ->
                PoTokenTrace.record(
                    PoTokenContext.GVS,
                    PoTokenState.CACHED,
                    source = cached.source
                )
                return@withLock cached.value
            }

            val startedAt = System.nanoTime()
            var failure: String? = null
            val minted = try {
                withTimeout(mintTimeoutMs) { minter.mint(videoId, visitorData) }
            } catch (e: TimeoutCancellationException) {
                failure = "timeout"
                null
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                failure = error.javaClass.simpleName
                null
            }
            val elapsedMs = elapsedMs(startedAt)

            if (minted == null) {
                if (failure != null) {
                    PoTokenTrace.record(
                        PoTokenContext.GVS,
                        PoTokenState.failed(failure),
                        elapsedMs
                    )
                    // Warning, not debug: a mint that cannot produce a token is
                    // the one line a field capture needs to tell "this box has
                    // no usable WebView" from "BotGuard changed" (Fire OS
                    // suppresses debug).
                    Log.w(
                        TAG,
                        "PO token mint failed ($failure) after ${elapsedMs}ms for " +
                            "$videoId; resolving without one"
                    )
                } else {
                    PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.NONE, elapsedMs)
                    // Warning for this one too: "the page loaded but its player
                    // never showed a token" is the field signature of an
                    // upstream break, and Fire OS suppresses debug.
                    Log.w(
                        TAG,
                        "PO token mint produced no token for $videoId " +
                            "(state=none ${elapsedMs}ms)"
                    )
                }
                return@withLock null
            }

            adopt(minted, videoId, visitorData, elapsedMs, spent = true)
            minted.gvsToken
        }
    }

    /**
     * Applies one mint's result to the caches, the binding and the trace.
     *
     * Shared by the resolve's mint and the pre-warm's, so the binding rule is
     * stated once: the tokens are cached under the visitor the PAGE presented -
     * the identity YouTube issued and minted them under - the request visitor is
     * bound to it for this resolve, and a warm mint is cached with its origin just
     * like a live one.
     *
     * A page visitor that differs from the request's is no longer a refusal. It
     * used to be, and on the 0.9.25 field capture that discarded BOTH tokens of a
     * successful mint (`bind=mismatch spent=no`) and sent the resolve out
     * tokenless, because the scraped visitor and the page's are minted
     * independently and the page does not adopt a seeded cookie. The safety
     * property is unchanged - a token is never spent against an identity other
     * than the one it was minted under - it is achieved by moving the request to
     * the token's identity instead of throwing the token away.
     *
     * A mint that yields no page visitor at all keeps the previous `unknown`
     * behavior: the tokens are cached under the request visitor, nothing is
     * adopted, and the request is unchanged.
     *
     * @param spent whether the tokens are being handed to a resolve (`true`) or
     * only cached for one that has not happened yet (null, the pre-warm).
     */
    private fun adopt(
        minted: MintedPoTokens,
        videoId: String,
        requestVisitor: String,
        elapsedMs: Long?,
        spent: Boolean?
    ) {
        val pageVisitor = minted.visitorData?.takeIf { it.isNotBlank() }
        val bind = when {
            pageVisitor == null -> PoTokenState.BIND_UNKNOWN
            pageVisitor == requestVisitor -> PoTokenState.BIND_MATCH
            else -> PoTokenState.BIND_ADOPTED
        }
        // The identity the tokens belong to, and therefore the one the resolve
        // has to present. `unknown` has nothing to adopt, so it stays on the
        // visitor the request was handed - exactly as before.
        val effective = pageVisitor ?: requestVisitor
        val source =
            if (minted.warm) PoTokenState.SOURCE_WARM else PoTokenState.SOURCE_LIVE
        val gvsState =
            if (minted.gvsToken != null) PoTokenState.MINTED else PoTokenState.NONE
        val playerState =
            if (minted.playerToken != null) PoTokenState.MINTED else PoTokenState.NONE

        synchronized(lock) {
            // An adoption is recorded per mint, so a later resolve with the same
            // request visitor translates to the identity just observed - a drift
            // in the persistent cookie jar produces a fresh adoption, never a
            // spend against a session that has moved on.
            if (bind == PoTokenState.BIND_ADOPTED) bindings[requestVisitor] = effective
            minted.playerToken?.let { player = Triple(effective, it, source) }
            minted.gvsToken?.let {
                gvsTokens[videoId] = CachedToken(it, source, effective)
            }
        }

        PoTokenTrace.record(
            PoTokenContext.GVS,
            gvsState,
            elapsedMs,
            bind = bind,
            // Only a token that exists can be spent or withheld; the other half
            // of a partial mint has nothing to say beyond `state=none`.
            spent = if (minted.gvsToken != null) spent else null,
            source = source
        )
        PoTokenTrace.record(
            PoTokenContext.PLAYER,
            playerState,
            null,
            bind = bind,
            spent = if (minted.playerToken != null) spent else null,
            source = source
        )
        // A SUCCESS is a warning too, on purpose: it is the line that tells
        // a field capture the minter works on this box, and it used to be
        // only in the diagnostics report - which a refusal now survives,
        // but which a support log without the report still would not show.
        Log.w(
            TAG,
            "PO token minted gvs=$gvsState player=$playerState bind=$bind " +
                "source=$source ${elapsedMs ?: 0}ms for $videoId"
        )
    }

    override fun isEnabled(): Boolean = enabled()

    /**
     * Loads the minter's page ahead of a resolve (see [PoTokenProvider.warmUp]).
     *
     * The mint is the resolve's own - same minter, same binding rule, same
     * budget - and it runs under the same mutex, so a pre-warm and a resolve
     * cannot drive one page at once. It runs on the caller's coroutine (a
     * screen's effect, or the deferred app-start one), never on a timer: with
     * nothing to warm there is no work and no page.
     *
     * A pre-warm that overruns or fails is NOT a viewer-visible failure. The page
     * stays loaded for the resolve that follows, and the trace says `cold` so a
     * capture can see the pre-warm did not produce tokens here.
     */
    override suspend fun warmUp(visitorData: String, videoId: String?) {
        if (!enabled()) return
        val warming = minter as? WarmingPoTokenMinter ?: return
        if (visitorData.isBlank()) return

        PoTokenTrace.recordWarm(PoTokenTrace.WARM_WARMING)
        mintMutex.withLock {
            val startedAt = System.nanoTime()
            val minted = try {
                withTimeout(mintTimeoutMs) { warming.warm(visitorData, videoId) }
            } catch (e: TimeoutCancellationException) {
                Log.w(
                    TAG,
                    "PO token pre-warm exceeded ${mintTimeoutMs}ms; the resolve mints on " +
                        "the warm page instead"
                )
                null
            } catch (e: CancellationException) {
                PoTokenTrace.recordWarm(PoTokenTrace.WARM_COLD, hasPlayerToken())
                throw e
            } catch (error: Throwable) {
                Log.w(TAG, "PO token pre-warm failed (${error.javaClass.simpleName})")
                null
            }
            val adopted = minted != null
            minted?.let {
                adopt(
                    it,
                    videoId ?: WebViewPoTokenMinter.WARM_PAGE_VIDEO_ID,
                    visitorData,
                    elapsedMs(startedAt),
                    // Cached for the resolve that follows, not handed to one: the
                    // line must not claim a spend that has not happened.
                    spent = null
                )
            }
            PoTokenTrace.recordWarm(
                if (adopted) PoTokenTrace.WARM_READY else PoTokenTrace.WARM_COLD,
                hasPlayerToken()
            )
        }
    }

    /**
     * Drops the warm page on memory pressure, keeping the cached tokens.
     *
     * The page is the memory; a player token is a string. The next trigger
     * re-warms, and a resolve arriving before that takes the cold path it always
     * had.
     */
    override fun releaseWarm() {
        (minter as? WarmingPoTokenMinter)?.releaseWarm()
        PoTokenTrace.recordWarm(PoTokenTrace.WARM_COLD, hasPlayerToken())
    }

    override fun invalidate() {
        synchronized(lock) {
            player = null
            gvsTokens.clear()
            // The bindings go with the tokens: an adoption is what the tokens
            // belonged to, so a refused identity must not keep steering the next
            // resolve at the visitor whose session was refused. The next mint
            // adopts afresh or not at all.
            bindings.clear()
        }
        PoTokenTrace.recordInvalidated()
        // Warning, not debug: this is the evidence a field capture needs that
        // the refusal path cleared the tokens (Fire OS suppresses debug).
        Log.w(TAG, "PO token cache cleared")
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L
}

/**
 * The WebView half: one offscreen WebView, created once and kept warm.
 *
 * A cold mint - the only kind before the pre-warm - created a WebView, spun up
 * the WebView process, loaded the embed page and waited for the page's BotGuard
 * to bootstrap, all inside a budget sized for the mint. The 0.9.23 field capture
 * is that bill: `pot.gvs: state=failed:timeout latency=6027ms` with `pot.player:
 * state=none`, i.e. the whole window eaten before observation could run. So the
 * page is now loaded AHEAD of a resolve ([warm]) and the view is kept, with the
 * same settings, the same bridge and the same injected script; a mint then only
 * points it at its own video. [mint] reuses that page when it exists and falls
 * back to create-and-destroy when it does not, so a cold resolve behaves exactly
 * as it did.
 *
 * The page is the video's own embed page - the same page the watch config is
 * minted from - and its own player does the BotGuard work. The injected script
 * does not reimplement BotGuard: it watches the page's own requests for the
 * tokens the player mints (`pot` on a googlevideo URL, `poToken` in a player
 * request body) and reports the first of each back through a bridge. That keeps
 * the JS surface small and, more importantly, keeps this component honest: if
 * the page changes, the script finds nothing and the resolve proceeds tokenless
 * instead of sending something invalid.
 *
 * A device with no usable WebView (some Fire OS builds) answers null, which is
 * a normal outcome, not an error.
 */
internal class WebViewPoTokenMinter(
    private val appContext: Context,
    /**
     * Where the seeded visitor cookie goes. A parameter so the seeding is
     * testable at this boundary without a WebView - the one part of the mint
     * that cannot run in a unit test.
     */
    private val setCookie: (url: String, cookie: String) -> Unit = ::setWebViewCookie
) : WarmingPoTokenMinter {

    /**
     * One WebView and the bridge its page reports through, kept between mints.
     * Holding the pair (rather than two nullable fields) is what makes "one warm
     * page" true by construction.
     */
    private class WarmPage(val view: WebView, val bridge: PoTokenJsBridge)

    /** The process's single warm page, or null when none is loaded. */
    private var warmPage: WarmPage? = null

    /** Destroying a WebView has to happen on the main thread; pressure can call in anywhere. */
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "PoTokenMinter"

        /** The origin the embed page - and the visitor cookie - live on. */
        internal const val YOUTUBE_ORIGIN = "https://www.youtube.com"

        /**
         * How long the injected script watches the page before reporting what
         * it has. Silence past this is a mint that produced nothing.
         *
         * The three windows form ONE ladder, innermost first: this script
         * window (5 s) < the resolve's GVS budget
         * ([InnerTubeExtractor.GVS_TOKEN_BUDGET_MS], 6 s) < this minter's
         * provider timeout ([WebViewPoTokenProvider.DEFAULT_MINT_TIMEOUT_MS],
         * 10 s). They were 7 s inside 4 s inside 10 s, so on a slow box the
         * resolve cancelled every mint before the page could finish observing,
         * and nothing in the field capture said the mint had been close.
         */
        internal const val MINT_SCRIPT_FALLBACK_MS = 5_000L

        /**
         * The video a warm-up loads when the caller does not know which trailer
         * is coming (a cold launch: the page still has to be BOOTED, and only a
         * video's embed page does that).
         *
         * A long-lived, widely embeddable trailer - the same id the watch page
         * fallback uses - chosen only because the embed page must exist; the
         * token this mint produces is the player-context one, which is not bound
         * to a video at all.
         */
        internal const val WARM_PAGE_VIDEO_ID = "dQw4w9WgXcQ"

        /**
         * The cookie that makes the page's BotGuard mint for the visitor the
         * watch config carries. Without it the page is a fresh cookie jar, i.e.
         * a DIFFERENT visitor, and every token it mints is bound to a session
         * this device never presents (bind=mismatch; refused at playback).
         */
        internal fun visitorCookie(visitorData: String): String =
            "VISITOR_INFO1_LIVE=$visitorData; Domain=.youtube.com; Path=/"

        /** The production seam: Android's shared WebView cookie jar. */
        internal fun setWebViewCookie(url: String, cookie: String) {
            CookieManager.getInstance().setCookie(url, cookie)
        }
    }

    override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens? =
        withContext(Dispatchers.Main.immediate) {
            if (!webViewAvailable()) {
                Log.w(TAG, "No usable WebView on this device; PO tokens unavailable")
                return@withContext null
            }

            // Warm first: the page is already loaded and configured, so this
            // mint is a navigation rather than a process start.
            warmPage?.let { page ->
                val minted = try {
                    mintOn(page, videoId, visitorData)
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    // A page that threw is not a page worth keeping: drop it so
                    // the next mint starts clean instead of failing forever.
                    Log.w(TAG, "Minting on the warm page failed; dropping it", error)
                    dropWarmPage(page)
                    null
                }
                return@withContext minted?.copy(warm = true)
            }

            val page = createPage() ?: return@withContext null
            try {
                mintOn(page, videoId, visitorData)
            } finally {
                destroy(page)
            }
        }

    /**
     * Loads the page ahead of a resolve, once.
     *
     * No second page is created when one is already warm: a repeated trigger (a
     * detail screen recomposing, the hero re-arming) re-navigates the SAME view,
     * which is what keeps this at one warm WebView per process.
     */
    override suspend fun warm(visitorData: String, videoId: String?): MintedPoTokens? =
        withContext(Dispatchers.Main.immediate) {
            if (!webViewAvailable()) {
                Log.w(TAG, "No usable WebView on this device; PO tokens unavailable")
                return@withContext null
            }
            val page = warmPage ?: (createPage() ?: return@withContext null).also { warmPage = it }
            val minted = try {
                mintOn(page, videoId ?: WARM_PAGE_VIDEO_ID, visitorData)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                Log.w(TAG, "PO token pre-warm failed; dropping the warm page", error)
                dropWarmPage(page)
                null
            }
            return@withContext minted?.copy(warm = true)
        }

    override fun releaseWarm() {
        val page = warmPage ?: return
        warmPage = null
        mainHandler.post { destroy(page) }
    }

    private fun dropWarmPage(page: WarmPage) {
        if (warmPage === page) warmPage = null
        destroy(page)
    }

    private fun destroy(page: WarmPage) {
        runCatching { page.view.stopLoading() }
        runCatching { page.view.destroy() }
    }

    /**
     * One WebView, configured once: the settings, the bridge and the script
     * injection are exactly what a cold mint used to do on every call.
     */
    private fun createPage(): WarmPage? {
        val webView = runCatching { WebView(appContext) }.getOrNull() ?: return null
        val bridge = PoTokenJsBridge()
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Muted autoplay has to be allowed for the page's player to make the
            // media requests that carry `pot`; there is no user to tap anything.
            mediaPlaybackRequiresUserGesture = false
            // The same identity the watch config is minted under (Spec A's
            // desktop UA), so the page's visitor has a chance of matching.
            userAgentString = YouTubeClients.DEFAULT_USER_AGENT
        }
        webView.addJavascriptInterface(bridge, PoTokenJsBridge.NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(MINT_SCRIPT, null)
            }
        }
        // Never attached to a window, and never given a user: the page runs
        // offscreen exactly as a cold mint's did, only longer.
        return WarmPage(webView, bridge)
    }

    /**
     * Puts the watch config's visitor into the WebView's cookie jar.
     *
     * Called before [WebView.loadUrl], because the page reads the cookie when it
     * boots - seeding afterwards would hand the load a session that already
     * decided which visitor it is.
     */
    internal fun seedVisitor(visitorData: String) {
        if (visitorData.isBlank()) return
        runCatching { setCookie(YOUTUBE_ORIGIN, visitorCookie(visitorData)) }
            .onFailure {
                Log.w(TAG, "PO token mint could not seed the visitor cookie")
            }
    }

    private suspend fun mintOn(
        page: WarmPage,
        videoId: String,
        visitorData: String
    ): MintedPoTokens? {
        // One answer per navigation: the bridge's previous result belongs to the
        // page that just went away.
        page.bridge.reset()
        // Before the load: the page's BotGuard mints for whichever visitor its
        // cookie jar presents, and a fresh WebView's jar is an empty one.
        seedVisitor(visitorData)
        page.view.loadUrl(youtubeEmbedUrl(videoId))

        val json = page.bridge.await() ?: return null
        return parse(json)
    }

    private fun parse(json: String): MintedPoTokens? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val player = obj.optString("player").takeIf { it.isNotBlank() }
        val gvs = obj.optString("gvs").takeIf { it.isNotBlank() }
        if (player == null && gvs == null) return null
        return MintedPoTokens(
            playerToken = player,
            gvsToken = gvs,
            visitorData = obj.optString("visitor").takeIf { it.isNotBlank() }
        )
    }

    private fun webViewAvailable(): Boolean = runCatching {
        // API 26+, and the cheapest way to tell "no WebView installed" (some
        // Fire OS images) from "WebView present": constructing one on such a
        // device throws, which the caller treats as unavailable too.
        WebView.getCurrentWebViewPackage() != null
    }.getOrDefault(false)

    /**
     * Installed after the page loads, then it: kicks playback (the media
     * requests are what carry `pot`), keeps the page's own fetch/XHR traffic
     * under observation, mines the initial player response for a pre-minted
     * `pot`, and reports the first tokens it sees. Silence is a valid result:
     * the provider's timeout then closes the resolve without a token.
     */
    private val MINT_SCRIPT: String = """
        (function () {
          if (window.__kbPotHooked) { return; }
          window.__kbPotHooked = true;
          var found = { player: null, gvs: null };
          var finished = false;

          function visitorData() {
            try {
              var ctx = window.ytcfg && window.ytcfg.get ? window.ytcfg.get('INNERTUBE_CONTEXT') : null;
              return (ctx && ctx.client && ctx.client.visitorData) || null;
            } catch (e) { return null; }
          }

          function finish() {
            if (finished) { return; }
            finished = true;
            try {
              KBTokenBridge.report(JSON.stringify({
                player: found.player,
                gvs: found.gvs,
                visitor: visitorData()
              }));
            } catch (e) { }
          }

          function scanUrl(value) {
            if (!value || found.gvs) { return; }
            var url = String(value);
            if (url.indexOf('googlevideo.com') < 0) { return; }
            var match = url.match(/[?&]pot=([^&#]+)/);
            if (match) { found.gvs = decodeURIComponent(match[1]); finish(); }
          }

          function scanBody(value) {
            if (found.player || typeof value !== 'string') { return; }
            var match = value.match(/"poToken"\s*:\s*"([^"]+)"/);
            if (match) { found.player = match[1]; finish(); }
          }

          function scanInitial() {
            if (found.gvs) { return; }
            try {
              var raw = window.ytInitialPlayerResponse ? JSON.stringify(window.ytInitialPlayerResponse) : '';
              var match = raw.match(/googlevideo\.com[^"']*[?&]pot=([^&"'\\]+)/);
              if (match) { found.gvs = decodeURIComponent(match[1]); finish(); }
            } catch (e) { }
          }

          var nativeFetch = window.fetch;
          if (nativeFetch) {
            window.fetch = function (input, init) {
              try {
                var url = typeof input === 'string' ? input : (input && input.url);
                scanUrl(url);
                if (init && typeof init.body === 'string') { scanBody(init.body); }
              } catch (e) { }
              return nativeFetch.apply(this, arguments);
            };
          }

          var nativeOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            try { scanUrl(url); } catch (e) { }
            return nativeOpen.apply(this, arguments);
          };

          var nativeSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function (body) {
            try { scanBody(body); } catch (e) { }
            return nativeSend.apply(this, arguments);
          };

          function kick() {
            try {
              var player = document.querySelector('#movie_player');
              if (player && player.playVideo) { player.playVideo(); }
              var videos = document.querySelectorAll('video');
              for (var i = 0; i < videos.length; i++) {
                videos[i].muted = true;
                var playing = videos[i].play();
                if (playing && playing.catch) { playing.catch(function () { }); }
              }
            } catch (e) { }
          }

          scanInitial();
          kick();
          setTimeout(function () { kick(); scanInitial(); }, 300);
          setTimeout(function () { kick(); scanInitial(); }, 1200);
          setTimeout(function () { kick(); scanInitial(); }, 3000);
          setTimeout(finish, ${MINT_SCRIPT_FALLBACK_MS});
        })();
    """.trimIndent()
}

/**
 * The JS bridge for one mint: the page reports `{player, gvs, visitor}` once,
 * and the provider side awaits that single answer.
 *
 * The page is youtube.com over https in an offscreen WebView that is never
 * attached to a window and is destroyed after each mint, so there is no page
 * with a reason to call this except the script above.
 */
internal class PoTokenJsBridge {

    companion object {
        /** The name the injected script calls. */
        const val NAME = "KBTokenBridge"
    }

    private var result = CompletableDeferred<String?>()

    @JavascriptInterface
    fun report(json: String) {
        result.complete(json)
    }

    suspend fun await(): String? = result.await()

    /**
     * Ready for the next navigation. The bridge outlives one mint on a warm page
     * (its JavascriptInterface registration is per WebView), so the answer slot
     * is what has to be renewed. A late report for the previous page simply
     * completes a deferred nobody is awaiting.
     */
    fun reset() {
        result = CompletableDeferred()
    }
}
