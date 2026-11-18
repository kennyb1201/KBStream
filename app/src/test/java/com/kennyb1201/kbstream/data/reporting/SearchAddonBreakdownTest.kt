package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `search:` line's add-on breakdown (spec 2026-11-17, search addon timeout
 * F4).
 *
 * The wave is parallel, so its total is the slowest SINGLE request in it: a 31s
 * paste could only be attributed by guessing which add-on held it. These pin
 * what the next paste must be able to read — who was slowest, and whether the
 * 8s cap cut anything — without pinning the formatter to one add-on list.
 */
class SearchAddonBreakdownTest {

    @Test
    fun `no add-ons means no breakdown, like the absent add-on leg`() {
        assertNull(searchAddonBreakdown(emptyList()))
    }

    @Test
    fun `the slowest add-on is named first and a cut one is marked`() {
        val line = searchAddonBreakdown(
            listOf(
                SearchAddonTiming("Cinemeta", ms = 412L),
                SearchAddonTiming("Penguplay", ms = 8_001L, timedOut = 1),
                SearchAddonTiming("AIOMetadata", ms = 1_190L)
            )
        )
        assertEquals(
            "slowest first, a '*' on the one the cap cut, the wave's cut count last",
            "addons=Penguplay:8001ms*,AIOMetadata:1190ms,Cinemeta:412ms timeouts=1",
            line
        )
    }

    @Test
    fun `a long wave names three and counts the rest`() {
        val line = searchAddonBreakdown(
            listOf(
                SearchAddonTiming("a", ms = 50L),
                SearchAddonTiming("b", ms = 40L, timedOut = 2),
                SearchAddonTiming("c", ms = 30L),
                SearchAddonTiming("d", ms = 20L),
                SearchAddonTiming("e", ms = 10L)
            )
        )
        assertEquals("addons=a:50ms,b:40ms*,c:30ms(+2) timeouts=2", line)
    }

    @Test
    fun `equal times keep install order, so two pastes read the same`() {
        val line = searchAddonBreakdown(
            listOf(
                SearchAddonTiming("first", ms = 900L),
                SearchAddonTiming("second", ms = 900L)
            )
        )
        assertEquals("addons=first:900ms,second:900ms timeouts=0", line)
    }

    @Test
    fun `a manifest name cannot break the key-value line it is printed in`() {
        val line = searchAddonBreakdown(
            listOf(SearchAddonTiming("Kenny's Addon, =1 *", ms = 8_000L))
        )
        assertEquals(
            "spaces, commas, '=' and '*' become one '_' run; the apostrophe is harmless",
            "addons=Kenny's_Addon_1:8000ms timeouts=0",
            line
        )
        assertTrue("and the field stays one token", line!!.split(" ").size == 2)
    }
}
