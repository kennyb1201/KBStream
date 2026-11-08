package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone grid's empty rows (spec T1, T2, T4).
 *
 * Three symptoms, two causes: the view only ever asked for the first 24 rows and
 * never asked again, and a read that finished with nothing left no trace - so a
 * channel whose guide does not exist was indistinguishable from one nobody had
 * asked for, and both said "Loading guide…" forever.
 *
 * The first half is behaviour: [mergeWindowAnswer] is what leaves the trace and
 * [guideRowGuideState] is what reads it. The second half is wiring, read from the
 * sources the way this module's other guide tests do it (no Compose harness on
 * the JVM); T3's scroll itself needs a device and is not claimed here.
 */
class GuideGridEmptyRowsContractTest {

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private fun program(start: Long, channel: String = "ch1") = GridProgram(
        channelKey = channel,
        title = "Program",
        description = null,
        startUtcMillis = start,
        endUtcMillis = start + 30 * minute,
    )

    private fun row(start: Long, channel: String = "ch1") = EpgProgramRow(
        channelId = channel,
        title = "Program",
        description = null,
        category = null,
        startUtcMillis = start,
        endUtcMillis = start + 30 * minute,
    )

    // ── T1: a finished-empty read leaves an entry ─────────────────────────

    @Test
    fun `a read that answers nothing still leaves an entry for the channel`() {
        val start = 1_700_000_000_000L
        val merged = mergeWindowAnswer(
            existing = emptyMap(),
            requestedIds = setOf("tsn1", "teamarr-event-1"),
            loaded = mapOf("tsn1" to listOf(row(start, "tsn1"))),
        )

        assertEquals("the channel that answered keeps its rows", 1, merged["tsn1"]?.size)
        assertTrue(
            "the channel that answered nothing is present, explicitly empty",
            merged.containsKey("teamarr-event-1") && merged["teamarr-event-1"]!!.isEmpty()
        )
    }

    @Test
    fun `a channel nobody read stays absent, which is the only meaning of never read`() {
        val merged = mergeWindowAnswer(
            existing = emptyMap(),
            requestedIds = setOf("tsn1"),
            loaded = mapOf("tsn1" to emptyList()),
        )

        assertNull(
            "absence must mean never read, so an unasked channel has no entry",
            merged["tsn6"]
        )
        assertTrue("and the asked one does have one", merged.containsKey("tsn1"))
    }

    @Test
    fun `an empty answer never wipes what a previous window already read`() {
        val start = 1_700_000_000_000L
        val existing = mapOf("ch1" to listOf(program(start, "ch1")))
        val merged = mergeWindowAnswer(
            existing = existing,
            requestedIds = setOf("ch1"),
            loaded = emptyMap(),
        )

        assertEquals(
            "a later empty window merges like any other read (F2b)",
            existing.getValue("ch1"),
            merged["ch1"]
        )
    }

    @Test
    fun `the merge is keyed per channel and keeps both sides of an overlapping read`() {
        val start = 1_700_000_000_000L
        val merged = mergeWindowAnswer(
            existing = mapOf("ch1" to listOf(program(start, "ch1"))),
            requestedIds = setOf("ch1"),
            loaded = mapOf("ch1" to listOf(row(start + hour, "ch1"))),
        )

        assertEquals(
            "the already-read program and the new one both survive",
            listOf(start, start + hour),
            merged.getValue("ch1").map { it.startUtcMillis }
        )
    }

    @Test
    fun `a read with nothing requested and nothing answered changes nothing`() {
        val existing = mapOf("ch1" to listOf(program(1_700_000_000_000L)))
        assertTrue(
            "an empty request must not rebuild the map",
            mergeWindowAnswer(existing, emptySet(), emptyMap()) === existing
        )
    }

    @Test
    fun `the window read writes its answer through the merge that leaves the trace`() {
        val viewModel = viewModel()
        val request = viewModel.substring(
            viewModel.indexOf("fun requestGuideGridWindow("),
            viewModel.indexOf("private fun gridWindowKey(")
        )
        assertTrue(
            "the completion merges the whole requested set, not just the channels that answered",
            request.contains("mergeWindowAnswer(") &&
                request.contains("requestedIds = missingIds")
        )
        assertFalse(
            "and no longer merges only the loaded map",
            request.contains("loaded.forEach { (channelId, rows) ->")
        )
        assertTrue(
            "a guide import still drops the entries with the reads they stand for",
            viewModel.contains("private fun clearGuideGrid() {") &&
                viewModel.contains("_gridPrograms.value = emptyMap()")
        )
    }

    // ── T2: never read, finished-empty, and answered are three states ─────

    @Test
    fun `a never-read unmatched channel is loading`() {
        assertEquals(
            GuideRowGuideState.LOADING,
            guideRowGuideState(
                channelId = "teamarr-event-1",
                resolvedGuideIds = emptySet(),
                programsByChannel = emptyMap(),
                loadingIds = setOf("teamarr-event-1"),
            )
        )
        assertEquals(
            "a channel not yet asked for is still waiting, not a result",
            GuideRowGuideState.LOADING,
            guideRowGuideState("teamarr-event-1", emptySet(), emptyMap(), emptySet())
        )
    }

