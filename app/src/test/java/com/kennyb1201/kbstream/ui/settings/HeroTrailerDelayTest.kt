package com.kennyb1201.kbstream.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait before the hero starts a trailer, now a setting rather than the
 * constant 4s both heroes hardcoded.
 *
 * The value syncs, so it can arrive from a build whose list of waits was
 * different — the snapping below is what keeps that from landing a dwell the
 * settings screen cannot even show.
 */
class HeroTrailerDelayTest {

    @Test
    fun `a default install sits on the dwell the heroes always used`() {
        assertEquals(4_000L, AppPreferences.DEFAULT_HERO_TRAILER_DELAY_MS)
        assertTrue(
            "the default must be one of the chips",
            AppPreferences.DEFAULT_HERO_TRAILER_DELAY_MS in
                AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS
        )
    }

    @Test
    fun `every offered wait is stored exactly as offered`() {
        AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS.forEach { ms ->
            assertEquals(ms, AppPreferences.heroTrailerDelayFromStored(ms))
        }
    }

    @Test
    fun `the chips run shortest first and never repeat`() {
        val options = AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS
        assertEquals(options.sorted(), options)
        assertEquals(options.size, options.toSet().size)
        // "Now" is offered, so the row can express "no wait at all".
        assertEquals(0L, options.first())
    }

    @Test
    fun `a wait from another build snaps to the nearest one on offer`() {
        // A build that offered 3s: neither neighbour is more right, so the
        // shorter wait wins the tie rather than the list being consulted twice.
        assertEquals(2_000L, AppPreferences.heroTrailerDelayFromStored(3_000L))
        assertEquals(2_000L, AppPreferences.heroTrailerDelayFromStored(2_400L))
        assertEquals(4_000L, AppPreferences.heroTrailerDelayFromStored(3_600L))
        assertEquals(6_000L, AppPreferences.heroTrailerDelayFromStored(5_500L))
    }

    @Test
    fun `a nonsense stored wait still lands on a chip`() {
        // Never a dwell nothing can show, and never one measured in days.
        assertEquals(0L, AppPreferences.heroTrailerDelayFromStored(-5_000L))
        assertEquals(0L, AppPreferences.heroTrailerDelayFromStored(500L))
        assertEquals(
            AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS.last(),
            AppPreferences.heroTrailerDelayFromStored(999_999_999L)
        )
    }

    @Test
    fun `a wait reads as Now or as whole seconds`() {
        assertEquals("Now", AppPreferences.heroTrailerDelayLabel(0L))
        assertEquals("2s", AppPreferences.heroTrailerDelayLabel(2_000L))
        assertEquals("4s", AppPreferences.heroTrailerDelayLabel(4_000L))
        assertEquals("8s", AppPreferences.heroTrailerDelayLabel(8_000L))
        // The label follows the same snapping the stored value does, so a chip
        // can never read as a wait the pref would refuse to hold.
        assertEquals("Now", AppPreferences.heroTrailerDelayLabel(400L))
    }
}
