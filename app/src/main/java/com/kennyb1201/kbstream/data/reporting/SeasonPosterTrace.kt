package com.kennyb1201.kbstream.data.reporting

import com.kennyb1201.kbstream.data.art.SeasonPosterAnswer
import com.kennyb1201.kbstream.data.art.SeasonPosterMemory

/**
 * The Detail page's season poster, run by run (spec 2026-11-14, F1).
 *
 * The report was "it shows, then vanishes when I scroll back, and stays gone",
 * and the code could not say which half failed: the URL being cleared by a
 * re-lookup that answered nothing, or a healthy URL whose image simply did not
 * redraw when its lazy item came back. Every effect run and every image outcome
 * lands here, so one paste says which one it was.
 *
 * Shape only, the same rule as the OAuth arrival tracing: which key re-ran the
 * effect, what each source ANSWERED (found / none / no-key / unanswered), what
 * the state did (set / kept / cleared), and the image's outcome. Never a URL -
 * a poster URL is not secret, but a paste is public and nothing here needs it.
 */
internal object SeasonPosterTrace {

    private const val MAX_LINES = 10

    private val entries = ArrayDeque<String>()
    private val lock = Any()

    fun record(line: String) {
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_LINES) entries.removeFirst()
        }
    }

    fun lines(): List<String> = synchronized(lock) { entries.toList() }

    /** Drops the ring (diagnostics' own clear, and tests). */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * One effect run: which key changed and to what, what was resolved, and what
     * the state did with it. [trigger] is "open", "type", "show", "season" or
     * "rerun" - the last one being exactly the case the field report could not
     * rule out.
     */
    fun runLine(
        trigger: String,
        season: Int?,
        answer: SeasonPosterAnswer?,
        decision: SeasonPosterMemory.Decision
    ): String = buildString {
        append("season poster run (").append(trigger)
        append(", season=").append(season?.toString() ?: "none").append("): ")
        if (answer == null) {
            append("nothing looked up")
        } else {
            append("tvdb=").append(if (answer.tvdbId == null) "none" else "set")
            append(" fanart=").append(answer.fanart.token)
            append(" tmdb=").append(answer.tmdb.token)
        }
        append(" -> ").append(SeasonPosterMemory.describe(decision))
    }

    /**
     * The header poster's Coil outcome, recorded on draw. `memory`, `disk` or
     * `network` on success (its result's data source), `error` when the request
     * failed - the half that tells "the URL was cleared" from "the image did not
     * come back".
     */
    fun imageLine(surface: String, outcome: String): String =
        "season poster image ($surface): $outcome"
}
