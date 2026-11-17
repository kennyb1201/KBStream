package com.kennyb1201.kbstream.data.reporting

/**
 * What the Detail page's season-row reveal decided, for COPY DIAGNOSTICS.
 *
 * The 2026-11-17 report - the focused episode row cut while the poster row was
 * whole - had to be diagnosed from source, because the paste carried no reveal
 * line at all. Which branch fired (both rows fitted, the focused row won a
 * don't-fit page, a settled page needed nothing, or a row was not on screen to
 * be measured) is the first thing the next report needs to answer, so each
 * decision is kept here as one short line.
 *
 * A bounded ring, last-wins-oldest-out: a D-pad walk produces a handful of
 * decisions per row, and a paste only needs the recent ones. Every line is
 * keyed by the neighbour row's LIST KEY (the constant names in `DetailScreen`)
 * - never a title, an id or anything about what the viewer was watching.
 */
object SeasonRowRevealTrace {

    /** Decisions kept at once: enough for a walk down the page and back. */
    private const val MAX_LINES = 24

    private val entries = ArrayDeque<String>()

    /**
     * Records one decision. [branch] is one of the `SEASON_ROW_REVEAL_*` names
     * and [delta] is the scroll that was applied (0 for the branches that do
     * not scroll).
     */
    fun record(neighbourKey: String, branch: String, delta: Int) {
        val line = "detail: seasonRowReveal neighbour=$neighbourKey branch=$branch delta=$delta"
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_LINES) entries.removeFirst()
        }
    }

    /** One line per recorded decision, oldest first; empty until one is asked. */
    fun lines(): List<String> = synchronized(entries) { entries.toList() }
}
