package com.kennyb1201.kbstream.data.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The live previous-channel toggle: pick a channel, pick another, and bounce
 * between the two.
 *
 * The failure this guards against is silent and easy to introduce: a toggle
 * that resolves to the wrong half of the pair, or that collapses to one
 * channel, still "works" on one press and only shows up as the viewer landing
 * back where they started on the second.
 */
class ChannelRecallTest {

    @Before
    fun setUp() = ChannelRecall.clear()

    @After
    fun tearDown() = ChannelRecall.clear()

    @Test
    fun `the first channel has nothing to recall to`() {
        ChannelRecall.onTuned("A")
        assertNull(ChannelRecall.target())
    }

    @Test
    fun `the channel you came from is the recall target`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())
    }

    @Test
    fun `the target is the previous channel, not the first one seen`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("B")
        ChannelRecall.onTuned("C")
        assertEquals("B", ChannelRecall.target())
    }

    @Test
    fun `a recall press swaps the pair so the next press goes back again`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())

        // A recall press tunes to the target and reports it like any other
        // tune - which is what has to re-arm the pair in the other direction.
        ChannelRecall.onTuned("A")
        assertEquals("B", ChannelRecall.target())

        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())
    }

    @Test
    fun `retuning the channel already playing does not collapse the pair`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("B")
        // The player reports its channel more than once (focus, overlay,
        // re-tune); only a real change may move the target.
        ChannelRecall.onTuned("B")
        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())
    }

    @Test
    fun `a blank id is not a channel`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("  ")
        ChannelRecall.onTuned(null)
        // Neither blank changed anything, so the next real switch still pairs
        // against A rather than against a phantom channel.
        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())
    }

    @Test
    fun `ids are trimmed so a padded id still matches itself`() {
        ChannelRecall.onTuned(" A ")
        ChannelRecall.onTuned("B")
        assertEquals("A", ChannelRecall.target())

        // The padded spelling of the channel already playing is the same
        // channel, so it must not become its own recall target. Untrimmed, this
        // read as a change from "B" to "  B  " and armed B against itself.
        ChannelRecall.onTuned("  B  ")
        assertEquals("A", ChannelRecall.target())
    }

    @Test
    fun `a target whose channel left the lineup can be dropped`() {
        ChannelRecall.onTuned("A")
        ChannelRecall.onTuned("B")
        ChannelRecall.clearTarget()
        assertNull(ChannelRecall.target())
    }
}
