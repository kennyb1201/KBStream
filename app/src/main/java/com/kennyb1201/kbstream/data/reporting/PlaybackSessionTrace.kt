package com.kennyb1201.kbstream.data.reporting

/**
 * The episode identity each playback session ran under, and the handoffs from
 * one session to the next — for the diagnostics report.
 *
 * Why: "the binge offered me an episode I had already watched, and the ones it
 * played were never marked" is a bookkeeping symptom that is invisible from
 * outside the app. Every one of those facts — the watch-history row, the
 * watched marker, the Simkl scrobble, and the arithmetic next episode — is
 * derived from the session's own `season`/`episode` fields, while the stream
 * itself was resolved for the id the handoff carried. Nothing ever compares the
 * two, so a session that plays one episode and files it as another looks
 * perfectly normal from inside, and the only visible trace is exactly what was
 * reported: a next episode that has already been watched, and finished episodes
 * with no marker.
 *
 * One line per session, with the id's own numbers next to the fields, says
 * which of the two is wrong; a chain of them across a binge says where they
 * stopped agreeing.
 *
 * Written by both players (see `PlaybackHistoryIds.playbackSessionLine`), read
 * by [Diagnostics]. Plain strings, so nothing here can fail a report.
 */
internal object PlaybackSessionTrace {

    /**
     * Lines kept: a binge is a handful of sessions, and this is a ring
     * (audit S-I11, 2026-10-27).
     *
     * 24, not 12. The ring is shared with the per-session event notes the
     * players add to it (a source switch, a tracker skip, a writer refusal, an
     * exit save), so a three-episode binge could write more than a dozen lines
     * and evict episode 1 - the exact chain the trace exists to show. The cap is
     * only ever a safety net against unbounded growth; a report is a few
     * hundred lines anyway.
     */
    private const val MAX_LINES = 24

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Appends one line, dropping the oldest past [MAX_LINES]. */
    fun note(line: String) {
        synchronized(lock) { lines = (lines + line).takeLast(MAX_LINES) }
    }

    /** The recent lines, oldest first. Empty until a session has started. */
    fun lines(): List<String> = lines
}
