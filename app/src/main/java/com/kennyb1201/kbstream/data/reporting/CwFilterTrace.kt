package com.kennyb1201.kbstream.data.reporting

/**
 * The masked-title filter's last decision per Continue Watching card, for the
 * in-app Diagnostics report.
 *
 * The spec that commissioned the CW hidden-titles fix (2026-11-05) cost an
 * evening for one reason: no capture could say which identity the filter was
 * handed for the card that would not go away. The rails log the same title's
 * meta id, but a CW card is built from history and tracker feeds, and its
 * `(type, show title, id)` can name the same show differently - so "hidden
 * everywhere except Continue Watching" had no evidence to read.
 *
 * One line per card, kept for the cards too: a kept card whose name matches a
 * hidden entry is where the next investigation starts. Titles and ids only -
 * the same PII posture as the other session lines.
 *
 * Bounded on purpose (a report is not a log file): one rail's worth of cards,
 * replaced wholesale on each filter pass, so the block always describes the
 * rail the viewer is looking at.
 */
object CwFilterTrace {

    /** One rail's worth of cards; a longer rail keeps its head. */
    const val MAX_ENTRIES = 24

    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Replaces the block with one filter pass's decisions. Called once per pass
     * rather than per card, so a recomposition that rebuilds nothing cannot
     * interleave a half-updated rail with the previous one's lines.
     */
    @Synchronized
    fun recordAll(lines: List<String>) {
        entries.clear()
        lines.take(MAX_ENTRIES).forEach { entries.addLast(it) }
    }

    /** The last pass, in rail order. Empty until the filter has run. */
    @Synchronized
    fun lines(): List<String> = entries.toList()
}
