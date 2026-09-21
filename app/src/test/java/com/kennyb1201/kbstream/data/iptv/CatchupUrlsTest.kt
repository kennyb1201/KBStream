package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch-up URLs are built from provider templates as opaque strings, so a wrong
 * substitution produces a URL that looks fine and plays nothing — or worse,
 * plays a different broadcast. The tokens below are the de-facto provider set;
 * everything else must be declined rather than half-substituted.
 *
 * Reference program: 2026-09-21T12:00:00Z → 13:30Z, so start = 1789992000,
 * end = 1789997400 epoch seconds, and `now` is pinned so the relative tokens
 * are deterministic.
 */
class CatchupUrlsTest {

    private val start = 1_789_992_000_000L
    private val end = 1_789_997_400_000L
    private val now = 1_790_000_000_000L

    private fun build(template: String) =
        CatchupUrls.build(template, start, end, now)

    @Test
    fun `start tokens become the program start in epoch seconds`() {
        for (token in listOf("\${start}", "\${timestamp}", "{utc}", "\${b}")) {
            assertEquals(
                "token=$token",
                "http://host/dvr/1789992000.ts",
                build("http://host/dvr/$token.ts")
            )
        }
    }

    @Test
    fun `end tokens become the program end in epoch seconds`() {
        for (token in listOf("\${end}", "{utcend}", "\${e}")) {
            assertEquals(
                "token=$token",
                "http://host/dvr/1789997400.ts",
                build("http://host/dvr/$token.ts")
            )
        }
    }

    @Test
    fun `a real xtream timeshift template substitutes every token`() {
        val url = build(
            "http://host/timeshift/user/pass/{utc}/{utcend}/\${start}/\${end}.ts"
        )

        assertEquals(
            "http://host/timeshift/user/pass/1789992000/1789997400/1789992000/1789997400.ts",
            url
        )
    }

    @Test
    fun `offset tokens are relative to now, forwards and backwards`() {
        assertEquals("http://h/1790000000.ts", build("http://h/\${offset:0}.ts"))
        assertEquals("http://h/1789999400.ts", build("http://h/\${offset:-600}.ts"))
        assertEquals("http://h/1790000300.ts", build("http://h/\${offset:300}.ts"))
    }

    @Test
    fun `the (b) family means now minus the given seconds`() {
        assertEquals("http://h/1789999400.ts", build("http://h/(b)600.ts"))
        assertEquals("http://h/1790000000.ts", build("http://h/(b)0.ts"))
    }

    @Test
    fun `date pieces come from the start time in UTC`() {
        assertEquals(
            "http://h/2026/09/21/12/00/00.ts",
            build("http://h/\${yyyy}/\${mm}/\${dd}/\${hh}/\${MM}/\${ss}.ts")
        )
    }

    @Test
    fun `date pieces are zero padded and read as UTC, not local time`() {
        // 2026-01-05T04:05:06Z — every piece needs padding, and a local-time
        // reader would shift the hour/day depending on the device's zone.
        val earlyStart = 1_767_585_906_000L
        val url = CatchupUrls.build(
            "http://h/\${yyyy}\${mm}\${dd}\${hh}\${MM}\${ss}.ts",
            earlyStart,
            earlyStart + 60_000L,
            now
        )

        assertEquals("http://h/20260105040506.ts", url)
    }

    @Test
    fun `a template with no recognized token is declined`() {
        // Substituting nothing would point somewhere that is not this broadcast.
        assertNull(build("http://host/static/index.ts"))
        assertNull(build(""))
        assertNull(build("   "))
    }

    @Test
    fun `a template with an unsupported token is declined rather than half built`() {
        // {duration} and {lutc} are Xtream tokens outside the supported set.
        // Building "http://h/1789992000/{duration}/x.ts" would look like a real
        // URL and serve nothing, so the whole template must be refused.
        assertNull(build("http://h/\${start}/{duration}/x.ts"))
        assertNull(build("http://h/{lutc}/{utcend}.ts"))
        assertNull(build("http://h/\${start}/\${Y}\${m}\${d}.ts"))
    }

    @Test
    fun `a literal template is declined because it cannot address a broadcast`() {
        // No token at all means the provider offers the SAME url for every past
        // programme — in practice the live stream. Serving that as catch-up
        // would play the current broadcast under an old programme's title.
        assertNull(build("http://h/1789992000.ts"))
    }

    @Test
    fun `advertised dvr days are parsed defensively`() {
        assertEquals(7, CatchupUrls.daysSupported("7"))
        assertEquals(7, CatchupUrls.daysSupported(" 7 "))
        assertEquals(0, CatchupUrls.daysSupported(null))
        assertEquals(0, CatchupUrls.daysSupported(""))
        assertEquals(0, CatchupUrls.daysSupported("seven"))
        assertEquals(0, CatchupUrls.daysSupported("-3"))
    }

    @Test
    fun `the dvr window admits programs inside it and rejects older ones`() {
        val day = 86_400_000L

        // No advertised window: let the provider decide rather than hide entries.
        assertTrue(CatchupUrls.withinDvrWindow(null, now - 30 * day, now))
        assertTrue(CatchupUrls.withinDvrWindow("0", now - 30 * day, now))

        assertTrue(CatchupUrls.withinDvrWindow("7", now - 6 * day, now))
        assertTrue(CatchupUrls.withinDvrWindow("7", now - 7 * day, now))
        assertFalse(CatchupUrls.withinDvrWindow("7", now - 7 * day - 1L, now))
        assertFalse(CatchupUrls.withinDvrWindow("1", now - 2 * day, now))
    }
}
