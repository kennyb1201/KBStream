package com.kennyb1201.kbstream.ui.detail

/**
 * The rail card a detail page's drill-down was opened from.
 *
 * The detail page is rebuilt from scratch when the actor / network /
 * production-company page it opened is backed out of: its scroll offset, its
 * focus and the `focusRestorer` that remembered which card was selected all go
 * with the composition. Without a record of where the click came from the page
 * reopens at its first item with the play button focused, which loses the
 * viewer's place in the very rail they were working through.
 */
data class DetailReturnTarget(
    /** "type:id" of the detail page the drill-down came from. */
    val detailKey: String,
    /** The pressed card, keyed the way its own rail keys it. */
    val targetKey: String,
    /** The detail-list item key of the rail that holds that card. */
    val rowKey: String,
    /** Where that rail sat in the detail list, so it can be put back. */
    val rowIndex: Int,
    /** Where the card sat in its rail, so the rail can be put back too. */
    val itemIndex: Int
)

/**
 * Handoff from a detail page's rail to itself, across the drill-down it opens.
 * The page records the card on the way out and reads the record back the next
 * time that same title composes.
 *
 * Held outside the composition because that is exactly what does not survive
 * the trip: leaving the detail screen disposes it. Read once, by the page it
 * was raised for and no other, and aged out on top of that in case the Back it
 * was waiting for never comes.
 *
 * ONE SLOT PER TITLE, not one slot in total, for the reason
 * [com.kennyb1201.kbstream.ui.browse.BrowseReturnFocus] documents: a single slot
 * let a chained drill-down (Detail X -> Actor Y -> a second detail page) record
 * over the record X was still waiting for, so backing out twice reopened X at
 * the top instead of on the card that was pressed. Keying by `detailKey`
 * protects the WRITE as well as the read.
 */
object DetailReturnFocus {

    private class Recorded(val target: DetailReturnTarget, val recordedAtMs: Long)

    /** detailKey -> that title's record. Insertion-ordered, for the size cap. */
    private val pending = LinkedHashMap<String, Recorded>()

    /**
     * Long enough for a filmography to be browsed before coming back, short
     * enough that a session-old record cannot answer for a fresh open of the
     * same title.
     */
    private const val VALID_FOR_MS = 30 * 60 * 1000L

    /**
     * How many titles may be waiting at once. A drill-down chain is a handful;
     * this only stops a pathological session accumulating records nobody will
     * read.
     */
    private const val MAX_PENDING = 8

    /** Records the card a drill-down is being opened from, for that title. */
    @Synchronized
    fun record(target: DetailReturnTarget) {
        val now = System.currentTimeMillis()
        prune(now)
        pending.remove(target.detailKey)
        pending[target.detailKey] = Recorded(target, now)
        while (pending.size > MAX_PENDING) {
            pending.remove(pending.keys.first())
        }
    }

    /**
     * Reads the record for [detailKey], if there is one, and clears it once.
     *
     * Records for other titles stay where they are: a drill-down can compose a
     * second detail page on the way - a "More Like This" poster opens one - and
     * that page must not swallow the record the first one is still waiting for.
     */
    @Synchronized
    fun consume(detailKey: String): DetailReturnTarget? {
        prune(System.currentTimeMillis())
        return pending.remove(detailKey)?.target
    }

    /** Drops every aged-out record: stale for the page that raised it too. */
    private fun prune(now: Long) {
        pending.entries.removeAll { now - it.value.recordedAtMs > VALID_FOR_MS }
    }
}
