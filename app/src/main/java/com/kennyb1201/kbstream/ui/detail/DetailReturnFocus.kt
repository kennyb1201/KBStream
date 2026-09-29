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
 * One-slot handoff from a detail page's rail to itself, across the drill-down
 * it opens. The page records the card on the way out and reads the record back
 * the next time that same title composes.
 *
 * Held outside the composition because that is exactly what does not survive
 * the trip: leaving the detail screen disposes it. Read once, by the page it
 * was raised for and no other, and aged out on top of that in case the Back it
 * was waiting for never comes.
 */
object DetailReturnFocus {

    private var pending: DetailReturnTarget? = null
    private var recordedAtMs: Long = 0L

    /**
     * Long enough for a filmography to be browsed before coming back, short
     * enough that a session-old record cannot answer for a fresh open of the
     * same title.
     */
    private const val VALID_FOR_MS = 30 * 60 * 1000L

    /** Records the card a drill-down is being opened from. */
    fun record(target: DetailReturnTarget) {
        pending = target
        recordedAtMs = System.currentTimeMillis()
    }

    /**
     * Reads the record, if it is for [detailKey], and clears it once.
     *
     * A record for another title is left exactly where it is: a drill-down can
     * compose a second detail page on the way - a "More Like This" poster opens
     * one - and that page must not swallow the record the first one is still
     * waiting for. [DetailReturnTarget.detailKey] is what keeps the two apart.
     */
    fun consume(detailKey: String): DetailReturnTarget? {
        val recorded = pending ?: return null
        if (
            recorded.detailKey != detailKey ||
            System.currentTimeMillis() - recordedAtMs > VALID_FOR_MS
        ) {
            return null
        }
        pending = null
        recordedAtMs = 0L
        return recorded
    }
}
