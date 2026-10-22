package com.kennyb1201.kbstream.data.youtube

/**
 * Where a PO token is spent. The two contexts are NOT interchangeable: a
 * player-context token rides the InnerTube `youtubei/v1/player` request (in
 * `serviceIntegrityDimensions`), a GVS-context token rides the googlevideo
 * stream URL (as `pot`). YouTube binds each one to what it was minted for, so a
 * token from one context is useless - or refused - in the other.
 */
enum class PoTokenContext(val label: String) {
    PLAYER("player"),
    GVS("gvs")
}

/**
 * Mints and caches YouTube Proof-of-Origin tokens for the trailer pipeline.
 *
 * The token is the mechanism YouTube's BotGuard program produces, and the whole
 * open-source ecosystem (yt-dlp's PO-token providers, NewPipe's
 * `PoTokenProvider`) converged on it because there is no header-only
 * substitute. This is an ENHANCEMENT layer: every method may answer null, and
 * null means "resolve exactly as before, without a token".
 *
 * Implementations cache (so a resolve does not re-mint), bind (the player token
 * belongs to a visitor, the GVS token to a video) and fail soft: a mint that
 * times out, breaks, or cannot run on this device is a normal outcome.
 *
 * The visitor a token is bound to is NOT necessarily the visitor the resolve was
 * handed: the request visitor comes from a header scrape, while the minter's page
 * is issued its own session by YouTube. When they differ the page's identity wins
 * (see [effectiveVisitor]), because that is the identity the token was actually
 * minted under.
 */
interface PoTokenProvider {
    /** Token for the player request, bound to the effective session visitor. Null = unavailable. */
    suspend fun playerToken(visitorData: String): String?

    /** Token for googlevideo stream requests, bound to one video. Null = unavailable. */
    suspend fun gvsToken(videoId: String, visitorData: String): String?

    /**
     * The visitor the resolve must present for [visitorData].
     *
     * The two identities a resolve starts with are independent: the request
     * visitor is scraped from a cookie-less page fetch, the page visitor is what
     * YouTube issued the minter's own page. Seeding the scraped one into the
     * page's cookie jar does not make the page adopt it, so a mint can (and on
     * the field capture behind this spec, did) come back bound to a different
     * visitor - a good token, refused only for failing to match an arbitrary
     * scrape. The token's identity is the ground truth, so the request moves to
     * it instead of the token being thrown away.
     *
     * Defaults to the argument unchanged, so a provider that mints nothing (and
     * the kill switch) leaves every request exactly as it was.
     */
    fun effectiveVisitor(visitorData: String): String = visitorData

    /** Drop all cached tokens (identity refused / visitor changed). */
    fun invalidate()

    /**
     * Whether the token layer is on for this device.
     *
     * Callers check this before paying for a pre-warm's watch-config fetch: with
     * minting off there is no warm page to want. The mint paths re-check the
     * switch themselves, so a pre-warm that races a flip still mints nothing.
     */
    fun isEnabled(): Boolean = true

    /**
     * Loads the minter's page AHEAD of a resolve (a pre-warm).
     *
     * A resolve's mint pays for a cold WebView - process spin-up, page load and
     * the page's own BotGuard bootstrap - out of a budget sized for the mint
     * alone. The field capture behind this (0.9.23, Moto G: `pot.gvs:
     * state=failed:timeout latency=6027ms`, `pot.player: state=none`) is that
     * cost with nothing left over, and it is paid again on every cold trailer.
     * Warming moves it off the critical path: the page and its token machinery
     * are already loaded, so a resolve either spends the cached player token or
     * points the warm page at its own video.
     *
     * [videoId] is the trailer the caller expects to play when it knows one (the
     * video-scoped token can then be minted too); null warms the page alone.
     *
     * A default no-op, like the rest of this interface: an implementation with
     * nothing to warm simply does nothing, and no caller needs a null check.
     */
    suspend fun warmUp(visitorData: String, videoId: String? = null) = Unit

    /**
     * Drops the warm page (memory pressure), leaving the cached tokens.
     *
     * Default no-op. The next [warmUp] re-warms; a resolve arriving first simply
     * takes today's cold path.
     */
    fun releaseWarm() = Unit
}

/**
 * The installed provider when minting is not wired (unit tests, a build with no
 * application class, or the moment before MainApplication installs one). It
 * answers null to everything, so callers never need a null check.
 */
object NoPoTokenProvider : PoTokenProvider {
    override suspend fun playerToken(visitorData: String): String? = null
    override suspend fun gvsToken(videoId: String, visitorData: String): String? = null
    override fun invalidate() = Unit
}

