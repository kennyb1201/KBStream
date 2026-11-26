package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The churn counter's arithmetic and its line, in isolation.
 *
 * Same JVM harness the other pure rules use: what is being checked is what a
 * paste will print, not Compose.
 */
class ComposeChurnTest {

    @Before
    fun reset() {
        ComposeChurn.reset()
    }

    @Test
    fun `note counts every execution of a scope`() {
        repeat(3) { ComposeChurn.note("poster") }
        ComposeChurn.note("billboard")
        assertEquals(mapOf("poster" to 3L, "billboard" to 1L), ComposeChurn.snapshot())
    }

    @Test
    fun `since reports only what ran after the snapshot`() {
        ComposeChurn.note("poster")
        val before = ComposeChurn.snapshot()
        repeat(4) { ComposeChurn.note("poster") }
        ComposeChurn.note("home")
        assertEquals(
            mapOf("poster" to 4L, "home" to 1L),
            ComposeChurn.since(before)
        )
    }

    @Test
    fun `since leaves out scopes that did not run`() {
        ComposeChurn.note("poster")
        ComposeChurn.note("billboard")
        val before = ComposeChurn.snapshot()
        ComposeChurn.note("billboard")
        val delta = ComposeChurn.since(before)
        assertEquals(mapOf("billboard" to 1L), delta)
        assertTrue("a zero would only lengthen the line", !delta.containsKey("poster"))
    }

    @Test
    fun `a scope counted for the first time is counted in full`() {
        val before = ComposeChurn.snapshot()
        repeat(2) { ComposeChurn.note("landscape") }
        assertEquals(mapOf("landscape" to 2L), ComposeChurn.since(before))
    }

    @Test
    fun `the suffix names the busiest scopes first and ties by name`() {
        val line = ComposeChurn.suffix(
            mapOf(
                "poster" to 48L,
                "kbcard" to 48L,
                "spotlight.tint" to 5L,
                "home" to 1L
            )
        )
        assertEquals(" churn kbcard=48 poster=48 spotlight.tint=5 home=1", line)
    }

    @Test
    fun `nothing ran means no suffix at all`() {
        assertEquals("", ComposeChurn.suffix(emptyMap()))
    }

    @Test
    fun `the line stops at eight scopes and says how many it left out`() {
        val delta = (1..11).associate { "scope$it" to it.toLong() }
        val line = ComposeChurn.suffix(delta)
        assertTrue("eleven scopes cannot fit one paste line: $line", line.endsWith(" +3"))
        assertEquals("the eight busiest are the ones named", 8, line
            .substringBefore(" +3")
            .removePrefix(" churn ")
            .split(" ")
            .size)
        assertTrue("scope1 is the least busy and must not be named", !line.contains("scope1="))
    }

    @Test
    fun `reset forgets the counts`() {
        ComposeChurn.note("poster")
        ComposeChurn.reset()
        assertTrue(ComposeChurn.snapshot().isEmpty())
    }
}
