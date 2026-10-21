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
 */
interface PoTokenProvider {
    /** Token for the player request, bound to the session visitor. Null = unavailable. */
    suspend fun playerToken(visitorData: String): String?

    /** Token for googlevideo stream requests, bound to one video. Null = unavailable. */
    suspend fun gvsToken(videoId: String, visitorData: String): String?

    /** Drop all cached tokens (identity refused / visitor changed). */
    fun invalidate()
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
 * [visitorData] is the visitor the page itself presented. It is diagnostic: a
 * page loaded in a fresh cookie jar can be a different visitor from the one the
 * watch config was minted with, and that mismatch is exactly the kind of
 * binding problem a field capture has to be able to show.
 */
data class MintedPoTokens(
    val playerToken: String? = null,
    val gvsToken: String? = null,
    val visitorData: String? = null
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

/** The states the diagnostics report names (see [PoTokenTrace]). */
internal object PoTokenState {
    const val NONE = "none"
    const val MINTED = "minted"
    const val CACHED = "cached"

    fun failed(error: Throwable): String = "failed:" + error.javaClass.simpleName

    fun failed(reason: String): String = "failed:$reason"
}
