package com.kennyb1201.kbstream.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The table a browse page consults about its held Detail-return record.
 *
 * Field report: on a genre page whose clicked rail built slower than the
 * others, Back from Detail did not return to the item - the page took its own
 * first card. The record, the key and the landing were all in place; what ran
 * out was the retry budget, which was being spent on the ARRIVAL of the page's
 * other rails (`returnAttempts++` on every `sections` emission) rather than on
 * landing tries. Twelve rails arriving ahead of the one holding the card
 * exhausted it, and the effect then abandoned the record.
 *
 * So the two budgets are separate, and that is what this table pins: waiting
 * for the recorded rail to appear is free and bounded by TIME, while the
 * landing budget is spent only where a landing was actually attempted.
 */
class BrowseReturnDecisionTest {

    private fun target(
        railKey: String? = "Popular",
        itemId: String = "movie:603",
        position: Int = 20
    ): BrowseReturnTarget = BrowseReturnTarget(
        screenKey = "tag:genre:18:movie",
        itemId = itemId,
        position = position,
        railKey = railKey,
        railIndex = 2
    )

    private fun decide(
        recorded: BrowseReturnTarget? = target(),
        arrived: Set<String> = setOf("Popular"),
        attempts: Int = 0,
        waitStartMs: Long = NOW,
        nowMs: Long = NOW
    ): BrowseReturnDecision = browseReturnDecision(
        recorded = recorded,
        arrivedRailKeys = arrived,
        attemptsSpent = attempts,
        waitStartMs = waitStartMs,
        nowMs = nowMs
    )

    @Test
    fun `an ordinary open takes the page's own first card`() {
        assertEquals(BrowseReturnDecision.FIRST_CARD, decide(recorded = null))
    }

    @Test
    fun `a rail that has not arrived yet is waited for, and costs nothing`() {
        // The whole fix, in one case: the page's OTHER rails have landed (so
        // this composition is a fresh one), the recorded rail has not, and the
        // landing budget is untouched - which is what lets the card still be
        // reached when its rail shows up several arrivals later.
        assertEquals(
            BrowseReturnDecision.WAIT_FOR_RAIL,
            decide(arrived = setOf("New Releases", "Top Rated"), attempts = 0)
        )
        assertEquals(
            "and a page that has already waited (an earlier arrival restarted " +
                "this effect) is still waiting, still having spent nothing",
            BrowseReturnDecision.WAIT_FOR_RAIL,
            decide(arrived = setOf("New Releases", "Top Rated"), attempts = 0)
        )
    }

    @Test
    fun `a wait with no clock reading is treated as fresh`() {
        // The wait start is 0L until a record is read, and a composition that
        // sees 0 with a record in hand is one whose record was just consumed.
        assertEquals(
            BrowseReturnDecision.WAIT_FOR_RAIL,
            decide(
                arrived = emptySet(),
                waitStartMs = 0L,
                nowMs = NOW + BROWSE_RETURN_WAIT_MS
            )
        )
    }

    @Test
    fun `a wait that has run out gives up on the record`() {
        // A rail whose load failed must not strand the D-pad: the page takes
        // its first card rather than holding a record for ever.
        assertEquals(
            BrowseReturnDecision.FIRST_CARD,
            decide(
                arrived = emptySet(),
                waitStartMs = NOW,
                nowMs = NOW + BROWSE_RETURN_WAIT_MS
            )
        )
    }

    @Test
    fun `just inside the wait is still a wait`() {
        assertEquals(
            BrowseReturnDecision.WAIT_FOR_RAIL,
            decide(
                arrived = emptySet(),
                waitStartMs = NOW,
                nowMs = NOW + BROWSE_RETURN_WAIT_MS - 1
            )
        )
    }

    @Test
    fun `a rail that is here spends one landing try`() {
        assertEquals(BrowseReturnDecision.TRY_LANDING, decide(attempts = 0))
        assertEquals(
            "the budget is inclusive of its last step",
            BrowseReturnDecision.TRY_LANDING,
            decide(attempts = BROWSE_RETURN_FOCUS_STEPS - 1)
        )
    }

    @Test
    fun `an exhausted landing budget falls back to the first card`() {
        assertEquals(
            BrowseReturnDecision.FIRST_CARD,
            decide(attempts = BROWSE_RETURN_FOCUS_STEPS)
        )
    }

    @Test
    fun `a record with no rail has nothing to wait for`() {
        // The folder grid, and the collection's single rail: the card is
        // already on screen, so there is no arrival to wait behind - and the
        // page's landing is not delayed by one that will never come.
        assertEquals(
            BrowseReturnDecision.TRY_LANDING,
            decide(recorded = target(railKey = null), arrived = emptySet())
        )
    }

    @Test
    fun `a spent landing budget still gives up on a rail that never arrived`() {
        assertEquals(
            BrowseReturnDecision.FIRST_CARD,
            decide(
                arrived = emptySet(),
                attempts = BROWSE_RETURN_FOCUS_STEPS,
                waitStartMs = NOW,
                nowMs = NOW + BROWSE_RETURN_WAIT_MS
            )
        )
    }

    @Test
    fun `the KB folder keyspace is a rail key like any other`() {
        val folder = target(railKey = "rail:tmdb:trending")
        assertEquals(
            BrowseReturnDecision.WAIT_FOR_RAIL,
            decide(recorded = folder, arrived = setOf("rail:tmdb:top_rated"))
        )
        assertEquals(
            BrowseReturnDecision.TRY_LANDING,
            decide(recorded = folder, arrived = setOf("rail:tmdb:trending"))
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
