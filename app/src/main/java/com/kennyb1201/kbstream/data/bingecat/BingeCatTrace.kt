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

    fun lines(): List<String> {
        val refreshed = if (lastRefreshAtMs > 0L) {
            "${(System.currentTimeMillis() - lastRefreshAtMs) / 60_000L} min ago"
        } else {
            "never"
        }
        return listOf(
            "connected=${if (connected) "yes" else "no"} " +
                "mapped=${mappedProfile ?: "unmapped"} " +
                "last_refresh=$refreshed last_error=$lastError cached=$counts"
        )
    }

    /** Test hook: the record is process-wide and must not leak between cases. */
    internal fun reset() {
        connected = false
        mappedProfile = null
        lastRefreshAtMs = 0L
        lastError = NONE
        counts = "none"
    }
}
