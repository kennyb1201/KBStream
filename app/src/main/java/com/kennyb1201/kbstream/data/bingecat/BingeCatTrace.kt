package com.kennyb1201.kbstream.data.bingecat

/**
 * The one BingeCat line the diagnostics report carries.
 *
 * Both halves of v1 fail soft - a 429 leaves the Library alone, a missing
 * mapping hides the section, a rejected token disconnects - so a bug report has
 * no other way to tell "never connected" from "connected, unmapped" from
 * "mapped, last refresh 429". Same family as [com.kennyb1201.kbstream.data.debrid.TorBoxTrace]:
 * an in-memory record written by the repository and read by
 * [com.kennyb1201.kbstream.data.reporting.Diagnostics].
 *
 * Tokens, device codes and the client id never appear here.
 */
object BingeCatTrace {

    private const val NONE = "none"

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var mappedProfile: String? = null

    @Volatile
    private var lastRefreshAtMs: Long = 0L

    @Volatile
    private var lastError: String = NONE

    @Volatile
    private var counts: String = "none"

    // ── Writes (step 2) ───────────────────────────────────────────────
    //
    // A tracker's failures are all soft, so the only evidence a write leg
    // exists - and which way it failed - is this line: counting attempts apart
    // from successes is what separates "nothing is connected" from "every
    // write is 403-scoped", and the flag is the state the connect screen
    // prompts for.

    @Volatile
    private var writesAttempted: Int = 0

    @Volatile
    private var writesSucceeded: Int = 0

    @Volatile
    private var lastWriteError: String = NONE

    @Volatile
    private var lastWriteDetail: String = NONE

    @Volatile
    private var needsReapproval: Boolean = false

    // ── List writes (step 3) ──────────────────────────────────────────
    //
    // Counted apart from the history writes for the same reason those are
    // counted apart from the reads: the two legs hold different scopes, so
    // "saving is broken" and "scrobbling is broken" are different reports and a
    // shared counter would merge them into one number that explains neither.

    @Volatile
    private var listWritesAttempted: Int = 0

    @Volatile
    private var listWritesSucceeded: Int = 0

    @Volatile
    private var lastListWriteError: String = NONE

    @Volatile
    private var lastListWriteDetail: String = NONE

    fun recordSession(connected: Boolean) {
        this.connected = connected
    }

    fun recordMapping(profileId: Long?, name: String?) {
        mappedProfile = profileId?.let { id -> name?.takeIf { it.isNotBlank() }?.let { "$id ($it)" } ?: id.toString() }
    }

    fun recordRefresh(
        watchlist: Int,
        saved: Int,
        lists: Int,
        watched: Int,
        atMs: Long = System.currentTimeMillis()
    ) {
        counts = "watchlist $watchlist, saved $saved, lists $lists, watched $watched"
        lastRefreshAtMs = atMs
        lastError = NONE
    }

    /**
     * @param kind one of `401`, `429`, `network`, `resolve-miss`, `invalid-scope`
     */
    fun recordError(kind: String) {
        lastError = kind
    }

    /** A write that reached the network (or should have). */
    fun recordWriteAttempt() {
        writesAttempted += 1
    }

    /** A committed batch, with the contract's own committed/unresolved counts. */
    fun recordWriteSuccess(detail: String) {
        writesSucceeded += 1
        lastWriteError = NONE
        lastWriteDetail = detail.ifBlank { NONE }
    }

    /**
     * A write that did not commit.
     *
     * @param kind one of `401`, `429`, `403-scope`, `network`, `no-token`,
     * `http:<code>`, or a failure class name
     */
    fun recordWriteFailure(kind: String) {
        lastWriteError = kind
    }

    /** A list write that reached the network (or should have). */
    fun recordListWriteAttempt() {
        listWritesAttempted += 1
    }

    /** A committed list write, with the server's own counts and refusals. */
    fun recordListWriteSuccess(detail: String) {
        listWritesSucceeded += 1
        lastListWriteError = NONE
        lastListWriteDetail = detail.ifBlank { NONE }
    }

    /**
     * A list write that did not commit.
     *
     * @param kind one of `401`, `429`, `403-scope`, `network`, `no-token`,
     * `http:<code>`, or a failure class name - the same vocabulary the history
     * writes use, so a report can be read the same way for either leg
     */
    fun recordListWriteFailure(kind: String) {
        lastListWriteError = kind
    }

    /** The needs-reapproval state a step-1 grant (or a 403) puts the tracker in. */
    fun recordNeedsReapproval(needed: Boolean) {
        needsReapproval = needed
    }

    fun lines(): List<String> {
        val refreshed = if (lastRefreshAtMs > 0L) {
            "${(System.currentTimeMillis() - lastRefreshAtMs) / 60_000L} min ago"
        } else {
            "never"
        }
        return listOf(
            "connected=${if (connected) "yes" else "no"} " +
                "mapped=${mappedProfile ?: "unmapped"} " +
                "last_refresh=$refreshed last_error=$lastError cached=$counts" +
                " writes=$writesAttempted/$writesSucceeded " +
                "last_write_error=$lastWriteError " +
                "last_write=$lastWriteDetail " +
                "list_writes=$listWritesAttempted/$listWritesSucceeded " +
                "last_list_write_error=$lastListWriteError " +
                "last_list_write=$lastListWriteDetail " +
                "needs_reapproval=${if (needsReapproval) "yes" else "no"}"
        )
    }

    /** Test hook: the record is process-wide and must not leak between cases. */
    internal fun reset() {
        connected = false
        mappedProfile = null
        lastRefreshAtMs = 0L
        lastError = NONE
        counts = "none"
        writesAttempted = 0
        writesSucceeded = 0
        lastWriteError = NONE
        lastWriteDetail = NONE
        listWritesAttempted = 0
        listWritesSucceeded = 0
        lastListWriteError = NONE
        lastListWriteDetail = NONE
        needsReapproval = false
    }
}
