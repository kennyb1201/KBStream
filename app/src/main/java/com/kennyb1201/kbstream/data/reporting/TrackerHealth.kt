package com.kennyb1201.kbstream.data.reporting

/**
 * The last outcome of each outbound WRITE leg, for the diagnostics report.
 *
 * A capture used to say what was queued (`pendingOutboxSummary()`: `table:key`
 * pairs) and never whether the last attempt to write anywhere had failed - so
 * "my watch state stopped reaching Simkl" travelled as a list of keys with no
 * reason attached, and the one fact that explains it (the last scrobble or
 * completion push was refused) was in logcat, which a TV cannot show (spec C11).
 *
 * What is recorded is deliberately tiny: the kind of write (`scrobble`,
 * `completion`, `history`), the tracker (`simkl`, `mdblist`, `trakt`, `local`),
 * whether it succeeded, and a short reason when it did not. No ids, no URLs, no
 * tokens - a diagnostics capture is shared.
 *
 * Process-wide and bounded: one entry per (kind, tracker) pair, so the table is
 * at most a dozen lines however long the session runs.
 */
object TrackerHealth {

    private data class Key(val kind: String, val tracker: String)

    private data class Outcome(
        val ok: Boolean,
        val detail: String?,
        val atMs: Long
    )

    private val entries = LinkedHashMap<Key, Outcome>()
    private val lock = Any()

    /**
     * Records one leg's outcome. [detail] must be a short, non-sensitive reason
     * ("HTTP 429", "no tmdb id") - never an id, a URL or a token.
     */
    fun note(
        kind: String,
        tracker: String,
        ok: Boolean,
        detail: String? = null
    ) {
        if (kind.isBlank() || tracker.isBlank()) return
        synchronized(lock) {
            entries[Key(kind, tracker.lowercase())] = Outcome(
                ok = ok,
                detail = detail?.takeIf { it.isNotBlank() }?.take(80),
                atMs = System.currentTimeMillis()
            )
        }
    }

    /** One line per leg, oldest kind first, for `Diagnostics.build()`. */
    fun lines(): List<String> = synchronized(lock) {
        if (entries.isEmpty()) return emptyList()
        entries.entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.tracker }))
            .map { (key, outcome) ->
                val word = if (outcome.ok) "ok" else "FAILED"
                buildString {
                    append("trackers: ${key.kind} · ${key.tracker} · $word")
                    outcome.detail?.let { append(" · $it") }
                }
            }
    }
}