    @Test
    fun `a finished-empty unmatched channel has no guide data`() {
        assertEquals(
            GuideRowGuideState.NO_GUIDE_DATA,
            guideRowGuideState(
                channelId = "teamarr-event-1",
                resolvedGuideIds = emptySet(),
                programsByChannel = mapOf("teamarr-event-1" to emptyList()),
                loadingIds = emptySet(),
            )
        )
    }

    @Test
    fun `a matched channel with an empty window is never labelled no-data`() {
        assertEquals(
            "F3d: the guide matched, the feed just lacks this window",
            GuideRowGuideState.ANSWERED,
            guideRowGuideState(
                channelId = "tsn2",
                resolvedGuideIds = setOf("tsn2"),
                programsByChannel = mapOf("tsn2" to emptyList()),
                loadingIds = emptySet(),
            )
        )
    }

    @Test
    fun `a still-loading row is never labelled no-data`() {
        assertEquals(
            "F3c: an empty entry for an older window is stale while a read is out",
            GuideRowGuideState.LOADING,
            guideRowGuideState(
                channelId = "teamarr-event-1",
                resolvedGuideIds = emptySet(),
                programsByChannel = mapOf("teamarr-event-1" to emptyList()),
                loadingIds = setOf("teamarr-event-1"),
            )
        )
    }

    @Test
    fun `a row that already holds programs is not sent back to loading`() {
        assertEquals(
            "the next window is a slide over data on screen, not a reload (F1c)",
            GuideRowGuideState.ANSWERED,
            guideRowGuideState(
                channelId = "ch1",
                resolvedGuideIds = emptySet(),
                programsByChannel = mapOf("ch1" to listOf(program(1_700_000_000_000L))),
                loadingIds = setOf("ch1"),
            )
        )
    }

    // ── T2/T4: the mobile row and the mobile request, read from the sources ─

    @Test
    fun `the mobile row asks the shared rule and prints its result`() {
        val grid = mobileGrid()
        assertTrue(
            "the row's guide line is the shared three-way state, not a second guess",
            grid.contains("guideState = guideRowGuideState(")
        )
        assertTrue(
            "a pending row still says Loading guide…",
            grid.contains("guideState == GuideRowGuideState.LOADING -> \"Loading guide…\"")
        )
        assertTrue(
            "a finished-empty row says it has no guide data",
            grid.contains("guideState == GuideRowGuideState.NO_GUIDE_DATA -> \"No guide data\"")
        )
        assertTrue(
            "the channel number still wins when the playlist carries one",
            grid.indexOf("number != null -> number") <
                grid.indexOf("guideState == GuideRowGuideState.LOADING")
        )
        assertFalse(
            "the old absence test cannot tell a finished read from an unasked one",
            grid.contains(".isNullOrEmpty()")
        )
    }

    @Test
    fun `the mobile request follows the viewport instead of a fixed first slice`() {
        val grid = mobileGrid()
        assertTrue(
            "the request is keyed on the row that reached the top",
            grid.contains("LaunchedEffect(rows, window, listState.firstVisibleItemIndex)")
        )
        assertTrue(
            "it asks for the visible page plus the same lookahead the TV grid keeps",
            grid.contains("val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)") &&
                grid.contains(
                    "val end = (first + visible + GuideGrid.LOOKAHEAD_ROWS).coerceAtMost(rows.size)"
                )
        )
        assertTrue(
            "and the slice is the rows from the top, so row 25 is asked for when it appears",
            grid.contains("rows.subList(first, end).map { it.first.channel.id }")
        )
        assertFalse(
            "the fixed first-page cap is gone",
            grid.contains("LOOKAHEAD_ROWS * 2")
        )
    }

    @Test
    fun `the phone screen hands the grid the loading set the state rule reads`() {
        val screen = mobileScreen()
        assertTrue(
            "the screen collects the ViewModel's in-flight ids",
            screen.contains("viewModel.gridLoadingChannelIds.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "and passes them to the grid, so a re-read is not reported as a result",
            screen.contains("loadingGuideIds = gridLoadingIds")
        )
    }

    @Test
    fun `the TV grid is untouched by the shared write`() {
        val tv = tvGrid()
        assertTrue(
            "TV still pages its own list on the first visible row",
            tv.contains("LaunchedEffect(rows, window, rowListState.firstVisibleItemIndex)") &&
                tv.contains("GuideGrid.LOOKAHEAD_ROWS).coerceAtMost(rows.size)")
        )
        // F2c/S5: TV deliberately reads an empty entry the same way it reads an
        // absent one - it has no "no guide data" result to show, and giving it one
        // is a TV decision, not a side effect of the phone's.
        assertTrue(
            "TV's pending test is unchanged: it treats empty and absent alike",
            tv.contains("programsByChannel[row.item.channel.id].isNullOrEmpty()")
        )
    }

    // ── sources ───────────────────────────────────────────────────────────

    private fun mobileGrid(): String =
        source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")

    private fun mobileScreen(): String =
        source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")

    private fun tvGrid(): String = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")

    private fun viewModel(): String = source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
