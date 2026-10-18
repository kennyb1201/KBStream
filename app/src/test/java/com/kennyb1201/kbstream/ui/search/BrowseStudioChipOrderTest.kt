package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the DC chip is drawn.
 *
 * The studios strip is presented "household names first, rest alphabetical"
 * (see [popularFirst]), and DC was left in the alphabetical tail — so the chip
 * for one of the biggest franchise catalogs sat below B-list labels. It now
 * sits in the curated popular block with the other franchise houses.
 *
 * Pinning the ORDER rather than the widget is deliberate: the list the picker
 * draws is [BROWSE_STUDIOS], so its index is the only thing a test can press
 * without a screen.
 */
class BrowseStudioChipOrderTest {

    private val names: List<String> = BROWSE_STUDIOS.map { it.name.trim() }

    @Test
    fun `DC is drawn with the popular studios, ahead of the alphabetical tail`() {
        val dc = names.indexOf("DC")
        // "Blumhouse Productions" sorts before "DC" alphabetically, so it can
        // only sit behind the DC chip while DC is in the curated block.
        val blumhouse = names.indexOf("Blumhouse Productions")

        assertTrue("the DC chip is missing from the studios strip", dc >= 0)
        assertTrue("Blumhouse moved off the studios strip", blumhouse >= 0)
        assertTrue(
            "DC is back in the alphabetical tail (index $dc, behind Blumhouse " +
                "at $blumhouse): it belongs with the popular studios",
            dc < blumhouse
        )
    }

    @Test
    fun `the DC chip is still the one entry, with its primary id`() {
        // Moving the chip must not have resolved it to another company or
        // duplicated it: 9993 is what the rails widen to the full DC union.
        val dcEntries = BROWSE_STUDIOS.filter { it.name.trim() == "DC" }

        assertEquals("the studios strip carries DC exactly once", 1, dcEntries.size)
        assertEquals(9993, dcEntries.single().id)
    }
}
