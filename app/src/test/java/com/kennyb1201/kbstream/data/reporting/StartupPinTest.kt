package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch phases, pinned out of the diagnostics ring (spec 2026-11-15).
 *
 * The paste that prompted this had no cold-start breakdown at all: build 323
 * carried `perf: samples=400` and no `startup:` line, because Home's 291 artwork
 * lookups had already evicted every phase before Settings could be opened. The
 * fix is a pin beside the ring, so what is pinned here is the pin's own rules -
 * that it survives the ring filling, that it covers `startup.*` ONLY (the
 * playback and sports lines read the ring and must keep reading it unchanged),
 * and that its last value per label is the one the report shows.
 *
 * [PerfTrace] is a process-wide object, so each case resets the ring first and
 * asserts on its own labels: [PerfTrace.reset] deliberately empties the ring and
 * NOT the pin, since the launch phases are facts about this process that
 * clearing the report cannot make untrue.
 */
class StartupPinTest {

    @Test
    fun `a launch phase survives the ring filling`() {
        PerfTrace.reset()
        PerfTrace.record("startup.survives", 121L)
        assertEquals(121L, pinnedValue("startup.survives"))

        // 450 samples is past the 400 cap: every one of the phase's ring entries
        // is gone, which is exactly the field state (samples=400, no line).
        repeat(450) { index -> PerfTrace.record("home.noise$index", 1L) }

        assertTrue(
            "the ring has evicted it, as it did on the Stick",
            PerfTrace.latestByPrefix("startup.survives").isEmpty()
        )
        assertEquals(
            "but the report still has the phase",
            121L,
            pinnedValue("startup.survives")
        )
    }

    @Test
    fun `only startup labels are pinned`() {
        PerfTrace.reset()
        PerfTrace.record("playback.first_frame", 900L)
        PerfTrace.record("sports.login", 42L)
        PerfTrace.record("http.tmdb", 310L)

        val pinned = PerfTrace.pinnedStartup().map { it.first }
        assertFalse(
            "playback, sports and http lines keep reading the ring - pinning them " +
                "would be a change in behaviour this spec explicitly forbids",
            pinned.any { it.startsWith("playback.") || it.startsWith("sports.") || it.startsWith("http.") }
        )
        assertEquals(
            "and those lines still read the ring",
            900L,
            PerfTrace.latestByPrefix("playback.").first().second
        )
    }

    @Test
    fun `the newest value per phase is the one the report shows`() {
        PerfTrace.reset()
        PerfTrace.record("startup.repeated", 300L)
        PerfTrace.record("startup.repeated", 118L)
        assertEquals(
            "a phase recorded twice (a re-created component) reports its latest",
            118L,
            pinnedValue("startup.repeated")
        )
        assertEquals(
            "and holds one entry, not two",
            1,
            PerfTrace.pinnedStartup().count { it.first == "startup.repeated" }
        )
    }

    @Test
    fun `the ring keeps its 400 cap and its own read`() {
        PerfTrace.reset()
        repeat(450) { PerfTrace.record("cap.probe", 2L) }
        assertEquals(
            "raising the cap was rejected - it is still 400",
            400,
            PerfTrace.count("cap.probe")
        )
        assertTrue(
            "and a startup label recorded among them is still in the ring while it " +
                "fits, so the pin adds nothing to what the ring already answered",
            run {
                PerfTrace.reset()
                PerfTrace.record("startup.stillInRing", 77L)
                PerfTrace.latestByPrefix("startup.stillInRing").firstOrNull()?.second == 77L
            }
        )
    }

    @Test
    fun `the report reads the pin first and falls back to the ring`() {
        // startupLine() builds a string out of live Android state, so its ORDER
        // is pinned from the source: the pin is read first, and the ring is the
        // fallback for a report taken before any phase has run.
        val diagnostics = read("data/reporting/Diagnostics.kt")
        val body = diagnostics.substringAfter("private fun startupLine(): String?")
            .substringBefore("return \"startup: \$breakdown\"")
        assertTrue(
            "the pin is the primary read",
            body.contains("PerfTrace.pinnedStartup()")
        )
        assertTrue(
            "and the ring survives as the early-launch fallback",
            body.contains("else PerfTrace.latestByPrefix(\"startup.\")")
        )
        assertTrue(
            "the printed format is unchanged",
            diagnostics.contains("\"startup: \$breakdown\"") &&
                diagnostics.contains("label.removePrefix(\"startup.\")")
        )
    }

    private fun pinnedValue(label: String): Long? =
        PerfTrace.pinnedStartup().firstOrNull { it.first == label }?.second

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
