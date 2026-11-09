package com.kennyb1201.kbstream.data.reporting

/**
 * What the Continue Watching / Upcoming evaluation last decided, per show.
 *
 * The rails' evaluation lines were `Log.d("UPCOMING_DIAG", ...)` only, so the one
 * report a viewer can actually send could not answer the question every
 * "a show is missing from Continue Watching" report asks: was this show
 * EVALUATED and skipped (and on what tally), or did it never reach the
 * evaluation at all? The release APK carries no logcat, and a TV has no way to
 * read one, so the lines are kept here and printed by [Diagnostics] - the same
 * shape as [GuideTrace] and [DetailTrace], because the same lines already
 * existed in logcat with no way to travel.
 *
 * Two kinds of line, in the order one refresh emits them:
 *
 *  - `cw skip …` / `cw kept …` - the per-show verdict of the Simkl CW leg, with
 *    the watched/aired tallies and, for a skip, the reason token the rule
 *    concluded;
 *  - `caught-up …` - the blob / leg (c) lines, including the blob's own ages
 *    (`ageMs` vs `lastWriteAgeMs`), which is the staleness evidence.
 *
 * Titles and id forms only, exactly what logcat already carried: no token, no
 * account identifier, and no watch history beyond the tallies that are the
 * diagnosis.
 *
 * The ring is process-wide and is NOT cleared when Home is left, a rail is
 * rebuilt or the profile switched: the report is taken from Settings AFTER the
 * repro, so the lines have to still be there. Only [reset] (tests) clears it.
 */
internal object UpcomingDiagTrace {

    /**
     * Lines kept. [GuideTrace]'s sixteen is the wrong size here: ONE Simkl feed
     * build emits a skip or kept line per evaluated show - library-scale,
     * dozens - plus the caught-up blob line and the leg (c) summaries, so at
     * sixteen a single build would evict the blob line, which is the staleness
     * evidence the report exists for. Sixty-four is about one full build.
     *
     * Eviction therefore takes the OLDEST lines, and the emission order makes
     * that the right thing to lose: the CW skips come first in a refresh and
     * the blob / leg (c) lines after (leg (c) runs off the Upcoming build), so
     * on a build larger than the ring the blob line survives at the tail while
     * the earliest skips fall off. The one-line candidate summaries still name
     * every candidate, so the set is never lost even when a skip line is.
     */
    private const val MAX_LINES = 64

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Appends one line, dropping the oldest past [MAX_LINES]. */
    fun note(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }
    }

    /** The recent lines, oldest first. Empty until Home has evaluated a rail. */
    fun lines(): List<String> = lines

    /** Clears the ring (tests). */
    fun reset() {
        synchronized(lock) { lines = emptyList() }
    }
}
