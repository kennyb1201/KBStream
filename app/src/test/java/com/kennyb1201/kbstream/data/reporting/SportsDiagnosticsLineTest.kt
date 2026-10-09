package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sports line in the one-tap diagnostics report.
 *
 * The hub's matching pass already prints its four stages to logcat under
 * `SPORTS PERF`, and logcat is exactly what a viewer on a couch cannot reach -
 * so the same figures have to travel with the report that gets copied to the
 * clipboard. These cases pin the two halves that make that useful: the line is
 * absent until the hub has actually run (a clean report keeps its length, and no
 * "sports: 0 cards" noise appears), and when it does appear it reads as the
 * spec asks - counts first, then each stage's milliseconds.
 *
 * [PerfTrace] is a process-wide object shared with the other tests in this JVM,
 * so each case resets it and records exactly the samples it asserts on.
 */
class SportsDiagnosticsLineTest {

    @Test
    fun `a session that never opened Sports has no sports line`() {
        PerfTrace.reset()

        assertNull(Diagnostics.sportsLine())
    }

    @Test
    fun `the hub's pass reads as counts, then each stage`() {
        PerfTrace.reset()
        PerfTrace.record("sports.match.channels", 1_200L)
        PerfTrace.record("sports.match.guide_index", 38_000L)
        PerfTrace.record("sports.match.epg_query", 4_200L)
        PerfTrace.record("sports.match.match_loop", 300L)
        PerfTrace.recordCount("sports.match.cards", 12L)
        PerfTrace.recordCount("sports.match.matched", 8L)
        PerfTrace.recordCount("sports.match.programs", 340L)

        assertEquals(
            "sports: 12 cards, 8 matched, 340 programs, channels 1200ms, " +
                "guide_index 38000ms, epg_query 4200ms, match_loop 300ms",
            Diagnostics.sportsLine()
        )
    }

    @Test
    fun `a stage that never ran is a dash rather than a zero`() {
        PerfTrace.reset()
        PerfTrace.recordCount("sports.match.cards", 3L)
        PerfTrace.recordCount("sports.match.matched", 2L)
        PerfTrace.recordCount("sports.match.programs", 5L)

        val line = Diagnostics.sportsLine()!!
        assertTrue("the counts are there", line.startsWith("sports: 3 cards, 2 matched, 5 programs,"))
        assertTrue(
            "and a missing timing says so instead of claiming 0ms, which would read as instant",
            line.contains("channels \u2014") && line.contains("match_loop \u2014")
        )
    }

    @Test
    fun `the newest pass is what the line reports`() {
        PerfTrace.reset()
        PerfTrace.recordCount("sports.match.cards", 4L)
        PerfTrace.recordCount("sports.match.cards", 9L)

        assertTrue(
            "a hub that has run twice reports the pass the viewer just saw",
            Diagnostics.sportsLine()!!.startsWith("sports: 9 cards,")
        )
    }

    @Test
    fun `counts cannot pose as slow durations in the perf summary`() {
        PerfTrace.reset()
        PerfTrace.recordCount("sports.match.programs", 5_000L)
        PerfTrace.record("sports.match.channels", 7_500L)

        val summary = PerfTrace.summary()
        assertTrue(
            "the real stage is ranked where it belongs",
            summary.contains("sports.match.channels")
        )
        assertFalse(
            "and a 5000-strong program count is not read as a 5s stall",
            summary.contains("sports.match.programs")
        )
        assertTrue(
            "while the report line still reads the count",
            Diagnostics.sportsLine()!!.contains("5000 programs")
        )
    }

    @Test
    fun `the line carries no lineup or game detail`() {
        // A report gets pasted into public issue threads, so the sports line is
        // counts and timings by construction: this pins that the function reads
        // nothing else out of the ring (no channel name, no team, no title).
        val source = java.io.File(findSourceRoot(), DIAGNOSTICS).readText()
        val start = source.indexOf("internal fun sportsLine(): String? {")
        assertTrue("sportsLine is in Diagnostics", start >= 0)
        val end = source.indexOf("private fun artLine()", start)
        assertTrue("artLine must follow sportsLine", end > start)
        val body = source.substring(start, end)
        assertTrue("it reads the recorded labels", body.contains("PerfTrace.latestByPrefix"))
        listOf(".name", ".title", ".venue", "displayName").forEach { leak ->
            assertFalse("the sports line must not report $leak", body.contains(leak))
        }
    }

    private fun findSourceRoot(): java.io.File {
        val prefixes = listOf("", "app/")
        var dir: java.io.File? =
            java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = java.io.File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
