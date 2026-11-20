package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The cost side of the image trace (diagnosis 2026-11-18, §1/§7).
 *
 * Every other count said what the loader DID; none said what it cost, and the
 * frame meter has no per-frame attribution - so nothing in a paste could tie a
 * decode to the 33 ms frame a tap walk measured. These cases pin the two lines
 * that close it: `decode:` (how long, over how many frame budgets, and how many
 * finished inside a metered gesture) and `asked at:` (what the surfaces asked
 * the loader for, hits included - the warm's own audit).
 *
 * `decode:` is the DECODER's time only (0.9.399). It used to be the request's
 * end-to-end latency, which is why the 2026-11-19 walk printed `n=514 ge16=514
 * ge33=514` - every load of that session took a network or disk round trip, and
 * nothing in the line said the decoding itself was fine. The wait it used to
 * report is still printed, under `request:`, because it answers the other half
 * of "why did that card take a moment".
 */
class ImageLoadTraceDecodeTimingTest {

    @Before
    fun clean() {
        ImageLoadTrace.reset()
    }

    private fun line(prefix: String): String? =
        ImageLoadTrace.lines().firstOrNull { it.startsWith(prefix) }

    @Test
    fun `decode durations are bucketed against the frame budgets`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236, decodeMs = 5)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236, decodeMs = 16)
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.NETWORK, 1280, 720, decodeMs = 40)

        assertEquals(
            "one 16 ms decode lost a budget, one 40 ms decode lost two, and the " +
                "slowest is the number a fix has to bring down",
            "decode: n=3 ge16=2 ge33=1 max=40ms duringFrame=0",
            line("decode:")
        )
    }

    @Test
    fun `a memory hit is not a timed decode`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.MEMORY, 420, 236, decodeMs = 2)

        assertEquals(
            "there is no decode to time on a hit, and counting its latency would " +
                "mix the frame's own work into the decode number",
            null,
            line("decode:")
        )
    }

    @Test
    fun `decodes that finish during a metered gesture are counted`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.DISK,
            420,
            236,
            decodeMs = 34,
            duringFrame = true
        )
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236, decodeMs = 7)

        assertEquals(
            "the correlation the paste needs: one of the two landed inside the walk",
            "decode: n=2 ge16=1 ge33=1 max=34ms duringFrame=1",
            line("decode:")
        )
    }

    @Test
    fun `an untimed session prints no decode line`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236)

        assertEquals(
            "a caller that did not time its requests must not read as 0 ms decodes",
            null,
            line("decode:")
        )
        assertEquals(
            "nor as a 0 ms request",
            null,
            line("request:")
        )
    }

    @Test
    fun `the wait and the decode are two lines, because they are two problems`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.NETWORK,
            1280,
            720,
            durationMs = 340,
            decodeMs = 12
        )

        assertEquals(
            "an original backdrop over the wire: 340 ms of request, 12 ms of decoding - " +
                "the reading `decode: max=340ms` would have sent a fix at the decoder",
            "decode: n=1 ge16=0 ge33=0 max=12ms duringFrame=0",
            line("decode:")
        )
        assertEquals(
            "and the wait keeps its own breakdown",
            "request: n=1 ge16=1 ge33=1 max=340ms",
            line("request:")
        )
    }

    @Test
    fun `asked sizes include memory hits, because a hit is a warm that worked`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.MEMORY,
            424,
            292,
            askedWidth = 424,
            askedHeight = 292
        )
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.DISK,
            424,
            292,
            askedWidth = 424,
            askedHeight = 292
        )

        assertEquals(
            "the hit and the decode asked for the same entry - and that entry is " +
                "the size the card draws, not the size a warm guessed",
            "asked at: 424x292 x2",
            line("asked at:")
        )
    }

    @Test
    fun `an unresolved ask is skipped, not counted as zero`() {
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236)

        assertEquals(
            "Coil leaves a dimension undefined when nothing requested a size; " +
                "that is not a 0x0 ask",
            null,
            line("asked at:")
        )
    }

    @Test
    fun `the asked list is capped like the decoded one`() {
        (1..10).forEach { n ->
            ImageLoadTrace.noteImage(
                ImageLoadTrace.Source.DISK,
                n * 10,
                n * 10,
                askedWidth = n * 10,
                askedHeight = n * 10
            )
        }

        val asked = line("asked at:") ?: error("no asked line")
        assertEquals(
            "six sizes, newest kept",
            6,
            asked.removePrefix("asked at: ").split(", ").size
        )
        assertTrue("the first four were evicted", !asked.contains("10x10"))
        assertTrue(asked.contains("100x100"))
    }

    @Test
    fun `reset clears the cost half too`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.DISK,
            420,
            236,
            durationMs = 20,
            decodeMs = 9,
            askedWidth = 424,
            askedHeight = 292,
            duringFrame = true
        )
        ImageLoadTrace.reset()

        assertEquals(
            "a reset that left the timing behind would report the previous session's cost",
            emptyList<String>(),
            ImageLoadTrace.lines()
        )
    }
}
