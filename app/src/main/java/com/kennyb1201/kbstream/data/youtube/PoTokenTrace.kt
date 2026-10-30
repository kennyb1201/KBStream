package com.kennyb1201.kbstream.data.youtube

/**
 * The state of the trailer pipeline's PO-token layer, for the in-app
 * Diagnostics report.
 *
 * This component carries maintenance in a way the rest of the trailer stack
 * does not: BotGuard changes without notice, and token binding rules have
 * shifted before. The mitigation is structural - failure degrades to today's
 * behavior - but a silent upstream break must be visible in ONE field capture
 * instead of a user report. So the report says, per context, whether the last
 * mint produced a token, whether it came from the cache, or the class of what
 * went wrong; how long the mint took; how the page's visitor relates to the one
 * the request was scraped with (`match`, `adopted` - the usual case - or
 * `unknown`); and whether the last resolve's URLs actually carried `pot`, signed
 * by which client.
 *
 * The lines OUTLIVE the refusal that drops the tokens. Clearing them there - as
 * this did at first - erased the evidence at the exact moment it mattered: the
 * field capture showed one `pot.cache: cleared` line and nothing about how the
 * tokens that were just refused had been obtained, or whether they had been
 * spent at all. The refusal therefore stamps the resolve line
 * (`invalidatedAfter=true`) and leaves every recorded fact in place.
 *
 * Never the token itself. A PO token is a live credential for this device's
 * session and this report is copied to a clipboard and pasted into a chat.
 */
object PoTokenTrace {

    /** Appended to the resolve line by [recordInvalidated], once. */
    internal const val INVALIDATED_FLAG = "invalidatedAfter=true"

    /** The event line a refusal leaves behind. */
    internal const val CLEARED_EVENT = "pot.cache: cleared"

    /** [recordWarm]'s vocabulary: no page, a page loading, a page holding tokens. */
    internal const val WARM_COLD = "cold"
    internal const val WARM_WARMING = "warming"
    internal const val WARM_READY = "ready"

    private val contextLines = LinkedHashMap<String, String>()

    private var resolveLine: String? = null

    /**
     * The pre-warm's state, always printable: an unset line reads `state=cold`,
     * which is a fact too (this box never warmed).
     */
    private var warmLine: String? = null

    private var eventLine: String? = null

    /**
     * The one line a trailer boundary death leaves behind (see
     * [recordTrailerDeath]). Null on a session no trailer died in, so an
     * ordinary capture stays exactly as short as it was.
     */
    private var deathLine: String? = null

    /** The signing client of the last resolve, for the resume path. */
    private var lastResolveClient: String? = null

    /** The source shape the last resolve returned: hls | adaptive | progressive. */
    private var lastResolveKind: String? = null

    /**
     * The chunk range a googlevideo read died in, when one did (see
     * [recordChunkDeath]). Held as its own fact rather than folded into
     * [deathLine] because the hero writes that line, and it writes it a moment
     * after the throw - the offset has to survive that gap.
     */
    private var chunkDeath: ChunkDeath? = null

    /**
     * A failed chunk read, in the terms a capture needs to act on it.
     *
     * [videoId] is read from the signed URL's own `id` parameter, so a
     * full-player failure for one clip can never be quoted as another's; it is
     * null when the URL carried none. [totalBytes] is the length the chunker
     * believed at that moment (null when the server never said), which is what
     * turns the offset into a media-time skip on the resume side.
     */
    data class ChunkDeath(
        val videoId: String?,
        val offsetBytes: Long,
        val totalBytes: Long?,
        val reason: String
    )

    /**
     * Records the last state of one context.
     *
     * [state] is [PoTokenState]'s vocabulary (`none | minted | cached |
     * failed:<class>`), [latencyMs] how long the mint took when there was one,
     * [bind] how the page's visitor relates to the request's (`match | adopted |
     * unknown`), and [spent] whether the token was handed to the resolve. Null
     * when it is not the question being answered (a pre-warm caches, it does not
     * spend).
     */
    @Synchronized
    fun record(
        context: PoTokenContext,
        state: String,
        latencyMs: Long? = null,
        bind: String? = null,
        spent: Boolean? = null,
        source: String? = null
    ) {
        val line = StringBuilder("pot.${context.label}: state=$state")
        if (latencyMs != null && latencyMs >= 0L) line.append(" latency=${latencyMs}ms")
        if (bind != null) line.append(" bind=$bind")
        // Stated both ways on purpose: "state=minted bind=adopted" alone leaves
        // the next question - was it actually used for the request? - unanswered,
        // and a capture must be able to say "spent under an adopted identity"
        // without inference.
        if (spent != null) line.append(" spent=${if (spent) "yes" else "no"}")
        // Where the token came from: the pre-warmed page or a mint the resolve
        // ran itself. The next capture has to be able to say "the resolve spent a
        // warm token" without inferring it from a latency.
        if (source != null) line.append(" source=$source")
        contextLines[context.label] = line.toString()
    }

