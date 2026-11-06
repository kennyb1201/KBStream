package com.kennyb1201.kbstream.data.reporting

/**
 * What each pinned Library rail's rows CARRIED as art, per rail load, for the
 * in-app Diagnostics report (COPY DIAGNOSTICS).
 *
 * Why: the 2026-11 report - "the pinned Trakt rails show titles only while the
 * BingeCat rail beside them has posters" - has two causes that look identical
 * from outside the app. Either Trakt's read shipped no poster URL for those rows
 * at all, or it shipped URLs that do not load (a dead image host, a 404 on a
 * republished poster), which ALSO switched the TMDB resolvers off, because the
 * card treated "carries a URL" as "has art". The two need different fixes - one
 * in the read, one in the card - and no capture could say which one it was, so
 * the fix for the card had to ship on a guess.
 *
 * One line per rail load answers that from the first report: how many rows
 * carried a poster URL and how many did not. Counts and the rail's own name and
 * id only - the same PII posture as the other rail lines.
 *
 * Bounded on purpose (a report is not a log file): one line per pinned rail,
 * replaced wholesale on each load, so the block always describes the rails the
 * viewer is looking at.
 */
object LibraryRailArtTrace {

    /** Rails kept; more than a Home column holds. */
    const val MAX_RAILS = 12

    /** One line per rail, in the order the rails were loaded. */
    private val byRail = LinkedHashMap<String, String>(MAX_RAILS)

    /**
     * Files this load's line for [rail], replacing whatever that rail said last
     * time. Called once per rail load rather than per row, so a half-built rail
     * can never be reported as if it were the whole answer.
     */
    @Synchronized
    fun record(rail: String, line: String) {
        byRail[rail] = line
        while (byRail.size > MAX_RAILS) {
            val oldest = byRail.keys.firstOrNull() ?: break
            byRail.remove(oldest)
        }
    }

    /** The last load of each rail, oldest first. Empty until a rail has loaded. */
    @Synchronized
    fun lines(): List<String> = byRail.values.toList()

    /** Clears the block (tests and a profile switch). */
    @Synchronized
    fun reset() {
        byRail.clear()
    }
}

/**
 * The one line [LibraryRailArtTrace] keeps for a rail load.
 *
 * Written as one function rather than at the call site so the shape is pinned
 * without a device: the whole point of the line is that its two counts are
 * readable at a glance next to the rail's own name.
 */
internal fun libraryRailCarriedArtLine(
    source: String,
    listName: String,
    listId: String,
    carried: Int,
    missing: Int
): String {
    val total = carried + missing
    return "rail art: $source \"$listName\" ($listId): " +
        "$carried of $total row(s) carried a poster, $missing did not"
}
