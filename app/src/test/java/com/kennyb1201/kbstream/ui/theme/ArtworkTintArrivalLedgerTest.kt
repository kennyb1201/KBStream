package com.kennyb1201.kbstream.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dynamic-colour fix (2026-11-17), F1/F2's bookkeeping.
 *
 * Two rules decide whether a tint ever resolves for art that arrives late, and
 * neither needs a device:
 *
 *  - an arrival is news once, and it is remembered by the URL the sampler will
 *    ask for (trimmed, so a whitespace-padded copy is the same art);
 *  - the sampler's OWN load is not news. Without that the signal would feed
 *    itself - sample succeeds, arrival fires, sample again - and a title with no
 *    usable colour would never stop being probed.
 *
 * The flow that wakes the surfaces and the miss invalidation are pinned by
 * source in ArtworkTintArrivalContractTest, because they live on the Coil and
 * Compose side of this module.
 */
class ArtworkTintArrivalLedgerTest {

    @Before
    fun reset() {
        ArtworkTintArrivalLedger.clear()
    }

    @Test
    fun `an arrival is remembered for the url the sampler asks for`() {
        assertFalse("nothing has landed yet", ArtworkTintArrivalLedger.hasLanded(URL))
        assertTrue(ArtworkTintArrivalLedger.noteLanded(URL))
        assertTrue("and now it has", ArtworkTintArrivalLedger.hasLanded(URL))
        assertFalse("another title is unaffected", ArtworkTintArrivalLedger.hasLanded("other"))
    }

    @Test
    fun `the url is normalised, so a padded copy is the same art`() {
        assertTrue(ArtworkTintArrivalLedger.noteLanded("  $URL  "))
        assertTrue(ArtworkTintArrivalLedger.hasLanded(URL))
        assertTrue(ArtworkTintArrivalLedger.hasLanded(" $URL "))
    }

    @Test
    fun `a blank url is not an arrival`() {
        assertFalse(ArtworkTintArrivalLedger.noteLanded(""))
        assertFalse(ArtworkTintArrivalLedger.noteLanded("   "))
        assertFalse(ArtworkTintArrivalLedger.hasLanded(""))
    }

    @Test
    fun `the samplers own load is not an arrival`() {
        ArtworkTintArrivalLedger.beginProbe(URL)
        try {
            assertTrue("the probe is visible while it runs", ArtworkTintArrivalLedger.isProbing(URL))
            assertFalse(
                "its success must not count, or the tint answers itself forever",
                ArtworkTintArrivalLedger.noteLanded(URL)
            )
            assertFalse("and nothing is recorded", ArtworkTintArrivalLedger.hasLanded(URL))
        } finally {
            ArtworkTintArrivalLedger.endProbe(URL)
        }
        assertTrue("once the probe ends, a real load IS news", ArtworkTintArrivalLedger.noteLanded(URL))
        assertTrue(ArtworkTintArrivalLedger.hasLanded(URL))
    }

    @Test
    fun `a probe of one url does not silence another`() {
        ArtworkTintArrivalLedger.beginProbe(URL)
        try {
            assertTrue(ArtworkTintArrivalLedger.noteLanded("other"))
        } finally {
            ArtworkTintArrivalLedger.endProbe(URL)
        }
    }

    @Test
    fun `nested probes stay counted until the last one ends`() {
        ArtworkTintArrivalLedger.beginProbe(URL)
        ArtworkTintArrivalLedger.beginProbe(URL)
        ArtworkTintArrivalLedger.endProbe(URL)
        assertTrue("one probe is still running", ArtworkTintArrivalLedger.isProbing(URL))
        assertFalse(ArtworkTintArrivalLedger.noteLanded(URL))
        ArtworkTintArrivalLedger.endProbe(URL)
        assertFalse(ArtworkTintArrivalLedger.isProbing(URL))
        assertTrue(ArtworkTintArrivalLedger.noteLanded(URL))
    }

    @Test
    fun `an unbalanced end is ignored rather than corrupting the count`() {
        ArtworkTintArrivalLedger.endProbe(URL)
        assertFalse(ArtworkTintArrivalLedger.isProbing(URL))
        assertTrue(ArtworkTintArrivalLedger.noteLanded(URL))
    }

    @Test
    fun `clear drops both facts`() {
        ArtworkTintArrivalLedger.noteLanded(URL)
        ArtworkTintArrivalLedger.beginProbe("other")
        ArtworkTintArrivalLedger.clear()
        assertFalse(ArtworkTintArrivalLedger.hasLanded(URL))
        assertFalse(ArtworkTintArrivalLedger.isProbing("other"))
    }

    private companion object {
        const val URL = "https://image.tmdb.org/t/p/w1280/backdrop.jpg"
    }
}
