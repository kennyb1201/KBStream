package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home-load split, pinned out of the diagnostics ring (spec 2026-11-15, Home
 * load F1/S5).
 *
 * `home.railsReady = 8641ms` on a cold TCL is the headline number, and it decides
 * nothing by itself: firstRail, pinned, catalogRails, railsReady and refreshAll
 * have to be read together before any leg can be called the slow one. They were
 * at exactly the same risk as the startup phases - Home generates its own
 * artwork lookups after launch, the ring holds 400 samples, and the figures are
 * gone by the time Settings opens on a cold entry.
 *
 * [StartupPinTest] covers the mechanism; this covers the Home half of it, and
 * that pinning `home.*` did not become a licence to pin anything else.
 */
class HomeLoadSplitTest {

    @Test
    fun `the load split survives the ring filling`() {
        PerfTrace.reset()
        PerfTrace.record("home.railsReady", 8_641L)
        PerfTrace.record("home.firstRail", 1_210L)
        PerfTrace.record("home.pinned", 900L)
        PerfTrace.record("home.catalogRails", 7_100L)

        repeat(450) { index -> PerfTrace.record("home.artLookup$index", 40L) }

        assertTrue(
            "the ring evicted them, as it did on the Stick and the TCL",
            PerfTrace.latestByPrefix("home.railsReady").isEmpty()
        )
        val pinned = PerfTrace.pinnedHome().toMap()
        assertEquals(
            "but the split the report needs is still there",
            8_641L,
            pinned["home.railsReady"]
        )
        assertEquals(1_210L, pinned["home.firstRail"])
        assertEquals(900L, pinned["home.pinned"])
        assertEquals(7_100L, pinned["home.catalogRails"])
    }

    @Test
    fun `pinning home legs does not pin the playback or sports lines`() {
        PerfTrace.reset()
        PerfTrace.record("playback.first_frame", 700L)
        PerfTrace.record("sports.match.cards", 3L)
        PerfTrace.record("http.tmdb", 120L)
        PerfTrace.record("home.refreshAll", 9_000L)

        // The pin is per-process and reset() deliberately leaves it alone, so
        // this asserts the RULE (only `home.` labels) rather than the exact
        // contents, which other cases in this process have already contributed
        // to.
        val pinned = PerfTrace.pinnedHome().map { it.first }
        assertTrue(
            "only home legs join this pin",
            pinned.all { it.startsWith("home.") }
        )
        assertTrue(
            "and the leg this case recorded did join it",
            pinned.contains("home.refreshAll")
        )
        assertTrue(
            "the other prefixes keep reading the ring, unchanged - playback and " +
                "sports have the same exposure and their own spec",
            PerfTrace.latestByPrefix("playback.").firstOrNull()?.second == 700L
        )
    }

    @Test
    fun `the report prints the split, from the pin, with the ring as fallback`() {
        val diagnostics = read("data/reporting/Diagnostics.kt")
        assertTrue(
            "an explicit line, because the perf summary ranks by total and would " +
                "crowd the legs out",
            diagnostics.contains("\"homeload: \"") &&
                diagnostics.contains("label.removePrefix(\"home.\")")
        )
        assertTrue(
            "read from the pin first, ring second",
            diagnostics.contains("PerfTrace.pinnedHome().takeIf { it.isNotEmpty() }") &&
                diagnostics.contains("?: PerfTrace.latestByPrefix(\"home.\")")
        )
        assertTrue(
            "and the startup line keeps its own pin read - this spec adds a " +
                "second pin, it does not move the first",
            diagnostics.contains("PerfTrace.pinnedStartup()")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
