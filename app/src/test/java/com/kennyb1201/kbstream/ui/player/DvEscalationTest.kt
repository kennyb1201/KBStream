package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this guards is narrow on purpose: it must fire ONLY for a vendor DV
 * decoder refusal that has already spent its stripped-DV retry. Firing when the
 * strip retry has not been used would skip the retry that actually plays the
 * file on boxes where the rewrite works, and firing for a non-DV failure would
 * move unrelated problems off the ladder that can fix them.
 */
class DvEscalationTest {

    @Test
    fun `a spent strip retry on a refused DV decoder hands off`() {
        assertTrue(
            DvEscalation.handOffAfterStripRetry(
                stripRetrySpent = true,
                dvDecoderRefused = true
            )
        )
    }

    @Test
    fun `the first DV refusal keeps the stripped retry instead of handing off`() {
        assertFalse(
            DvEscalation.handOffAfterStripRetry(
                stripRetrySpent = false,
                dvDecoderRefused = true
            )
        )
    }

    @Test
    fun `a spent strip retry on a non-DV failure stays on the ladder`() {
        assertFalse(
            DvEscalation.handOffAfterStripRetry(
                stripRetrySpent = true,
                dvDecoderRefused = false
            )
        )
    }

    @Test
    fun `no strip retry and no DV refusal stays on the ladder`() {
        assertFalse(
            DvEscalation.handOffAfterStripRetry(
                stripRetrySpent = false,
                dvDecoderRefused = false
            )
        )
    }
}
