package com.kennyb1201.kbstream.data.reporting

/**
 * What the detail page's vertical focus last did, in the words the diagnostics
 * report prints.
 *
 * Every focus trap on that page fails the same way: the press is consumed, the
 * focus does not move, and nothing is said. "UP does nothing after I walk down
 * and back up" was diagnosed by hand, three field questions at a time, because
 * the page's own lines live in logcat and a TV has no way to read one. The
 * release APK carries no logcat either, so this ring is what makes the next
 * paste name the move, the target and whether it landed.
 *
 * Sixteen lines, not eight: a full walk is about ten rows down and ten back up,
 * and what a trap needs is the walk that ended in it, not just its tail. The
 * lines are short - row names, directions, counts.
 *
 * Row names ([com.kennyb1201.kbstream.ui.detail.DetailRow]), directions, attempt
 * counts and hand-back surface labels only: never a title, a TMDB/IMDb id or
 * anything else that names what the viewer was looking at.
 *
 * The ring is process-wide and is NOT cleared when Detail is left, the title
 * changes or the profile switches - the report is taken from Settings AFTER the
 * repro. Only [reset] (tests) clears it.
 */
internal object DetailTrace {

    /** Lines kept. A full walk is ~20 moves, so this holds the whole trap. */
    private const val MAX_LINES = 16

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Appends one line, dropping the oldest past [MAX_LINES]. */
    fun note(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }
    }

    /** The recent lines, oldest first. Empty until Detail has been moved on. */
    fun lines(): List<String> = lines

    /** Clears the ring (tests). */
    fun reset() {
        synchronized(lock) { lines = emptyList() }
    }
}
