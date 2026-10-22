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
        client: String? = null
    ) {
        resolveLine = buildString {
            append("pot.resolve: video=$videoId playerToken=$playerToken pot=$pot")
            if (!client.isNullOrBlank()) append(" client=$client")
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
    }

    /** Test seam. */
    @Synchronized
    fun clear() {
        contextLines.clear()
        resolveLine = null
        warmLine = null
        eventLine = null
    }
}
