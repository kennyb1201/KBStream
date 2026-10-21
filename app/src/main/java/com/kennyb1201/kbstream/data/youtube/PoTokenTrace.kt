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
 * `pot`.
 *
 * Never the token itself. A PO token is a live credential for this device's
 * session and this report is copied to a clipboard and pasted into a chat.
 */
object PoTokenTrace {

    private val contextLines = LinkedHashMap<String, String>()

    private var resolveLine: String? = null

    private var eventLine: String? = null

    /**
     * Records the last state of one context.
     *
     * [state] is [PoTokenState]'s vocabulary (`none | minted | cached |
     * failed:<class>`), [latencyMs] how long the mint took when there was one,
     * and [bind] whether the page's visitor matched the request's visitor.
     */
    @Synchronized
    fun record(
        context: PoTokenContext,
        state: String,
        latencyMs: Long? = null,
        bind: String? = null
    ) {
        val line = StringBuilder("pot.${context.label}: state=$state")
        if (latencyMs != null && latencyMs >= 0L) line.append(" latency=${latencyMs}ms")
        if (bind != null) line.append(" bind=$bind")
        contextLines[context.label] = line.toString()
    }

    /** Whether the last resolve's player request and stream URLs carried tokens. */
    @Synchronized
    fun recordResolve(videoId: String, playerToken: Boolean, pot: Boolean) {
        resolveLine = "pot.resolve: video=$videoId playerToken=$playerToken pot=$pot"
    }

    /** The cache was thrown away because the identity was refused. */
    @Synchronized
    fun recordInvalidated() {
        contextLines.clear()
        resolveLine = null
        eventLine = "pot.cache: cleared"
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
