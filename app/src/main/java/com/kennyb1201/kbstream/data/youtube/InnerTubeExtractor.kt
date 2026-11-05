package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct YouTube player-API extractor (modeled after KB's approach).
 *
 * Talks straight to YouTube's InnerTube `youtubei/v1/player` endpoint — the
 * same API the official YouTube apps use — with the client chain in
 * [YouTubeClients] (android -> tv -> ios -> web_embedded -> android_vr). Every
 * format it returns carries the client that signed it, because googlevideo only
 * serves a signed URL to that client's User-Agent.
 *
 * The watch config (INNERTUBE_API_KEY + VISITOR_DATA) is minted from the VIDEO'S
 * OWN EMBED PAGE and cached for 3 hours: the visitor belongs to the embedded
 * client context - the one built for third-party playback - instead of an
 * unrelated desktop watch page that no mobile/VR client context ever used. The
 * extractor then resolves progressive, adaptive (video+audio) or HLS manifests.
 * This is far more reliable than NewPipe/Piped because there is no third-party
 * extractor to break and no public instance that can go down.
 */
object InnerTubeExtractor {

    private const val TAG = "InnerTubeExtractor"
    // Hard cap per extraction pass. Two passes (initial + fresh-config retry)
    // can therefore never exceed ~30s total; trailers are background content
    // and a resolution that drags past this is worse than falling back to the
    // backdrop quickly.
    private const val EXTRACTOR_TIMEOUT_MS = 15_000L
    private const val CONFIG_TTL_MS = 3 * 60 * 60 * 1000L // 3 hours

    /**
     * How long a resolve may wait for the PLAYER token before proceeding without
     * one.
     *
     * This one is a cache read: the player token is a byproduct of the
     * video-scoped mint below (see [mintTokens]), so by the time it is asked for
     * it either is in the provider's cache or will not exist at all. Long
     * budgets here would only buy waiting.
     */
    private const val TOKEN_BUDGET_MS = 4_000L

    /**
     * How long the resolve waits for the GVS token - the one that runs a WebView.
     *
     * Trailers are background content, so attestation must not become the long
     * pole in a resolve that caps at [EXTRACTOR_TIMEOUT_MS] - but a mint that is
     * cancelled just before it lands is worse than no mint: the resolve goes
     * tokenless AND the capture cannot tell that the mint nearly worked. This
     * budget therefore sits ABOVE the minter's own script window
     * ([WebViewPoTokenMinter.MINT_SCRIPT_FALLBACK_MS], 5 s) and BELOW its
     * provider timeout ([WebViewPoTokenProvider.DEFAULT_MINT_TIMEOUT_MS], 10 s),
     * so the innermost window is always the one that decides:
     *
     *   5 s script  <  6 s GVS budget  <  10 s provider timeout.
     *
     * The first version of this ladder was 7 s inside 4 s inside 10 s: on a slow
     * box (a 1.9 GB Onn) WebView creation and the embed page's own player start
     * routinely exceeded 4 s, so every mint was abandoned before it could report
     * anything.
     */
    internal const val GVS_TOKEN_BUDGET_MS = 6_000L

    /**
     * The least time a second GVS mint may be given before it is not worth
     * asking: the mint's own script window is ~5 s, so anything under a second
     * cannot finish and would only delay the fallback the resolve is about to
     * walk anyway.
     */
    internal const val MIN_REMINT_BUDGET_MS = 1_000L

    /**
     * Whether a resolve that ended without a GVS token did so because the MINT
     * failed rather than because there was nothing to mint with.
     *
     * A cold WebView is the first case: minting is on, a visitor exists to bind
     * the token to, and the mint still produced nothing - so the next resolve
     * (or a re-mint) can still save it. With minting off, or with no visitor,
     * the resolve is tokenless BY CONFIGURATION: a second mint cannot help and
     * nothing about the resolve should differ from pre-spec behavior.
     */
    internal fun isColdMintTokenless(
        gvsToken: String?,
        visitorData: String?,
        mintingEnabled: Boolean
    ): Boolean = gvsToken.isNullOrBlank() &&
        !visitorData.isNullOrBlank() &&
        mintingEnabled

    private const val DEFAULT_USER_AGENT = YouTubeClients.DEFAULT_USER_AGENT

    /**
     * Preferred client for the trailer's FIRST mount: the adaptive video/audio
     * picks, the HLS manifest pick and the progressive pick all take this
     * client's source whenever one is usable, and fall back to the rest of the
     * pool only when it has none. android_vr used to be first but its sessions
     * have started returning LOGIN_REQUIRED at higher rates; android/tv/ios
     * remain the reliable anonymous clients. Every source this extractor
     * returns carries its client's UA so playback requests match the signature.
     *
     * The progressive and HLS picks used to rank the WHOLE pool by format score
     * first, with client priority only as the last tiebreak, so a higher-scored
     * android_vr source won the first mount and died at ~15 s (2026-11-05
     * capture, video aHrMFtt40_Q: android_vr progressive, fully tokened, died at
     * 15.1 s; the android replacement resumed and played to the end). A client
     * is preferred here, not banned: when it has no usable source the pool is
     * every eligible client again, android_vr included.
     */
    internal const val PREFERRED_SEPARATE_CLIENT = "android"

    /**
     * The three source shapes a resolve can return, as the death line names
     * them: a trailer URL class that refuses offset reads is not the same on an
     * HLS manifest as on a progressive googlevideo URL.
     */
    internal const val KIND_HLS = "hls"
    internal const val KIND_ADAPTIVE = "adaptive"
    internal const val KIND_PROGRESSIVE = "progressive"

    private const val PREFERRED_VIDEO_MIME = "avc"

    /**
     * How many audio candidates to CDN-probe (best-first) before giving up
     * on the adaptive pair and falling back to a muxed progressive stream.
     * Bounds worst-case resolve latency: each probe is a ~1 MB ranged GET
     * with a 2 s connect / 5 s read timeout.
     */
    private const val MAX_AUDIO_PROBES = 3


    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
    private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
    private val QUALITY_LABEL_REGEX = Regex("(\\d{2,4})p")

    private data class CachedConfig(
        val apiKey: String,
        val visitorData: String?,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    /**
     * What one client's player response contributes to the pools.
     *
     * [status] is `playabilityStatus.status` verbatim (null when the response
     * carried none). A LOGIN_REQUIRED response is counted, not pooled - it is
     * the signal that the identity itself is being refused, and when every
     * client answers that way the cached config is thrown away.
     */
    internal data class ClientHarvest(
        val status: String?,
        val hlsUrl: String? = null,
        val progressive: List<StreamCandidate> = emptyList(),
        val video: List<StreamCandidate> = emptyList(),
        val audio: List<StreamCandidate> = emptyList()
    ) {
        val loginRequired: Boolean get() = status == "LOGIN_REQUIRED"

        /** False for a real error status (UNPLAYABLE, ERROR, ...). */
        val playable: Boolean get() = status == null || status == "OK"
    }

    private data class RequestResponse(
        val ok: Boolean,
        val status: Int,
        val body: String
    )

    private val DEFAULT_HEADERS = mapOf(
        "accept-language" to "en-US,en;q=0.9",
        "user-agent" to DEFAULT_USER_AGENT
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val cachedConfig = AtomicReference<CachedConfig?>(null)
    private val configMutex = Mutex()

    /**
     * A resolve's answer: the source, and which client signed it.
     *
     * The client is carried because a URL class can be unreadable past its
     * opening window (the 2026-10-22 field case: every offset read 403s while
     * `bytes=0-<pos>` still serves), and the only way to recover from that is
     * to re-resolve EXCLUDING the client whose URL died. A caller that only
     * gets the source cannot name the exclusion.
     */
    internal data class ResolvedSource(
        val source: PlayableSource,
        val client: String?,
        /**
         * True when this source came back WITHOUT a GVS token although the
         * resolve could have carried one - minting enabled, a visitor to bind it
         * to, and a mint that produced nothing (a cold WebView).
         *
         * Set on EVERY winner the resolve can produce - HLS, adaptive and
         * progressive - because the wall is a property of the URL the token
         * would have signed, not of the shape the winner happened to take. The
         * adaptive case was established first (2026-11-03), and the progressive
         * case is the one that then shipped unmounted-guarded and died at
         * 41.9 s of a 138.5 s trailer (2026-11-05, `kind=progressive`,
         * `pot=false`, TCL Android 12). HLS is the same URL family; leaving any
         * of them unguarded is the same first-mount death on a different line.
         *
         * Read by [TrailerPlayerLauncher.resolvePlayableUrl], which refuses to
         * mount such a source and walks the NewPipe chain instead. A tokenless
         * resolve BY CONFIGURATION (minting off, no visitor) is NOT this flag:
         * that resolve is byte-identical to pre-spec behavior.
         */
        val potlessByColdMint: Boolean = false,
        /**
         * Whether the player request that produced this source presented a
         * player-context token, and whether the returned URL(s) carry `pot`.
         *
         * Carried because the pair is only true NOW: the death line has to name
         * the state this source MOUNTED with, and by the time a replacement
         * resolve has run, the trace's last resolve belongs to that replacement
         * (2026-11-05: an `android_vr` mount died while a `client android`
         * replacement played, and the capture could not say which wall the dead
         * one had hit). See `PoTokenTrace.recordMount`.
         */
        val playerToken: Boolean = false,
        val pot: Boolean = false
    )

    /**
     * Resolves a YouTube video to a directly playable [PlayableSource].
     * Returns null if no client could produce a usable stream.
     */
    suspend fun extractPlaybackSource(youtubeUrl: String): PlayableSource? =
        extractPlaybackSourceWithClient(youtubeUrl)?.source

    /**
     * The same resolve, naming the client that signed the winning source.
     *
     * [excludeClients] skips those clients when the winners are picked - HLS,
     * adaptive video/audio and progressive alike. The harvest still runs (the
     * requests are what make the fresh visitor's URLs), only the SELECTION
     * skips them, because the refusal is a property of the URL class a client
     * signs, not of the resolve or the identity. An empty set (every ordinary
     * resolve) selects exactly as before.
     *
     * A SET, not one key: one exclusion answers one URL class, and a video whose
     * refusal survives it needs the next class excluded, which is a cumulative
     * set (see HeroTrailerResumeLadder).
     */
    // internal, not public: its answer carries [ResolvedSource], which is an
    // implementation detail of the extractor (the client that signed the URL).
    // The module's own caller is TrailerPlayerLauncher; a public signature here
    // cannot name an internal type, and the compiler correctly refused it.
    internal suspend fun extractPlaybackSourceWithClient(
        youtubeUrl: String,
        excludeClients: Set<String> = emptySet()
    ): ResolvedSource? =
        withContext(Dispatchers.IO) {
            if (youtubeUrl.isBlank()) return@withContext null

            Log.d(TAG, "Starting extraction for $youtubeUrl (exclude=${excludeDescription(excludeClients)})")

            var resolved: ResolvedSource? = null
            try {
                resolved = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                    extractInternal(
                        youtubeUrl,
                        forceRefreshConfig = false,
                        excludeClients = excludeClients
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.w(TAG, "Extractor failed: ${error.message}")
            }

            // Retry with a fresh watch config if the first attempt failed
            if (resolved == null) {
                Log.d(TAG, "First attempt failed; retrying with fresh config")
                try {
                    resolved = withTimeout(EXTRACTOR_TIMEOUT_MS) {
                        extractInternal(
                            youtubeUrl,
                            forceRefreshConfig = true,
                            excludeClients = excludeClients
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    Log.w(TAG, "Extractor retry failed: ${error.message}")
                }
            }

            if (resolved == null) {
                Log.w(TAG, "No playable source for $youtubeUrl")
            }
            resolved
        }

    private suspend fun extractInternal(
        youtubeUrl: String,
        forceRefreshConfig: Boolean,
        excludeClients: Set<String> = emptySet()
    ): ResolvedSource? {
        val videoId = extractVideoId(youtubeUrl) ?: return null
        // The id is known before the config call on purpose: the visitor is
        // minted on THIS video's embed page, not on an unrelated one.
        val config = ensureWatchConfig(videoId, forceRefresh = forceRefreshConfig)
        // One mint per resolve covers both contexts: the WebView loads this
        // video's embed page and the page's player produces its player token
        // and its googlevideo token together. Either may be null - and a token
        // the COLD WebView failed to produce gets one more GVS-only attempt,
        // accounted against this pass's own budget (see remintGvsWhenTokenless).
        //
        // The scraped visitor is named once: the mint binds its tokens to it and
        // the tokenless verdict below reads the same value. Deliberately NOT
        // presented to the player request - that one takes the effective
        // identity (`visitor` below), never the scrape.
        val configVisitor = config.visitorData
        val mintStartedAtMs = System.nanoTime()
        val tokens = remintGvsWhenTokenless(
            videoId = videoId,
            visitorData = configVisitor,
            tokens = mintTokens(videoId, configVisitor),
            mintStartedAtMs = mintStartedAtMs
        )
        // The request moves to the token's identity, not the other way round: a
        // token is bound to the visitor the PAGE was issued, and the scraped
        // config visitor is a different session that seeding cannot make the
        // page adopt. Presenting the scrape here while spending a token minted
        // under the page's visitor is exactly the binding failure that used to
        // discard every token; presenting the effective visitor is the only
        // place the visitor enters the player request.
        val visitor = presentedVisitor(config.visitorData, tokens.visitor)
        // What EVERY winner will be judged by (see
        // ResolvedSource.potlessByColdMint): a token still missing after the
        // re-mint, with minting on and a visitor to bind it to, is a COLD MINT -
        // not a tokenless-by-configuration resolve, which must behave exactly as
        // it did before this spec. All three returns below carry it: the wall is
        // a property of the URL the missing token would have signed, so the
        // shape a winner took cannot decide whether it may mount.
        val tokenlessByColdMint = isColdMintTokenless(
            gvsToken = tokens.gvs,
            visitorData = configVisitor,            mintingEnabled = runCatching { PoTokens.provider().isEnabled() }.getOrDefault(false)
        )



        val progressive = mutableListOf<StreamCandidate>()
        val adaptiveVideo = mutableListOf<StreamCandidate>()
        val adaptiveAudio = mutableListOf<StreamCandidate>()
        val hlsUrls = mutableListOf<Triple<String, Int, String>>()
        var loginRequiredCount = 0

        for (client in YouTubeClients.all) {
            kotlinx.coroutines.yield()
            try {
                val playerResponse = fetchPlayerResponse(
                    apiKey = config.apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = visitor,
                    playerToken = tokens.player
                )

                val harvest = harvestPlayerResponse(client, playerResponse)
                if (harvest.loginRequired) {
                    loginRequiredCount++
                    Log.w(TAG, "Client ${client.key}: LOGIN_REQUIRED (visitor may be stale)")
                    continue
                }
                if (!harvest.playable) {
                    continue
                }

                harvest.hlsUrl?.takeIf { it.isNotBlank() }?.let {
                    hlsUrls += Triple(client.key, client.priority, it)
                }
                progressive += harvest.progressive
                adaptiveVideo += harvest.video
                adaptiveAudio += harvest.audio
            } catch (error: Exception) {
                Log.w(TAG, "Client ${client.key} failed: ${error.message}")
            }
        }

        if (loginRequiredCount == YouTubeClients.all.size) {
            Log.w(TAG, "All clients LOGIN_REQUIRED; invalidating config")
            invalidateConfig()
            return null
        }

        if (hlsUrls.isEmpty() && progressive.isEmpty() && adaptiveVideo.isEmpty() && adaptiveAudio.isEmpty()) {
            return null
        }

        // A resume after a boundary death asks for a source signed by a
        // DIFFERENT client than the ones whose URL classes refused every offset
        // read - a set, because the ladder excludes one class per step. The
        // exclusion is applied at selection, not at harvest: the requests are
        // what minted this visitor's URLs, and skipping one would only shrink
        // the pool for no gain.
        val allowedHls = hlsUrls.filterNot { it.first in excludeClients }
        val allowedProgressive = progressive.filterNot { it.client in excludeClients }
        val allowedVideo = adaptiveVideo.filterNot { it.client in excludeClients }
        val allowedAudio = adaptiveAudio.filterNot { it.client in excludeClients }

        // Prefer HLS first. Media3 handles manifest segments, refreshes and
        // seeking without reopening googlevideo URLs at arbitrary byte offsets.
        // HLS manifests are signed for the client that received them, so the
        // manifest fetch (and its segment fetches) must carry that client's UA.
        val bestHls = pickBestHls(allowedHls, PREFERRED_SEPARATE_CLIENT)
        if (bestHls != null) {
            Log.d(TAG, "Using HLS trailer manifest (client=${bestHls.first})")
            return PlayableSource.Muxed(
                bestHls.third,
                userAgent = userAgentFor(bestHls.first)
            ).withResolveTokens(
                videoId,
                tokens,
                bestHls.first,
                KIND_HLS,
                tokenlessByColdMint
            )
        }

        // Prefer adaptive video + audio (best quality) only when HLS is absent.
        val bestVideo = pickBest(allowedVideo, PREFERRED_SEPARATE_CLIENT, excludeClients)

        val resolvedVideo = bestVideo?.let {
            resolveReachableUrl(it.url, userAgentFor(it.client))
        }

        // Probe more than one audio candidate: googlevideo rejects probes
        // per-CDN-node, so the single best audio URL 403ing does NOT mean the
        // title has no usable audio. Falling back to the video-only stream
        // here used to ship silent trailers (Adaptive video tracks carry no
        // audio) — exactly the "trailers play muted" report.
        var resolvedAudio: String? = null
        if (resolvedVideo != null) {
            val audioCandidates = allowedAudio.sortedWith(
                compareByDescending<StreamCandidate> { it.score }
                    .thenBy { if (it.hasN) 1 else 0 }
                    .thenBy { it.priority }
            )
            var probes = 0
            for (candidate in audioCandidates) {
                if (probes >= MAX_AUDIO_PROBES) break
                probes++
                val probed = resolveReachableUrl(
                    candidate.url,
                    userAgentFor(candidate.client)
                )
                if (probed != null) {
                    resolvedAudio = probed
                    break
                }
            }
            if (resolvedAudio == null) {
                Log.w(
                    TAG,
                    "No adaptive audio URL reachable after $probes probes " +
                        "— will fall back to a muxed stream"
                )
            }
        }

        if (resolvedVideo != null && resolvedAudio != null) {
            val clientUa = bestVideo?.let { userAgentFor(it.client) }
            // Which client signed the winning source is the field check for
            // this identity work: a full-length play on a tv/ios-signed source
            // with android-signed still 403ing at ~36 s says the remaining wall
            // is attestation, not the client table.
            Log.d(TAG, "Using adaptive video+audio (client=${bestVideo?.client})")
            return PlayableSource.Adaptive(resolvedVideo, resolvedAudio, userAgent = clientUa)
                .withResolveTokens(
                    videoId,
                    tokens,
                    bestVideo?.client,
                    KIND_ADAPTIVE,
                    tokenlessByColdMint
                )
        }

        // No verified adaptive audio (or no video at all): fall through to
        // the progressive (combined) stream — genuinely muxed, so it carries
        // audio even when every adaptive audio URL failed its probe.
        if (resolvedVideo != null) {
            Log.w(
                TAG,
                "Adaptive video resolved but audio probe failed; " +
                    "falling through to muxed progressive"
            )
        }
        // The same preferred-client-first shape as the adaptive pick above: the
        // pool is the preferred (android) client's progressives when it has
        // any, and every eligible client's only when it does not. Score ranks
        // WITHIN that pool - it must not be able to put another client's source
        // first, which is how an android_vr first mount died at ~15 s while
        // android's own progressive played the trailer through.
        val bestProgressive = pickBest(allowedProgressive, PREFERRED_SEPARATE_CLIENT, excludeClients)

        val resolvedProgressive = bestProgressive?.let {
            resolveReachableUrl(it.url, userAgentFor(it.client))
        }
        if (resolvedProgressive != null) {
            Log.d(
                TAG,
                "Falling back to muxed progressive stream " +
                    "(audio safety, client=${bestProgressive.client})"
            )
            // The same flag the adaptive return carries: a tokenless
            // progressive is the exact shape that mounted and died at the wall
            // on 2026-11-05 (see ResolvedSource.potlessByColdMint). Without it
            // the launcher's gate lets it through and the death plan becomes
            // the normal path for a cold mint.
            return PlayableSource.Muxed(
                resolvedProgressive,
                userAgent = userAgentFor(bestProgressive.client)
            ).withResolveTokens(
                videoId,
                tokens,
                bestProgressive.client,
                KIND_PROGRESSIVE,
                tokenlessByColdMint
            )
        }

        return null
    }

    /**
     * What one resolve minted; either token may be null (the tokenless path), and
     * [visitor] is the identity the resolve must present - the page's, when the
     * mint was adopted, otherwise null (present the scraped config visitor).
     */
    private data class ResolveTokens(
        val player: String?,
        val gvs: String?,
        val visitor: String? = null
    )

    /**
     * The visitor the player request presents: the token's identity when there is
     * one, the scraped config's otherwise.
     *
     * A pure rule, pinned separately from the resolve because it is the whole
     * point of the binding work: the request must move to the identity YouTube
     * actually minted the tokens under, and fall back to the scrape only when
     * there was no mint to adopt.
     */
    internal fun presentedVisitor(configVisitor: String?, tokenVisitor: String?): String? =
        tokenVisitor?.takeIf { it.isNotBlank() } ?: configVisitor

    /**
     * Mints the resolve's tokens, each inside its own budget (see
     * [GVS_TOKEN_BUDGET_MS] and [TOKEN_BUDGET_MS]).
     *
     * The GVS mint comes first because it is the one that runs the WebView: it
     * loads this video's embed page, and the same session yields the player
     * token, which the provider then serves from cache. Asking for the player
     * token first would only find an empty cache - a player token has no video
     * to load and is never minted on its own.
     *
     * No visitor means nothing to bind a token to (and nothing for the player
     * request to identify itself with), so the resolve goes tokenless.
     */
    private suspend fun mintTokens(videoId: String, visitorData: String?): ResolveTokens {
        if (visitorData.isNullOrBlank()) return ResolveTokens(null, null)
        val provider = PoTokens.provider()
        val gvs = tokenWithinBudget(GVS_TOKEN_BUDGET_MS) {
            provider.gvsToken(videoId, visitorData)
        }
        val player = tokenWithinBudget(TOKEN_BUDGET_MS) {
            provider.playerToken(visitorData)
        }
        // Asked AFTER the mint: the page's identity is only known once the mint
        // has observed it. With minting off (or no adoption) this is the request
        // visitor unchanged, so the request stays byte-identical to pre-spec.
        val visitor = runCatching { provider.effectiveVisitor(visitorData) }.getOrNull()
        return ResolveTokens(player, gvs, visitor)
    }

    /**
     * How long a second GVS mint may take: whatever is left of the extractor's
     * own budget, and never more than a first mint would have had.
     *
     * This is the accounting rule that keeps the re-mint from turning one slow
     * resolve into two full extracts: the first attempt runs inside a
     * [EXTRACTOR_TIMEOUT_MS] `withTimeout`, so a second mint that ignored what
     * the first one spent could push it past that cap, and the timeout would
     * read as "no source" and start the fresh-config retry instead of using the
     * tokens already in hand.
     */
    internal fun remintBudgetMs(
        elapsedMs: Long,
        extractorBudgetMs: Long = EXTRACTOR_TIMEOUT_MS
    ): Long = (extractorBudgetMs - elapsedMs.coerceAtLeast(0L))
        .coerceAtMost(GVS_TOKEN_BUDGET_MS)
        .coerceAtLeast(0L)

    /**
     * Whether a resolve should spend a second mint on the GVS token.
     *
     * All four have to hold:
     *
     * - the first mint produced no GVS token (a token already in hand needs no
     *   second one);
     * - the resolve has a visitor to bind a token to (no visitor is a
     *   deliberate tokenless resolve, not a cold WebView);
     * - minting is enabled (with it off, the resolve is tokenless BY
     *   CONFIGURATION and a second mint cannot help);
     * - enough of the extractor's budget is left for the mint to finish.
     */
    internal fun shouldRemintGvs(
        gvsToken: String?,
        visitorData: String?,
        mintingEnabled: Boolean,
        remainingBudgetMs: Long
    ): Boolean = isColdMintTokenless(gvsToken, visitorData, mintingEnabled) &&
        remainingBudgetMs >= MIN_REMINT_BUDGET_MS

    /**
     * A second, GVS-only mint for a resolve whose first mint came back tokenless.
     *
     * A cold WebView spends its whole [GVS_TOKEN_BUDGET_MS] on the first load and
     * yields nothing - both captures behind this are the same shape
     * (`pot.gvs: state=failed:timeout latency=6008ms`, `pot.player: state=none`) -
     * and the resolve then mounts an UNDECORATED adaptive source that dies at the
     * same spot on every surface (see TrailerPlayerLauncher's tokenless hold).
     * The second mint runs on the session the first one left warm, which is
     * exactly the case it can still save.
     *
     * GVS only, and once: the player token is a byproduct of the same session and
     * is served from the provider's cache once the page has been minted, so a
     * second request for it would only wait. Whatever the second attempt yields
     * is final - no token is an answer too, and the caller hold the source
     * instead of mounting it tokenless.
     */
    private suspend fun remintGvsWhenTokenless(
        videoId: String,
        visitorData: String?,
        tokens: ResolveTokens,
        mintStartedAtMs: Long
    ): ResolveTokens {
        val elapsedMs = (System.nanoTime() - mintStartedAtMs) / 1_000_000L
        val budgetMs = remintBudgetMs(elapsedMs)
        val provider = PoTokens.provider()
        if (!shouldRemintGvs(tokens.gvs, visitorData, provider.isEnabled(), budgetMs)) {
            return tokens
        }
        Log.w(
            TAG,
            "Tokenless resolve for $videoId; re-minting the GVS token once " +
                "(budget=${budgetMs}ms of ${EXTRACTOR_TIMEOUT_MS}ms left)"
        )
        val visitor = visitorData ?: return tokens
        val gvs = tokenWithinBudget(budgetMs) { provider.gvsToken(videoId, visitor) }
            ?: return tokens
        Log.w(TAG, "Re-mint produced a GVS token for $videoId; resolving with it")
        // The page's identity is only known once a mint has observed it, and it
        // is what the player request must present (see presentedVisitor), so it
        // is refreshed here rather than left at the first attempt's answer.
        val effectiveVisitor = runCatching { provider.effectiveVisitor(visitor) }.getOrNull()
        return tokens.copy(
            gvs = gvs,
            visitor = effectiveVisitor ?: tokens.visitor
        )
    }

    /**
     * Loads the PO-token minter's page AHEAD of a resolve (a pre-warm).
     *
     * Called from the moments a trailer is seconds-to-minutes away: a detail
     * screen showing a title with a trailer, the hero arming one, and - deferred
     * - a settled cold launch. The field capture behind this (0.9.23, Moto G) is
     * a GVS mint that spent its whole 6 s budget on a COLD WebView and produced
     * nothing (`pot.gvs: state=failed:timeout latency=6027ms`, `pot.player:
     * state=none`), so the resolve ran tokenless and the chunks hit the mid-play
     * wall. The fix is to move that work off the critical path, not to widen the
     * budget: the ladder stays 5 s script < 6 s GVS budget < 10 s provider.
     *
     * Never throws and never becomes a dependency of a resolve: a pre-warm that
     * fails is a pre-warm that did not happen, and the resolve then mints exactly
     * as it did before (cold path, same budgets). With minting off there is
     * nothing to warm, which is checked first - no fetch, no WebView.
     */
    suspend fun prewarmPoTokens(videoId: String? = null) {
        val provider = PoTokens.provider()
        if (!provider.isEnabled()) return
        withContext(Dispatchers.IO) {
            // The visitor the resolve will present comes from the watch config,
            // so warming without it would mint tokens bound to a visitor this
            // device never sends (a bind mismatch, refused at playback).
            val config = runCatching {
                ensureWatchConfig(videoId ?: WebViewPoTokenMinter.WARM_PAGE_VIDEO_ID)
            }.getOrNull() ?: return@withContext
            val visitor = config.visitorData?.takeIf { it.isNotBlank() }
                ?: return@withContext
            try {
                provider.warmUp(visitor, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                Log.w(TAG, "PO token pre-warm failed (${error.javaClass.simpleName})")
            }
        }
    }

    /** A bounded wait for one token; any failure or timeout means "no token". */
    private suspend fun tokenWithinBudget(
        budgetMs: Long,
        mint: suspend () -> String?
    ): String? =
        try {
            withTimeoutOrNull(budgetMs) { mint() }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            // The token is an enhancement layer: a broken minter must degrade to
            // the pre-spec request, never fail the resolve.
            Log.w(TAG, "PO token unavailable (${error.javaClass.simpleName}); resolving without")
            null
        }

    /**
     * Attaches the GVS token to the source being returned, records what the
     * resolve actually carried for the diagnostics report, and hands back the
     * whole answer.
     *
     * The token pair travels WITH the source because it is only true here: the
     * death line has to name the state a source MOUNTED with, and the resolve
     * line is overwritten by the next resolve (see `PoTokenTrace.recordMount`).
     */
    private fun PlayableSource.withResolveTokens(
        videoId: String,
        tokens: ResolveTokens,
        client: String?,
        kind: String,
        potlessByColdMint: Boolean
    ): ResolvedSource {
        val decorated = withPotToken(tokens.gvs)
        val playerToken = !tokens.player.isNullOrBlank()
        val pot = decorated.carriesPot()
        PoTokenTrace.recordResolve(
            videoId = videoId,
            playerToken = playerToken,
            pot = pot,
            client = client,
            kind = kind
        )
        return ResolvedSource(
            source = decorated,
            client = client,
            potlessByColdMint = potlessByColdMint,
            playerToken = playerToken,
            pot = pot
        )
    }

    /**
     * Pools one client's player response: what its HLS/progressive/adaptive
     * formats contribute, and whether the response was refused outright.
     *
     * The owning [client] is threaded into every candidate. A format is signed
     * for the client whose response carried it, so its UA is what probes and
     * playback must send. The parser used to hand EVERY client's progressive
     * formats to `CLIENTS[0]` (android), which made an iOS- or VR-signed URL
     * get probed and played under the Android UA - the false-403 pattern this
     * package documents elsewhere.
     */
    internal fun harvestPlayerResponse(
        client: YouTubeClient,
        playerResponse: JSONObject
    ): ClientHarvest {
        val status = playerResponse.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK") return ClientHarvest(status = status)

        val streamingData = playerResponse.optJSONObject("streamingData")
            ?: return ClientHarvest(status = status)

        val video = mutableListOf<StreamCandidate>()
        val audio = mutableListOf<StreamCandidate>()
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val format = adaptive.optJSONObject(i) ?: continue
                val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mimeType = format.optString("mimeType").orEmpty()
                if (mimeType.contains("video/")) {
                    video += candidateFromFormat(client, format, url, mimeType, "video")
                } else if (mimeType.startsWith("audio/")) {
                    audio += candidateFromFormat(client, format, url, mimeType, "audio")
                }
            }
        }

        return ClientHarvest(
            status = status,
            hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() },
            progressive = parseFormats(streamingData.optJSONArray("formats"), client),
            video = video,
            audio = audio
        )
    }

    /** Progressive (muxed video+audio) formats, tagged with their own client. */
    internal fun parseFormats(
        formats: JSONArray?,
        client: YouTubeClient
    ): List<StreamCandidate> {
        if (formats == null) return emptyList()
        val out = mutableListOf<StreamCandidate>()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val url = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mimeType = format.optString("mimeType").orEmpty()
            out += candidateFromFormat(client, format, url, mimeType, "video")
        }
        return out
    }

    private fun candidateFromFormat(
        client: YouTubeClient,
        format: JSONObject,
        url: String,
        mimeType: String,
        kind: String
    ): StreamCandidate {
        val height = format.optInt("height", 0).takeIf { it > 0 }
            ?: parseQualityLabel(format.optString("qualityLabel"))
            ?: 0
        val fps = format.optInt("fps", 0)
        val bitrate = format.optDouble("bitrate", format.optDouble("averageBitrate", 0.0))
        // Codec-aware, height-capped score for video: strongly prefer H.264
        // (avc1) at or below the TV ceiling, then lower resolutions, then
        // anything else. Audio keeps the pure-bitrate score.
        val score = if (kind == "audio") {
            bitrate * 1_000_000.0 + format.optDouble("audioSampleRate", 0.0)
        } else {
            val isAvc = mimeType.contains(PREFERRED_VIDEO_MIME, ignoreCase = true)
            val cappedHeight = minOf(height, MAX_TRAILER_HEIGHT)
            if (isAvc && height <= MAX_TRAILER_HEIGHT) {
                // Best tier: hardware-friendly codec within the ceiling.
                4.0e12 + cappedHeight * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
            } else if (height <= MAX_TRAILER_HEIGHT) {
                // Within ceiling but VP9/AV1 - decode may fall back to software.
                2.0e12 + cappedHeight * 1_000_000_000.0 + fps * 1_000_000.0 + bitrate
            } else {
                // Above the ceiling - least desirable for TV playback.
                bitrate
            }
        }
        return StreamCandidate(
            client = client.key,
            priority = client.priority,
            url = url,
            score = score,
            hasN = hasNParam(url),
            height = height,
            ext = if (mimeType.contains("webm")) "webm" else if (kind == "audio") "m4a" else "mp4"
        )
    }

    /** The exclusion as one log token: `none`, or the keys in chain order. */
    private fun excludeDescription(excludeClients: Set<String>): String =
        if (excludeClients.isEmpty()) {
            "none"
        } else {
            YouTubeClients.all.map { it.key }.filter { it in excludeClients }
                .ifEmpty { excludeClients.sorted() }
                .joinToString(",")
        }

    /** UA of the client a candidate URL was signed for (probes must match it). */
    private fun userAgentFor(clientKey: String): String? = YouTubeClients.userAgentFor(clientKey)

    /**
     * The trailer's HLS manifest, a preferred client's whenever one is usable.
     *
     * Within a pool the rule is unchanged: the highest variant at or below the
     * TV ceiling (then the widest), and that pool's lowest variant as the last
     * resort when everything in it exceeds the ceiling. What is new is the order
     * the pools are consulted in - the preferred client's manifests first, then
     * every manifest - so a taller variant from another client can never be the
     * first mount while the preferred client has one at all. A client is
     * demoted, not banned: with no preferred manifest parsed, the whole pool is
     * ranked exactly as before.
     *
     * The ceiling keeps its say across clients: an in-ceiling variant from ANY
     * client still beats an above-ceiling one from the preferred client, because
     * an above-ceiling variant can itself be the reason a TV mount fails.
     */
    private suspend fun pickBestHls(
        hlsUrls: List<Triple<String, Int, String>>,
        preferredClient: String
    ): Triple<String, Int, String>? {
        // Parse each manifest once: the client preference ranks the MANIFESTS,
        // and a manifest that would not parse is not a usable source for its
        // client either.
        val parsed = hlsUrls.mapNotNull { entry ->
            try {
                // Signed for `entry.first`'s client: fetch its manifest with
                // that client's UA (see parseHlsManifest comment).
                entry to parseHlsManifest(entry.third, userAgentFor(entry.first))
            } catch (error: Exception) {
                Log.w(TAG, "HLS manifest parse failed: ${error.message}")
                null
            }
        }.filter { it.second.isNotEmpty() }
        return pickHlsManifest(parsed, preferredClient)
    }

    // ── Watch config (api key + visitor data) ─────────────────────────

    /** What one page's ytcfg yields: the API key and the visitor it minted. */
    internal data class ScrapedConfig(
        val apiKey: String?,
        val visitorData: String?
    )

    /**
     * The ytcfg values of one page, or an empty scrape for a page that did not
     * arrive (null/blank).
     *
     * The same two regexes read the embed page and the watch page: both embed
     * the player config as `"INNERTUBE_API_KEY":"…"` /
     * `"VISITOR_DATA":"…"` pairs in their bootstrap JSON.
     */
    internal fun scrapeConfig(page: String?): ScrapedConfig {
        if (page.isNullOrBlank()) return ScrapedConfig(null, null)
        return ScrapedConfig(
            apiKey = API_KEY_REGEX.find(page)?.groupValues?.getOrNull(1),
            visitorData = VISITOR_DATA_REGEX.find(page)?.groupValues?.getOrNull(1)
        )
    }

    /**
     * Which scrape the config comes from: the embed page's when it yielded a
     * key, otherwise the watch page's (the only source before this change).
     * [watchConfig] is null when the watch page was never needed.
     */
    internal fun mintedConfig(embed: ScrapedConfig, watchConfig: ScrapedConfig?): ScrapedConfig =
        if (!embed.apiKey.isNullOrBlank()) embed else watchConfig ?: embed

    /**
     * The watch config: an InnerTube API key plus the visitor data it was
     * minted with.
     *
     * The visitor comes from THIS video's embed page. It used to be scraped
     * from a hardcoded desktop watch page (`watch?v=dQw4w9WgXcQ`) and then
     * presented to the mobile/TV/VR client contexts, which never minted it - an
     * identity those contexts do not own, in a context built for an unrelated
     * video. The embed page is the embedded client's own page, which is the
     * context this app builds for third-party playback.
     *
     * Falls back to the watch page, exactly as before, when the embed fetch or
     * parse yields no key. TTL, invalidateConfig() and stale-on-failure are
     * unchanged.
     */
    private suspend fun ensureWatchConfig(
        videoId: String,
        forceRefresh: Boolean = false
    ): CachedConfig {
        if (!forceRefresh) {
            cachedConfig.get()?.let {
                if (!isConfigStale(it)) return it
            }
        }
        return configMutex.withLock {
            if (!forceRefresh) {
                cachedConfig.get()?.let {
                    if (!isConfigStale(it)) return@withLock it
                }
            }

            val embedUrl = youtubeEmbedUrl(videoId)
            Log.d(TAG, "Minting watch config from the embed page for $videoId")
            val embedConfig = scrapeConfig(
                // A failed embed fetch is not a failure of the mint: it just
                // means the watch page has to provide the key (below).
                runCatching {
                    performRequest(url = embedUrl, method = "GET", headers = DEFAULT_HEADERS)
                }.getOrNull()?.takeIf { it.ok }?.body
            )

            val watchConfig = if (embedConfig.apiKey.isNullOrBlank()) {
                Log.d(TAG, "Embed page yielded no API key; falling back to the watch page")
                val watchResponse = performRequest(
                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en",
                    method = "GET",
                    headers = DEFAULT_HEADERS
                )
                if (!watchResponse.ok) {
                    val stale = cachedConfig.get()
                    if (stale != null) {
                        Log.w(
                            TAG,
                            "Watch page failed (${watchResponse.status}); using stale config"
                        )
                        return@withLock stale
                    }
                    throw IllegalStateException(
                        "Failed to fetch watch page (${watchResponse.status})"
                    )
                }
                scrapeConfig(watchResponse.body)
            } else {
                null
            }

            // The scraped key is the source of truth; the build-time
            // BuildConfig value is an optional, blank-by-default fallback -
            // never a key baked into the source (see app/build.gradle.kts).
            val scraped = mintedConfig(embedConfig, watchConfig)
            val apiKey = scraped.apiKey ?: com.kennyb1201.kbstream.BuildConfig.INNERTUBE_API_KEY
            val visitorData = scraped.visitorData

            val newConfig = CachedConfig(
                apiKey = apiKey,
                visitorData = visitorData
            )
            cachedConfig.set(newConfig)
            Log.d(TAG, "Watch config cached (visitor=${!visitorData.isNullOrBlank()})")
            newConfig
        }
    }

    private fun isConfigStale(config: CachedConfig): Boolean =
        System.currentTimeMillis() - config.fetchedAt > CONFIG_TTL_MS

    fun invalidateConfig() {
        cachedConfig.set(null)
        // A refused identity refuses the tokens minted FOR it: the player token
        // is bound to the visitor just thrown away, and a stream token for the
        // same video would only be spent on a URL YouTube has already refused.
        // Dropping both here is what keeps a refused token from being spent
        // twice; the next resolve mints for the fresh visitor.
        PoTokens.invalidate()
        // Warning, not debug: this is now the field's evidence that the
        // playback side invalidated the identity (a 403 retry on a TV), and
        // Fire OS suppresses debug-level logs - the same reason the trailer
        // resolution traces above are warnings.
        Log.w(TAG, "Watch config invalidated")
    }

    // ── Player API ────────────────────────────────────────────────────

    /**
     * The InnerTube player request body for one client.
     *
     * The ONLY difference a PO token makes is the added
     * `serviceIntegrityDimensions` object: without a token the payload is
     * byte-identical to the pre-spec one, which is what makes "minting disabled"
     * a no-op rather than a different request.
     */
    internal fun playerRequestPayload(
        videoId: String,
        client: YouTubeClient,
        playerToken: String? = null
    ): JSONObject = JSONObject().apply {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        put(
            "context",
            // Per video: the embedded client declares this video's embed
            // page as its thirdParty context (see YouTubeClient.contextFor).
            client.contextFor(videoId)
        )
        put(
            "playbackContext",
            JSONObject().apply {
                put(
                    "contentPlaybackContext",
                    JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                    }
                )
            }
        )
        if (!playerToken.isNullOrBlank()) {
            put(
                "serviceIntegrityDimensions",
                JSONObject().apply {
                    put("poToken", playerToken)
                }
            )
        }
    }

    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        playerToken: String?
    ): JSONObject {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(apiKey)}"

        val headers = buildMap {
            putAll(DEFAULT_HEADERS)
            put("content-type", "application/json")
            put("origin", "https://www.youtube.com")
            put("x-youtube-client-name", client.id)
            put("x-youtube-client-version", client.version)
            put("user-agent", client.userAgent)
            if (!visitorData.isNullOrBlank()) {
                // The ONLY place the visitor enters the player request (the
                // payload's context carries none - pinned by PoTokenWiringTest),
                // which is what makes "present the token's identity" a
                // single-point change.
                put("x-goog-visitor-id", visitorData)
            }
        }

        val payload = playerRequestPayload(videoId, client, playerToken)

        val response = performRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            body = payload.toString()
        )
        if (!response.ok) {
            throw IllegalStateException("player API ${client.key} failed (${response.status})")
        }
        return runCatching { JSONObject(response.body) }.getOrDefault(JSONObject())
    }

    // ── HLS parsing ───────────────────────────────────────────────────

    /**
     * Every variant a manifest lists, not just its best one.
     *
     * Returning the single highest variant and then applying the ceiling in
     * [pickBestHls] made the ceiling unreachable: the picker was written as
     * "highest at or below MAX_TRAILER_HEIGHT, else lowest", but its input was
     * already each manifest's maximum. A 4K manifest therefore handed back a
     * 2160p trailer even though it also listed a 1080p one - negating an
     * explicit cap on exactly the low-end TV SoCs this path exists for.
     */
    private suspend fun parseHlsManifest(
        manifestUrl: String,
        userAgent: String? = null
    ): List<ManifestVariant> {
        // The manifest must be fetched with the SAME User-Agent of the client
        // it was signed for — googlevideo binds signed HLS manifests to the
        // requesting client's UA, and fetching with the generic Chrome UA
        // 403s on some CDN edges (reported on Fire TV: trailers never played
        // there while other devices were fine). The Chrome default stays
        // only as a fallback for the legacy single-arg call sites.
        val headers = if (userAgent.isNullOrBlank()) {
            DEFAULT_HEADERS
        } else {
            DEFAULT_HEADERS + ("User-Agent" to userAgent)
        }
        val response = performRequest(
            url = manifestUrl,
            method = "GET",
            headers = headers
        )
        if (!response.ok) {
            throw IllegalStateException("Failed to fetch HLS manifest (${response.status})")
        }

        val lines = response.body
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val variants = mutableListOf<ManifestVariant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val next = lines.getOrNull(i + 1) ?: continue
            if (next.startsWith("#")) continue

            val attrs = parseHlsAttrs(line)
            val height = attrs["RESOLUTION"]
                ?.split("x")
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            variants += ManifestVariant(
                url = absolutizeUrl(manifestUrl, next),
                height = height,
                bandwidth = bandwidth
            )
        }
        return variants
    }

    private fun parseHlsAttrs(line: String): Map<String, String> {
        val index = line.indexOf(':')
        if (index == -1) return emptyMap()

        val raw = line.substring(index + 1)
        val out = LinkedHashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var inQuote = false

        for (ch in raw) {
            if (inKey) {
                if (ch == '=') inKey = false else key.append(ch)
                continue
            }
            if (ch == '"') {
                inQuote = !inQuote
                continue
            }
            if (ch == ',' && !inQuote) {
                val k = key.toString().trim()
                if (k.isNotEmpty()) out[k] = value.toString().trim()
                key.clear()
                value.clear()
                inKey = true
                continue
            }
            value.append(ch)
        }
        val lastKey = key.toString().trim()
        if (lastKey.isNotEmpty()) out[lastKey] = value.toString().trim()
        return out
    }

    // ── CDN reachability probe ────────────────────────────────────────

    /**
     * Probes googlevideo CDN nodes and returns the first reachable URL.
     * Handles YouTube's `mn` multi-node parameter and 403-prone node fallback.
     */
    private suspend fun resolveReachableUrl(url: String, userAgent: String? = null): String? {
        if (!url.contains("googlevideo.com")) return url

        // YouTube signs every googlevideo stream URL (sig/n/lsig params) for a
        // specific host. Rewriting the host to a different `mn` node — or even
        // appending a query param like ratebypass — invalidates that signature
        // and googlevideo replies 403 to every request. So probe the EXACT URL
        // we intend to play, byte unchanged, and only use it if it serves bytes.
        // If it 403s, return null so extractInternal falls back to HLS, then
        // the progressive (muxed) stream.
        return if (isUrlReachable(url, userAgent)) url else null
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            // The reachability probe pulls a full 1 MB range (playback-sized),
            // not just a byte — give the read enough room on slow links.
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun isUrlReachable(url: String, userAgent: String? = null): Boolean =
        runCatching {
            // Probe the exact URL unchanged with a playback-sized range.
            // A 1-byte probe (bytes=0-0) passes on URLs googlevideo then 403s
            // for real ranges, so the probe must request a full 1 MB chunk —
            // exactly what playback requests — to prove the URL actually
            // serves data. googlevideo binds signed URLs to the client
            // User-Agent that requested them, so the probe must carry the SAME
            // client UA the URL was signed for - probing an android-signed URL
            // with the android_vr UA (or vice versa) gets a false 403 and a
            // working stream is thrown away.
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-1048575")
                .header("User-Agent", userAgent ?: YouTubeClients.android.userAgent)
                .build()
            probeClient.newCall(request).execute().use { response ->
                response.code == 200 || response.code == 206
            }
        }.getOrDefault(false)

    // ── Helpers ───────────────────────────────────────────────────────

    fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed

        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return runCatching {
            val uri = Uri.parse(normalized)
            val host = uri.host?.lowercase().orEmpty()

            if (host.endsWith("youtu.be")) {
                val id = uri.pathSegments.firstOrNull()
                if (!id.isNullOrBlank() && VIDEO_ID_REGEX.matches(id)) return id
            }

            val queryId = uri.getQueryParameter("v")
            if (!queryId.isNullOrBlank() && VIDEO_ID_REGEX.matches(queryId)) {
                return queryId
            }

            val segments = uri.pathSegments
            if (segments.size >= 2) {
                val first = segments[0]
                val second = segments[1]
                if (
                    (first == "embed" || first == "shorts" || first == "live") &&
                    VIDEO_ID_REGEX.matches(second)
                ) {
                    return second
                }
            }
            null
        }.getOrNull()
    }

    private fun parseQualityLabel(label: String?): Int? {
        if (label.isNullOrBlank()) return null
        return QUALITY_LABEL_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun hasNParam(url: String): Boolean =
        runCatching {
            !Uri.parse(url).getQueryParameter("n").isNullOrBlank()
        }.getOrDefault(false)

    private fun absolutizeUrl(baseUrl: String, maybeRelative: String): String =
        runCatching {
            URL(URL(baseUrl), maybeRelative).toString()
        }.getOrElse { maybeRelative }

    private suspend fun performRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null
    ): RequestResponse {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            if (!name.equals("Accept-Encoding", ignoreCase = true)) {
                requestBuilder.header(name, value)
            }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        when (method.uppercase()) {
            "POST" -> requestBuilder.post((body ?: "").toRequestBody())
            "PUT" -> requestBuilder.put((body ?: "").toRequestBody())
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }

        return httpClient.newCall(requestBuilder.build()).execute().use { response ->
            RequestResponse(
                ok = response.isSuccessful,
                status = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }
}