    /**
     * Records where the pre-warm stands.
     *
     * [playerToken] is whether a cached player-context token is in hand at this
     * moment - the one half of the warm state that outlives the page, and the
     * half a resolve can spend with no mint at all. Null when it is not the
     * question being answered (the `warming` state).
     */
    @Synchronized
    fun recordWarm(state: String, playerToken: Boolean? = null) {
        warmLine = buildString {
            append("pot.warm: state=$state")
            if (playerToken != null) append(" playerToken=${if (playerToken) "yes" else "no"}")
        }
    }

    /**
     * Whether the last resolve's player request and stream URLs carried tokens.
     *
     * [client] is the signing client key of the returned source: the client
     * whose UA signs the URL that died is the first thing a field capture has
     * to name, because a dead stream on a tv/ios-signed source and a dead one
     * on an android-signed source are different problems.
     */
    @Synchronized
    fun recordResolve(
        videoId: String,
        playerToken: Boolean,
        pot: Boolean,
        client: String? = null,
        /** The shape the resolve returned: `hls`, `adaptive` or `progressive`. */
        kind: String? = null
    ) {
        lastResolveClient = client?.takeIf { it.isNotBlank() }
        lastResolveKind = kind?.takeIf { it.isNotBlank() }
        resolveLine = buildString {
            append("pot.resolve: video=$videoId playerToken=$playerToken pot=$pot")
            if (!client.isNullOrBlank()) append(" client=$client")
            if (!kind.isNullOrBlank()) append(" kind=$kind")
        }
    }

    /**
     * The client that signed the most recent resolve's URLs, or null.
     *
     * Read by a trailer that died at its first chunk boundary: the resume has
     * to exclude the client whose URL class refused every offset read, and this
     * is the only record of which one that was.
     */
    @Synchronized
    fun resolveClient(): String? = lastResolveClient

    /** The most recent resolve's source shape (`hls`/`adaptive`/`progressive`). */
    @Synchronized
    fun resolveKind(): String? = lastResolveKind

    /**
     * Records the chunk range a googlevideo read or open died in.
     *
     * Called from the chunked data source itself, at the moment it stops
     * retrying - the one place the byte offset, the stream length and the
     * reason still exist. Observation only: nothing in the retry, chunk-size or
     * backoff logic reads this back, so VOD playback is unchanged. The line it
     * feeds is [recordTrailerDeath]'s, not its own: an offset with no death
     * around it is not a fact a capture can use.
     */
    @Synchronized
    fun recordChunkDeath(
        videoId: String?,
        offsetBytes: Long,
        totalBytes: Long?,
        reason: String
    ) {
        chunkDeath = ChunkDeath(
            videoId = videoId?.takeIf { it.isNotBlank() },
            offsetBytes = offsetBytes.coerceAtLeast(0L),
            totalBytes = totalBytes?.takeIf { it > 0L },
            reason = reason
        )
    }

    /**
     * The recorded chunk death, when it belongs to [videoId].
     *
     * Equality, not "the most recent one": a trailer that dies after the full
     * player failed on a different clip must not inherit that clip's offset.
     * Null when either side is unknown - a death with no chunk record is said
     * to have none rather than attributed to a guess.
     */
    @Synchronized
    fun chunkDeathFor(videoId: String?): ChunkDeath? {
        val recorded = chunkDeath ?: return null
        val wanted = videoId?.takeIf { it.isNotBlank() } ?: return null
        return recorded.takeIf { it.videoId == wanted }
    }

