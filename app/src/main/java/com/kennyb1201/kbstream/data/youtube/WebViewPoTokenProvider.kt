package com.kennyb1201.kbstream.data.youtube

import android.content.Context
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
 *    with), the GVS tokens against their video (an LRU - trailers resolve one at
 *    a time);
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

    /** The player token and the visitor it was minted for. */
    private var player: Pair<String, String>? = null

    /** videoId -> GVS token, least-recently used first out. */
    private val gvsTokens = object : LinkedHashMap<String, String>(maxGvsTokens, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxGvsTokens
    }

    private val mintMutex = Mutex()

    override suspend fun playerToken(visitorData: String): String? {
        if (!enabled()) {
            PoTokenTrace.record(PoTokenContext.PLAYER, PoTokenState.NONE)
            return null
        }
        synchronized(lock) {
            player?.let { (visitor, token) ->
                if (visitor == visitorData) {
                    PoTokenTrace.record(PoTokenContext.PLAYER, PoTokenState.CACHED)
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

    override suspend fun gvsToken(videoId: String, visitorData: String): String? {
        if (!enabled()) {
            PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.NONE)
            return null
        }
        synchronized(lock) { gvsTokens[videoId] }?.let {
            PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.CACHED)
            return it
        }

        return mintMutex.withLock {
            // A concurrent resolve may have minted while this one waited.
            synchronized(lock) { gvsTokens[videoId] }?.let {
                PoTokenTrace.record(PoTokenContext.GVS, PoTokenState.CACHED)
                return@withLock it
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

            val bind = bindState(minted.visitorData, visitorData)
            val gvsState =
                if (minted.gvsToken != null) PoTokenState.MINTED else PoTokenState.NONE
            val playerState =
                if (minted.playerToken != null) PoTokenState.MINTED else PoTokenState.NONE

            if (bind == "mismatch") {
                // A PO token is bound to the visitor it was minted for, so a
                // token minted for ANOTHER visitor is not a token for this
                // resolve: spending it is a refusal with extra steps. Neither
                // token is cached or returned - the resolve then takes exactly
                // the tokenless path it would have taken with no mint at all -
                // and both lines say why (`spent=no`).
                //
                // Never the visitor string itself: it is a live session
                // identifier, and the state is what a capture needs.
                PoTokenTrace.record(
                    PoTokenContext.GVS,
                    gvsState,
                    elapsedMs,
                    bind = bind,
                    // Only a token that exists can be withheld; the other half
                    // of a partial mint has nothing to say beyond `state=none`.
                    spent = if (minted.gvsToken != null) false else null
                )
                PoTokenTrace.record(
                    PoTokenContext.PLAYER,
                    playerState,
                    null,
                    bind = bind,
                    spent = if (minted.playerToken != null) false else null
                )
                Log.w(
                    TAG,
                    "PO token minted for a different visitor than the watch config " +
                        "holds (gvs=$gvsState player=$playerState bind=$bind " +
                        "${elapsedMs}ms); withholding both, resolving without one"
                )
                return@withLock null
            }

            synchronized(lock) {
                minted.playerToken?.let { player = visitorData to it }
                minted.gvsToken?.let { gvsTokens[videoId] = it }
            }

            PoTokenTrace.record(
                PoTokenContext.GVS,
                gvsState,
                elapsedMs,
                bind = bind
            )
            PoTokenTrace.record(
                PoTokenContext.PLAYER,
                playerState,
                null,
                bind = bind
            )
            // A SUCCESS is a warning too, on purpose: it is the line that tells
            // a field capture the minter works on this box, and it used to be
            // only in the diagnostics report - which a refusal now survives,
            // but which a support log without the report still would not show.
            Log.w(
                TAG,
                "PO token minted gvs=$gvsState player=$playerState bind=$bind " +
                    "${elapsedMs}ms for $videoId"
            )
            minted.gvsToken
        }
    }

    override fun invalidate() {
        synchronized(lock) {
            player = null
            gvsTokens.clear()
        }
        PoTokenTrace.recordInvalidated()
        // Warning, not debug: this is the evidence a field capture needs that
        // the refusal path cleared the tokens (Fire OS suppresses debug).
        Log.w(TAG, "PO token cache cleared")
    }

    /**
     * `match` / `mismatch` / `unknown`. A page loaded in a fresh cookie jar can
     * be a different visitor from the watch config's; that is diagnostic, not a
     * reason to discard the mint (the caller's visitor is what the request
     * carries, and §4 handles a refused token).
     */
    private fun bindState(pageVisitor: String?, requestVisitor: String): String = when {
        pageVisitor.isNullOrBlank() -> "unknown"
        pageVisitor == requestVisitor -> "match"
        else -> "mismatch"
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L
}

/**
 * The WebView half: one offscreen WebView, created on the main thread for the
 * duration of one mint and destroyed when it ends (or times out).
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
) : PoTokenMinter {

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
            val webView = runCatching { WebView(appContext) }.getOrNull()
                ?: return@withContext null

            try {
                mintOn(webView, videoId, visitorData)
            } finally {
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
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
        webView: WebView,
        videoId: String,
        visitorData: String
    ): MintedPoTokens? {
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
        // Before the load: the page's BotGuard mints for whichever visitor its
        // cookie jar presents, and a fresh WebView's jar is an empty one.
        seedVisitor(visitorData)
        webView.loadUrl(youtubeEmbedUrl(videoId))

        val json = bridge.await() ?: return null
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

    private val result = CompletableDeferred<String?>()

    @JavascriptInterface
    fun report(json: String) {
        result.complete(json)
    }

    suspend fun await(): String? = result.await()
}
