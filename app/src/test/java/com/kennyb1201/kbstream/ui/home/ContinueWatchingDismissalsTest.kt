package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dismissal lookup itself: one card's key set against the stored map.
 *
 * The bug this is part of is that the lookup asked ONE key. The keys a card
 * carries are covered by [ContinueWatchingDismissalFlavorTest]; this pins what
 * happens once more than one of them is stored.
 */
class ContinueWatchingDismissalsTest {

    private fun stored(
        vararg entries: Pair<String, Long>
    ): MutableMap<String, Long> = linkedMapOf(*entries)

    private val show =
        setOf("parent:series:tt10986410", "parent:series:97546")

    @Test
    fun `a matched dismissal hides the card and keeps the marker`() {
        val dismissals = stored(
            "parent:series:tt10986410" to 1_000L,
            "parent:series:97546" to 1_000L
        )

        val outcome = continueWatchingDismissalOutcome(
            dismissalKeys = show,
            dismissals = dismissals,
            recencyTimestamp = 500L
        )

        assertEquals(ContinueWatchingDismissalOutcome.HIDDEN, outcome)
        assertEquals(2, dismissals.size)
    }

    @Test
    fun `watch activity newer than the dismissal brings the card back and clears every key`() {
        val dismissals = stored(
            "parent:series:tt10986410" to 1_000L,
            "parent:series:97546" to 1_000L
        )

        val outcome = continueWatchingDismissalOutcome(
            dismissalKeys = show,
            dismissals = dismissals,
            recencyTimestamp = 2_000L
        )

        assertEquals(ContinueWatchingDismissalOutcome.REARMED, outcome)
        // Both, not just one: a leftover key would re-hide the card on the
        // very next rebuild.
        assertTrue("the re-arm must clear every matched key", dismissals.isEmpty())
    }

    @Test
    fun `the newest matched dismissal decides`() {
        val dismissals = stored(
            "parent:series:tt10986410" to 1_000L,
            "parent:series:97546" to 5_000L
        )

        // Newer than the older marker, older than the newest one: still hidden.
        assertEquals(
            ContinueWatchingDismissalOutcome.HIDDEN,
            continueWatchingDismissalOutcome(show, dismissals, 3_000L)
        )

        // Newer than both: the card is back, and neither marker survives.
        assertEquals(
            ContinueWatchingDismissalOutcome.REARMED,
            continueWatchingDismissalOutcome(show, dismissals, 6_000L)
        )
        assertTrue(dismissals.isEmpty())
    }

    @Test
    fun `a card exactly as old as its dismissal stays hidden`() {
        val dismissals = stored("parent:series:97546" to 1_000L)

        assertEquals(
            ContinueWatchingDismissalOutcome.HIDDEN,
            continueWatchingDismissalOutcome(show, dismissals, 1_000L)
        )
        assertEquals(1, dismissals.size)
    }

    @Test
    fun `another show's dismissal leaves the card and the map alone`() {
        val dismissals = stored("parent:series:4321" to 1_000L)

        assertEquals(
            ContinueWatchingDismissalOutcome.NONE,
            continueWatchingDismissalOutcome(show, dismissals, 500L)
        )
        assertEquals(1, dismissals.size)
    }

    @Test
    fun `no dismissals stored and no keys are both a pass`() {
        assertEquals(
            ContinueWatchingDismissalOutcome.NONE,
            continueWatchingDismissalOutcome(show, stored(), 0L)
        )
        assertEquals(
            ContinueWatchingDismissalOutcome.NONE,
            continueWatchingDismissalOutcome(
                emptySet(),
                stored("parent:series:97546" to 1_000L),
                0L
            )
        )
    }
}