/**
 * The process-wide [PoTokenProvider].
 *
 * The extractor has no Context (it runs on the IO pool, from the hero and from
 * the player), and the provider needs one for the WebView, so the app installs
 * the real provider once in `MainApplication.onCreate` and everything else
 * reads it here. The default is [NoPoTokenProvider]: a missing install degrades
 * to pre-spec behavior instead of failing a resolve.
 */
object PoTokens {

    @Volatile
    private var installed: PoTokenProvider = NoPoTokenProvider

    /** Installs the provider for this process (see MainApplication). */
    fun install(provider: PoTokenProvider) {
        installed = provider
    }

    /** The provider to consult; never null. */
    fun provider(): PoTokenProvider = installed

    /**
     * Drops every cached token. Called from [InnerTubeExtractor.invalidateConfig]
     * because a refused identity refuses the tokens minted for it: spending one
     * again would be spending a refusal twice.
     */
    fun invalidate() {
        installed.invalidate()
    }

    /**
     * Drops the warm page, if the installed provider keeps one.
     *
     * Called from MainApplication's memory-pressure hook: the page is the memory,
     * the cached tokens are strings, so pressure takes the page and the next
     * trailer trigger re-warms it (see [PoTokenProvider.releaseWarm]).
     */
    fun releaseWarm() {
        installed.releaseWarm()
    }

    /** Test seam: back to the no-op provider. */
    fun resetForTest() {
        installed = NoPoTokenProvider
    }
}

/**
 * What one BotGuard mint produced. Any field may be null: the page's own player
 * does not always expose both contexts in the same session, and a partial mint
 * is still worth keeping.
 *
 * [visitorData] is the visitor the page itself presented - the identity YouTube
 * issued the page and minted these tokens under. It is not diagnostic: a page
 * loaded in a fresh cookie jar is routinely a different visitor from the one the
 * watch config was scraped with, and the provider adopts the page's identity for
 * the resolve (see [PoTokenProvider.effectiveVisitor]) rather than discarding a
 * token for failing to match a header scrape.
 */
data class MintedPoTokens(
    val playerToken: String? = null,
    val gvsToken: String? = null,
    val visitorData: String? = null,
    /**
     * Whether this came off the pre-warmed page ([WebViewPoTokenMinter]'s kept
     * WebView) or a mint that created and destroyed its own. Diagnostic, and the
     * origin the trace prints (see [PoTokenState.SOURCE_WARM]).
     */
    val warm: Boolean = false
)

/**
 * The actual mint mechanism, separated from caching so the cache, the
 * kill switch, the timeout and the invalidation contract can be tested without
 * a WebView (and so a future provider - a local server, say - can replace it).
 */
interface PoTokenMinter {
    /**
     * Mints tokens from [videoId]'s embed page for [visitorData]. Null when this
     * device cannot mint at all (no usable WebView) or the page produced
     * nothing within its own deadline.
     */
    suspend fun mint(videoId: String, visitorData: String): MintedPoTokens?
}

/**
 * A minter that can keep one page loaded between mints (see
 * [PoTokenProvider.warmUp]).
 *
 * Separate from [PoTokenMinter] so the caching contract stays testable against a
 * plain fake: a provider whose minter is not warming behaves exactly as before.
 */
interface WarmingPoTokenMinter : PoTokenMinter {
    /**
     * Loads the page for [visitorData] without a resolve waiting on it; null when
     * the page produced nothing (still warmed - the next mint reuses it).
     *
     * [videoId] lets the video-scoped token be minted too; null warms the page on
     * a constant placeholder video, which is all the player-context token needs.
     */
    suspend fun warm(visitorData: String, videoId: String?): MintedPoTokens?

    /** Drops the warm page. Safe to call when there is none. */
    fun releaseWarm()
}

/** The states the diagnostics report names (see [PoTokenTrace]). */
internal object PoTokenState {
    const val NONE = "none"
    const val MINTED = "minted"
    const val CACHED = "cached"

    /**
     * Where a token came from: the pre-warmed page, or the mint a resolve ran
     * itself. Recorded on every mint/cache line so a field capture can say which
     * path produced the token that was spent - the whole point of the pre-warm.
     */
    const val SOURCE_WARM = "warm"
    const val SOURCE_LIVE = "live"

    /**
     * How the mint's page identity relates to the request's, as the trace prints
     * it. `adopted` is the normal cross-session case: the page presented its own
     * visitor, the resolve now presents that one.
     */
    const val BIND_MATCH = "match"
    const val BIND_ADOPTED = "adopted"
    const val BIND_UNKNOWN = "unknown"

    fun failed(error: Throwable): String = "failed:" + error.javaClass.simpleName

    fun failed(reason: String): String = "failed:$reason"
}
