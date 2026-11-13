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

    // ── Needs reconnect (the lost/refused session) ────────────────────
    //
    // Its own flag rather than a second reading of the connected one, because a
    // report has to be able to tell "never connected" from "was connected, the
    // credential is gone, and the viewer must sign in again" - the state that
    // used to read `connected=yes needs_reapproval=no` over an empty Library
    // (field report, build 253).

    @Volatile
    private var needsReconnect: Boolean = false

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

    // ── Public lists (step 6) ─────────────────────────────────────────
    //
    // Counted apart from the list writes for the reason those are counted
    // apart from the history writes: discovery runs on `search:read` and the
    // import on `lists:write`, so "browsing is broken" and "importing is
    // broken" are different reports. The import's detail names the list and its
    // mode - never a token, an id from the account's own sessions, or a title.

    @Volatile
    private var publicListReads: Int = 0

    @Volatile
    private var lastPublicListError: String = NONE

    @Volatile
    private var importsAttempted: Int = 0

    @Volatile
    private var importsSucceeded: Int = 0

    @Volatile
    private var lastImportError: String = NONE

    @Volatile
    private var lastImportDetail: String = NONE

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

    /** A public-list discovery read that reached the network. */
    fun recordPublicListRead() {
        publicListReads += 1
    }

    /**
     * A public-list read that did not answer.
     *
     * @param kind one of `401`, `429`, `403-scope`, `network`, `no-token`,
     * `http:<code>` - the same vocabulary every other BingeCat leg uses
     */
    fun recordPublicListError(kind: String) {
        lastPublicListError = kind
    }

    /** An import that reached the network (or should have). */
    fun recordImportAttempt() {
        importsAttempted += 1
    }

    /** An import BingeCat answered about, with the list and mode it names. */
    fun recordImportSuccess(detail: String) {
        importsSucceeded += 1
        lastImportError = NONE
        lastImportDetail = detail.ifBlank { NONE }
    }

    /**
     * An import that did not land.
     *
     * @param kind one of `401`, `429`, `403-scope`, `409-import-running`,
     * `network`, `no-token`, `http:<code>[:<error code>]`, or a failure class name
     */
    fun recordImportFailure(kind: String) {
        lastImportError = kind
    }

    /** The needs-reapproval state a step-1 grant (or a 403) puts the tracker in. */
    fun recordNeedsReapproval(needed: Boolean) {
        needsReapproval = needed
    }

    /**
     * The needs-reconnect state a missing or refused credential puts the tracker
     * in. Never true for a profile that simply never connected.
     */
    fun recordNeedsReconnect(needed: Boolean) {
        needsReconnect = needed
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
                "needs_reapproval=${if (needsReapproval) "yes" else "no"} " +
                "needs_reconnect=${if (needsReconnect) "yes" else "no"} " +
                "public_list_reads=$publicListReads " +
                "last_public_list_error=$lastPublicListError " +
                "imports=$importsAttempted/$importsSucceeded " +
                "last_import_error=$lastImportError last_import=$lastImportDetail"
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
        needsReconnect = false
        publicListReads = 0
        lastPublicListError = NONE
        importsAttempted = 0
        importsSucceeded = 0
        lastImportError = NONE
        lastImportDetail = NONE
    }
}
