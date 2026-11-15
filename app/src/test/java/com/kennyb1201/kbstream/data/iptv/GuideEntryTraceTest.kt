package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.reporting.GuideTrace
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide entry measurement (spec 2026-11-15, guide grid entry F1).
 *
 * The entry was UNMEASURED - no `guide.*` label existed anywhere - so "the guide
 * is slow to open" had no answer at all, and the 102-120 MB database size was
 * the only thing anyone could point at. This pins what the number means: the
 * screen composing to the first window answer that carries PROGRAMS (an empty
 * answer is a channel with no EPG here, not a drawn row), once per entry, with
 * the counts and the two facts the spec asks the report to state - the entry
 * query's scope and where the guide match runs.
 *
 * [GuideEntryTrace] takes its clock as a parameter, so every branch is reachable
 * without a device.
 */
class GuideEntryTraceTest {

    @Test
    fun `the entry leg is measured to the first row with programs in it`() {
        GuideTrace.reset()
        PerfTrace.reset()
        GuideEntryTrace.markEntry(atMs = 1_000L)

        GuideEntryTrace.onFirstRows(channels = 12, programs = 37, atMs = 2_450L)

        assertEquals(
            "screen composed to first drawn row",
            1_450L,
            PerfTrace.latestByPrefix("guide.entry").firstOrNull()?.second
        )
        val line = GuideTrace.lines().single()
        assertTrue("named for the spec's label", line.startsWith("guide.entry first-row=1450ms"))
        assertTrue("with what it built", line.contains("channels=12") && line.contains("programs=37"))
        assertTrue(
            "and the entry query's scope, so the next paste cannot be read as " +
                "'it loads the whole database' by assumption",
            line.contains("scope=window")
        )
        assertTrue(
            "and where the match ran: on entry, from the cached table first",
            line.contains("match=entry-cached")
        )
    }

    @Test
    fun `an empty answer does not close the leg`() {
        GuideTrace.reset()
        PerfTrace.reset()
        GuideEntryTrace.markEntry(atMs = 1_000L)

        // A channel with no EPG in this window answers successfully and empty.
        // Counting it would report a fast entry for a grid that is still blank.
        GuideEntryTrace.onFirstRows(channels = 12, programs = 0, atMs = 1_200L)
        assertTrue(PerfTrace.latestByPrefix("guide.entry").isEmpty())
        assertTrue(GuideTrace.lines().isEmpty())

        GuideEntryTrace.onFirstRows(channels = 12, programs = 9, atMs = 4_000L)
        assertEquals(
            "the leg ends at the answer that actually draws rows",
            3_000L,
            PerfTrace.latestByPrefix("guide.entry").firstOrNull()?.second
        )
    }

    @Test
    fun `one entry is measured once`() {
        GuideTrace.reset()
        PerfTrace.reset()
        GuideEntryTrace.markEntry(atMs = 1_000L)
        GuideEntryTrace.onFirstRows(channels = 12, programs = 5, atMs = 2_000L)
        // A scroll later answers more windows; those are not the entry.
        GuideEntryTrace.onFirstRows(channels = 12, programs = 40, atMs = 60_000L)

        assertEquals(
            "the scroll must not overwrite the entry figure",
            1_000L,
            PerfTrace.latestByPrefix("guide.entry").firstOrNull()?.second
        )
        assertEquals(1, GuideTrace.lines().size)
    }

    @Test
    fun `a second open is measured again`() {
        GuideTrace.reset()
        PerfTrace.reset()
        GuideEntryTrace.markEntry(atMs = 0L + 10L)
        GuideEntryTrace.onFirstRows(channels = 3, programs = 4, atMs = 310L)
        GuideEntryTrace.markEntry(atMs = 5_000L)
        GuideEntryTrace.onFirstRows(channels = 3, programs = 4, atMs = 5_600L)

        assertEquals(
            "re-arming is what makes S1 comparable across opens",
            600L,
            PerfTrace.latestByPrefix("guide.entry").firstOrNull()?.second
        )
    }

    @Test
    fun `nothing is recorded before the guide is opened`() {
        GuideTrace.reset()
        PerfTrace.reset()
        GuideEntryTrace.onFirstRows(channels = 4, programs = 4, atMs = 900L)
        assertTrue(PerfTrace.latestByPrefix("guide.entry").isEmpty())
        assertFalse(GuideTrace.lines().any { it.startsWith("guide.entry") })
    }

    // ── The verified entry shape, pinned so it cannot drift silently ──────

    @Test
    fun `the entry query is window-scoped and the grid asks only for what it draws`() {
        val repository = read("data/iptv/IptvRepository.kt")
        val grid = read("ui/iptv/GuideGridView.kt")
        assertTrue(
            "programs are read for the channels asked and the window drawn - " +
                "not the whole database",
            repository.contains("getProgramsForChannelsInWindow(") &&
                repository.contains("windowStart = windowStart") &&
                repository.contains("windowEnd = windowEnd")
        )
        assertTrue(
            "the grid requests the visible rows plus a page of lookahead",
            grid.contains("GuideGrid.LOOKAHEAD_ROWS") &&
                read("ui/iptv/GuideGridRules.kt").contains("const val LOOKAHEAD_ROWS = 12")
        )
        assertTrue(
            "and one request is bounded in channels and chunked in queries",
            read("ui/iptv/IptvViewModel.kt").contains("MAX_GUIDE_CHANNELS_PER_REQUEST = 80") &&
                repository.contains("PROGRAM_QUERY_BATCH_SIZE = 8")
        )
    }

    @Test
    fun `both screens mark the entry and only a successful window answer closes it`() {
        assertTrue(
            "the television guide",
            read("ui/iptv/GuideScreen.kt").contains("GuideEntryTrace.markEntry()")
        )
        assertTrue(
            "and the phone's",
            read("ui/mobile/GuideScreenMobile.kt").contains("GuideEntryTrace.markEntry()")
        )
        val viewModel = read("ui/iptv/IptvViewModel.kt")
        val success = viewModel.substringAfter("result.onSuccess { loaded ->")
        assertTrue(
            "the leg is closed on SUCCESS: a failed read is a blank row, not an " +
                "entry that finished",
            success.substringBefore("result.onFailure").contains("GuideEntryTrace.onFirstRows(")
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
