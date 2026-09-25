package com.kennyb1201.kbstream.data.sync

import com.kennyb1201.kbstream.data.sync.KidsTimeGuard.ForegroundTransition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kids-time foreground tracking. The daily-limit clock may only run while the
 * PROCESS is foregrounded, and the count of started Activities is what decides
 * that.
 *
 * This is the guard that matters most in practice: every player is a separate
 * Activity, so the old MainActivity-owned wiring counted "the player opened"
 * as the app going to the background and stopped the clock for the whole of
 * playback. A child who let the Up Next chain keep feeding episodes therefore
 * never spent any of the budget, and the limit never arrived.
 */
class KidsTimeGuardTest {

    @Test
    fun `first started activity foregrounds the app`() {
        assertEquals(
            ForegroundTransition.FOREGROUNDED,
            KidsTimeGuard.foregroundTransition(0, 1)
        )
    }

    @Test
    fun `opening the player on top keeps the app foregrounded`() {
        // MainActivity (1) then the player (2): the clock must not pause.
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(1, 2)
        )
        // A hand-off (native -> mpv, or the external picker) stacks one more.
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(2, 3)
        )
    }

    @Test
    fun `closing the player keeps the app foregrounded`() {
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(2, 1)
        )
    }

    @Test
    fun `last started activity stopping backgrounds the app`() {
        assertEquals(
            ForegroundTransition.BACKGROUNDED,
            KidsTimeGuard.foregroundTransition(1, 0)
        )
        // An unmatched stop must still read as backgrounded, never as a
        // negative count that keeps the clock running forever.
        assertEquals(
            ForegroundTransition.BACKGROUNDED,
            KidsTimeGuard.foregroundTransition(2, 0)
        )
    }

    @Test
    fun `an unchanged count announces nothing`() {
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(0, 0)
        )
        assertEquals(
            ForegroundTransition.NONE,
            KidsTimeGuard.foregroundTransition(3, 3)
        )
    }
}
