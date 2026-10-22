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
 * went wrong; how long the mint took; whether the page's visitor matched the
 * one the request carries; and whether the last resolve's URLs actually carried
 * `pot`, signed by which client.
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

    private val contextLines = LinkedHashMap<String, String>()

    private var resolveLine: String? = null

    private var eventLine: String? = null

    /**
     * Records the last state of one context.
     *
     * [state] is [PoTokenState]'s vocabulary (`none | minted | cached |
     * failed:<class>`), [latencyMs] how long the mint took when there was one,
     * [bind] whether the page's visitor matched the request's visitor, and
     * [spent] whether the token was handed to the resolve - false is the
     * mismatch case, where a token WAS minted and deliberately withheld.
     */
    @Synchronized
    fun record(
        context: PoTokenContext,
        state: String,
        latencyMs: Long? = null,
        bind: String? = null,
        spent: Boolean? = null
    ) {
        val line = StringBuilder("pot.${context.label}: state=$state")
        if (latencyMs != null && latencyMs >= 0L) line.append(" latency=${latencyMs}ms")
        if (bind != null) line.append(" bind=$bind")
        // Only a withheld token says so. "state=minted bind=mismatch" alone
        // leaves the next question - did we spend it anyway? - unanswered.
        if (spent == false) line.append(" spent=no")
        contextLines[context.label] = line.toString()
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

    /** Oldest-first, stable order: the two contexts, the resolve, then events. */
    @Synchronized
    fun lines(): List<String> = buildList {
        PoTokenContext.values().forEach { context ->
            contextLines[context.label]?.let { add(it) }
        }
        resolveLine?.let { add(it) }
        eventLine?.let { add(it) }
    }

    /** Test seam. */
    @Synchronized
    fun clear() {
        contextLines.clear()
        resolveLine = null
        eventLine = null
    }
}
