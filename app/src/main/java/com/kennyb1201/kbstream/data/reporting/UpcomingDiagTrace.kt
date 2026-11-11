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
 * TWO stores, because the lines answer two different questions and must not
 * share one eviction budget:
 *
 *  - the one-line LEG SUMMARIES (`caught-up blob …`, `caught-up candidates=`,
 *    `caught-up enriched=`, `caught-up cards=`, the two local-leg lines, the
 *    kept-off line and the build-failure line) say what each leg SAW - the
 *    candidate set, the buckets, the blob's ages. They are the diagnostic
 *    signal, there is exactly one of each per refresh, and they live in
 *    [summaries]: one entry per kind, replaced when the same leg speaks again,
 *    never evicted, printed in a fixed order.
 *  - everything else (`cw skip …`, `cw kept …`, `dropped: …`, `fallback air
 *    TVmaze …`) is the per-show DETAIL, library-scale - dozens to hundreds of
 *    lines a build - and lives in the ring, evicting oldest first at
 *    [MAX_LINES].
 *
 * The split is what a 2026-11-11 paste proved was needed. The summaries are
 * emitted FIRST in each build and the dropped lines after, so on the two-build
 * refresh every Home refresh runs (see `UPCOMING_REBUILD_DEBOUNCE_MS`) the
 * summaries were the OLDEST lines in a single ring: 27 dropped lines from the
 * first build plus 36 from the second plus the kept-off line filled all
 * sixty-four and pushed every summary out. A show missing from Upcoming
 * (Universal Basic Guys) could then be shown to have never reached the builder
 * and still not be explained, because the candidate lists that would say why
 * were exactly the lines that had been evicted.
 *
 * Titles and id forms only, exactly what logcat already carried: no token, no
 * account identifier, and no watch history beyond the tallies that are the
 * diagnosis. Every line still goes to logcat unchanged - this class is an
 * additional sink, never a replacement, and it rewords nothing.
 *
 * Both stores are process-wide and are NOT cleared when Home is left, a rail is
 * rebuilt or the profile switched: the report is taken from Settings AFTER the
 * repro, so the lines have to still be there. Only [reset] (tests) clears them.
 */
internal object UpcomingDiagTrace {

    /**
     * Per-show lines kept. [GuideTrace]'s sixteen is the wrong size here: ONE
     * Simkl feed build emits a skip or kept line per evaluated show -
     * library-scale, dozens - plus a dropped line per dateless item, so at
     * sixteen a single build would lose most of the evidence. Sixty-four is
     * about one full build.
     *
     * Eviction therefore takes the OLDEST lines, and the emission order makes
     * that the right thing to lose: the CW skips come first in a refresh and
     * the builder's dropped lines after, so on a build larger than the ring the
     * newest verdicts survive while the earliest fall off. The summaries this
     * file's doc describes are NOT in this budget at all - they are one line per
     * kind in [summaries], which nothing evicts.
     */
    private const val MAX_LINES = 64

    /**
     * The one-line leg summary prefixes, exactly as the emitters start them,
     * in the order [summaries] prints them: blob, Simkl candidates, enriched,
     * Simkl cards, local candidates, local cards, kept-off, failed.
     *
     * A line matching one of these is a summary and goes to [summaries] only;
     * every other line is per-show detail and goes to the ring. Matching is by
     * prefix because the emitters build these lines by concatenation and the
     * suffix is the evidence (`source=… candidates=N '…'`), never a constant.
     *
     * The two `caught-up candidates` / `caught-up cards` pairs differ by leg:
     * the Simkl leg's end in `=`, the local leg's in ` (local…`, so neither
     * prefix can swallow the other.
     */
    private val SUMMARY_PREFIXES = listOf(
        "caught-up blob ",
        "caught-up candidates=",
        "caught-up enriched=",
        "caught-up cards=",
        "caught-up candidates (local",
        "caught-up cards (local",
        "kept off Upcoming (not caught up): ",
        "upcoming schedule build failed"
    )

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /**
     * One summary line per kind, keyed by its prefix from [SUMMARY_PREFIXES].
     * Bounded by construction: the key set is the prefix list, so this can
     * never hold more than eight lines however long the app runs, and a second
     * note of the same kind REPLACES the first rather than stacking.
     */
    @Volatile
    private var summaries: Map<String, String> = emptyMap()

    /**
     * Files one line: a summary into [summaries] (replacing the same kind), any
     * other line into the ring (dropping the oldest past [MAX_LINES]).
     */
    fun note(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        when (val kind = summaryKind(trimmed)) {
            null -> synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }
            // Same kind, same slot: the newest line wins and the store does not
            // grow, so two builds a refresh cannot duplicate a leg's line.
            else -> synchronized(lock) { summaries = summaries + (kind to trimmed) }
        }
    }

    /** The matched summary prefix, or null when [line] is per-show detail. */
    private fun summaryKind(line: String): String? =
        SUMMARY_PREFIXES.firstOrNull { line.startsWith(it) }

    /** The recent per-show lines, oldest first. Empty until Home has evaluated a rail. */
    fun lines(): List<String> = lines

    /**
     * The leg summaries, in [SUMMARY_PREFIXES] order regardless of which leg
     * spoke first in a given build - so two pastes of the same state read
     * identically. Kinds with no line yet are omitted, never printed blank.
     */
    fun summaries(): List<String> = SUMMARY_PREFIXES.mapNotNull { summaries[it] }

    /** Clears both stores (tests). */
    fun reset() {
        synchronized(lock) {
            lines = emptyList()
            summaries = emptyMap()
        }
    }
}
