package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamRankReport] is the only part of the diagnostics report that says *why*
 * a stream list came out in the order it did, so the two things a capture has to
 * be able to answer are pinned here: which fetch a block belongs to (several are
 * kept, because a wrong-episode complaint spans two episodes), and what
 * auto-play actually started (which no block can work out from itself).
 */
class StreamRankReportTest {

    /**
     * Evicts whatever an earlier case left behind. The object is process-wide
     * and keeps three blocks, so three fetches recorded and thrown away leave
     * the next record the only one that matters — and the cases stop depending
     * on each other's order.
     */
    private fun discardEarlierFetches() {
        repeat(3) { StreamRankReport.record(listOf("streams: an earlier fetch")) }
    }

    @Test
    fun `the newest fetch is the last block and earlier ones are still there`() {
        discardEarlierFetches()
        StreamRankReport.record(listOf("streams: 3 source(s), ranked for S03E34, top 3:"))
        StreamRankReport.record(listOf("streams: 4 source(s), ranked for S03E35, top 3:"))

        val lines = StreamRankReport.lines()
        assertEquals("streams: 4 source(s), ranked for S03E35, top 3:", lines.last())
        assertTrue(
            "the earlier fetch is gone, so two episodes cannot be compared",
            lines.contains("streams: 3 source(s), ranked for S03E34, top 3:")
        )
    }

    @Test
    fun `only the last three fetches are kept`() {
        (1..5).forEach { StreamRankReport.record(listOf("streams: fetch $it")) }

        assertEquals(
            listOf(
                "streams: fetch 3", "",
                "streams: fetch 4", "",
                "streams: fetch 5"
            ),
            StreamRankReport.lines()
        )
    }

    @Test
    fun `auto-play annotates the newest block, at its end`() {
        discardEarlierFetches()
        StreamRankReport.record(listOf("streams: 11 source(s), ranked for S03E35, top 3:"))
        StreamRankReport.noteAutoPlay("started · Some Release 1080p")

        val lines = StreamRankReport.lines()
        assertEquals("auto-play: started · Some Release 1080p", lines.last())
        assertEquals(
            "streams: 11 source(s), ranked for S03E35, top 3:",
            lines[lines.size - 2]
        )
    }

    @Test
    fun `a second auto-play call replaces the first rather than stacking`() {
        discardEarlierFetches()
        StreamRankReport.record(listOf("streams: 11 source(s), ranked for S03E35, top 3:"))
        StreamRankReport.noteAutoPlay("started · first guess")
        StreamRankReport.noteAutoPlay("none - the picker is showing instead")

        val lines = StreamRankReport.lines()
        assertEquals(1, lines.count { it.startsWith("auto-play:") })
        assertEquals("auto-play: none - the picker is showing instead", lines.last())
        assertFalse(lines.any { it.contains("first guess") })
    }

    @Test
    fun `a line that already carries the marker is not prefixed twice`() {
        discardEarlierFetches()
        StreamRankReport.record(listOf("streams: 1 source(s), ranked, top 1:"))
        StreamRankReport.noteAutoPlay("auto-play: started · X")

        assertEquals("auto-play: started · X", StreamRankReport.lines().last())
    }
}
