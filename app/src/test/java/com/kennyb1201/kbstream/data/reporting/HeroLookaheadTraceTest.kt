package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lookahead's hit rate (spec 2026-11-17, F5).
 *
 * The walk's proof cannot be the number of warms: a warm the viewer never
 * reaches decodes a bitmap for nothing, and `heroWarms` would still look
 * healthy. What has to be readable is whether the hero's DRAWS landed on a
 * bitmap that was already in the memory cache - the hit rate - against the
 * focus arrivals the viewer actually made.
 */
class HeroLookaheadTraceTest {

    @Before
    fun clean() {
        ImageLoadTrace.reset()
    }

    @Test
    fun `an arrival is reported even before the hero draws`() {
        ImageLoadTrace.noteHeroFocusArrival()

        val line = ImageLoadTrace.lines().single()
        assertEquals(
            "the warm fired, so the paste says so - a 0/0 rate is honest, not absent",
            "hero: lookahead arrivals=1 hits=0 misses=0 rate=0%",
            line
        )
    }

    @Test
    fun `a memory-cache draw is a hit and a fresh decode is a miss`() {
        ImageLoadTrace.noteHeroFocusArrival()
        ImageLoadTrace.noteHeroDraw(memoryCached = true)
        ImageLoadTrace.noteHeroFocusArrival()
        ImageLoadTrace.noteHeroDraw(memoryCached = false)

        val line = ImageLoadTrace.lines().first { it.startsWith("hero: lookahead") }
        assertTrue(line, line.contains("arrivals=2"))
        assertTrue(line, line.contains("hits=1"))
        assertTrue(line, line.contains("misses=1"))
        assertTrue(line, line.contains("rate=50%"))
    }

    @Test
    fun `the rate is integer percent, so a 2-of-3 walk reads 66`() {
        repeat(3) {
            ImageLoadTrace.noteHeroFocusArrival()
            ImageLoadTrace.noteHeroDraw(memoryCached = it < 2)
        }

        val line = ImageLoadTrace.lines().first { it.startsWith("hero: lookahead") }
        assertTrue(
            "truncated, not rounded: the number never overstates the warm",
            line.contains("rate=66%")
        )
    }

    @Test
    fun `draws still appear in the art line beside it`() {
        ImageLoadTrace.noteHeroFocusArrival()
        ImageLoadTrace.noteHeroDraw(memoryCached = true)

        val art = ImageLoadTrace.lines().first { it.startsWith("art:") }
        assertEquals(1, Regex("heroDraws=1").findAll(art).count())
        assertTrue(
            "the existing draw counter is unchanged - the new line splits it, it " +
                "does not replace it",
            art.contains("heroDraws=1 heroWarms=0 tileWarms=0")
        )
    }

    @Test
    fun `reset clears the lookahead's counters too`() {
        ImageLoadTrace.noteHeroFocusArrival()
        ImageLoadTrace.noteHeroDraw(memoryCached = true)
        ImageLoadTrace.reset()
        assertEquals(emptyList<String>(), ImageLoadTrace.lines())
    }
}