    /**
     * Records a trailer that died mid-play.
     *
     * The field case this exists for (2026-10-22: every trailer, every device,
     * at the first boundary past the initially-probed bytes) left NO line
     * anywhere in the report: the death is a soft one, the identity classifier
     * fires, and the session ends without a card. One capture had to be able to
     * say which client's URL class died, how far it got, and whether the
     * re-resolve with a different client recovered it.
     *
     * [resume] is the report's own CLOSED vocabulary - these values and no
     * others, because a capture has to be readable without knowing the code:
     *
     *  - `pending` - a replacement resolve was requested and no outcome is known
     *    yet. The only value that may describe an in-flight resume; a bare
     *    `none` must never stand in for it (it read exactly like "no resume was
     *    ever asked for", which is how the 2026-10-28 capture lost the thread).
     *  - `client <key> ok` - the replacement resolved and mounted.
     *  - `client <key> died` - a later identity-classified death, after which the
     *    video is blocked for the session (the ladder is exhausted).
     *  - `none: no other client` - the replacement resolve COMPLETED with no
     *    source from any remaining client.
     *  - `cancelled` - the requested resolve was cancelled (effect restart, scope
     *    cancellation, activity finishing) before its outcome was known. The
     *    request is still owed, never lost.
     *  - `blocked` - the resolve early-returned because the video was already
     *    blocked while a resume was owed. A bug state; its presence is a report.
     *  - `replacement ended` / `replacement failed` - the mounted replacement
     *    died in a shape the identity classifier does not catch (silent
     *    `STATE_ENDED`, the rebuffer watchdog, any non-identity error), so the
     *    death line names the resume instead of leaving the request's own line.
     *
     * Never a URL and never a token.
     *
     * The 2026-10-30 report (Detail hero, "dies at ~20 s on both attempts, same
     * spot") arrived with ZERO trailer lines in either paste, and the reason was
     * the surface, not the trace: the phone's hero never wrote one. It does now,
     * and the line carries what settles that report on its own - where it died,
     * which chunk range, which format, and which retry it was:
     *
     *  - [itag]/[chunkOffsetBytes]/[chunkTotalBytes]/[attempt] render only when
     *    the caller knows them. A capture must not read an invented itag as a
     *    fact, and the surfaces that predate this (the full player's trailer
     *    session) keep the line they always wrote.
     *  - [attempt] is 0 for the first play and 1.. for a retry; [change] says
     *    what that retry changed - `first`, `same` for an identical re-sign, or
     *    `class:<key>` when it re-resolved excluding the class that died.
     *
     * One more `resume` value joins the closed vocabulary above:
     * `none: retries spent` - the ladder had no untried class left and the one
     * identical replay was already spent, so no replacement was requested at
     * all. It is the line that says "this is as far as the retry rules go".
     */
    @Synchronized
    fun recordTrailerDeath(
        videoId: String,
        client: String?,
        kind: String?,
        positionMs: Long,
        durationMs: Long,
        resume: String,
        /** The `itag` of the format that died, when its URL named one. */
        itag: String? = null,
        /** Which attempt this death belongs to: 0 is the first play. */
        attempt: Int? = null,
        /** What the retry that produced this attempt changed (see above). */
        change: String? = null,
        /** The chunk byte offset the read died at, when the chunker recorded one. */
        chunkOffsetBytes: Long? = null,
        /** The stream's length in bytes at that moment, or null when unknown. */
        chunkTotalBytes: Long? = null
    ) {
        val who = client?.takeIf { it.isNotBlank() } ?: "unknown"
        val shape = kind?.takeIf { it.isNotBlank() } ?: "unknown"
        val length = if (durationMs > 0L) durationMs.toString() else "?"
        deathLine = buildString {
            append("trailer.death: video=$videoId client=$who kind=$shape")
            itag?.takeIf { it.isNotBlank() }?.let { append(" itag=$it") }
            append(" at=${positionMs.coerceAtLeast(0L)}/$length")
            if (chunkOffsetBytes != null) {
                append(" chunk=${chunkOffsetBytes.coerceAtLeast(0L)}")
                append("/${chunkTotalBytes?.takeIf { it > 0L } ?: "?"}")
            }
            if (attempt != null) {
                append(" retry=${attempt.coerceAtLeast(0)}")
                change?.takeIf { it.isNotBlank() }?.let { append("/$it") }
            }
            append(" resume=$resume")
        }
    }

    /**
     * The cache was thrown away because the identity was refused.
     *
     * Keeps [contextLines] and [resolveLine] exactly as they were - a refusal is
     * usually the ONLY interesting event in a capture, and it is the one that
     * used to delete everything worth reading - and stamps the resolve so the
     * order ("this resolve ran, then the identity was refused") is legible.
     */
    @Synchronized
    fun recordInvalidated() {
        resolveLine = resolveLine?.let { line ->
            if (line.contains(INVALIDATED_FLAG)) line else "$line $INVALIDATED_FLAG"
        }
        eventLine = CLEARED_EVENT
    }

    /**
     * Oldest-first, stable order: the two contexts, the resolve, the pre-warm,
     * then events.
     *
     * The pre-warm line is printed even when it was never recorded, as
     * `state=cold`: a capture from a device that never warmed has to be able to
     * say so rather than leave the reader to notice the absence.
     */
    @Synchronized
    fun lines(): List<String> = buildList {
        PoTokenContext.values().forEach { context ->
            contextLines[context.label]?.let { add(it) }
        }
        resolveLine?.let { add(it) }
        add(warmLine ?: "pot.warm: state=$WARM_COLD")
        eventLine?.let { add(it) }
        deathLine?.let { add(it) }
    }

    /** Test seam. */
    @Synchronized
    fun clear() {
        contextLines.clear()
        resolveLine = null
        warmLine = null
        eventLine = null
        deathLine = null
        chunkDeath = null
        lastResolveClient = null
        lastResolveKind = null
    }
}
