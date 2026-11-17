package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrolling dig five (2026-11-17) — the fixes, pinned by source.
 *
 * One test per cause the dig ranked, because the harness has no Android runtime:
 * what is checkable here is that the shape the fix promised is the shape in the
 * tree. The numbers stay the device's job (a `frame:` line in COPY DIAGNOSTICS,
 * an MPV `time-pos` count), and this class does not pretend otherwise.
 */
class ScrollingDigFiveContractTest {

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }

    // ── §3a: the phone Guide's per-frame window and O(playlist) set build ──

    @Test
    fun `the guide window is sampled on a beat, not per layout change`() {
        val guide = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
        assertTrue(
            "the visible-row answer is rate-limited",
            guide.contains("GUIDE_VISIBLE_ROWS_MIN_INTERVAL_MS") &&
                guide.contains("now - lastSentAt < GUIDE_VISIBLE_ROWS_MIN_INTERVAL_MS")
        )
        assertTrue(
            "and an unchanged window is not re-requested at all",
            guide.contains("ids == lastSent")
        )
        assertFalse(
            "the old fire-on-every-emission shape is gone",
            guide.contains("if (ids.isNotEmpty()) viewModel.updateGuideChannels(ids)")
        )
    }

    @Test
    fun `the playlist id set is built once per playlist`() {
        val vm = source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
        assertTrue("cached by identity", vm.contains("knownChannelIdsOwner === playlist"))
        assertTrue(
            "and reused rather than rebuilt",
            vm.contains("knownChannelIdsCache") &&
                vm.contains("private var knownChannelIdsOwner: IptvPlaylist? = null")
        )
    }

    // ── §3b: the watched pass a page append used to run in full ──

    @Test
    fun `a page append coalesces the watched pass`() {
        val vm = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        assertTrue("the append asks for a coalesced pass", vm.contains("requestWatchedRefreshAfterPage()"))
        assertTrue(
            "coalescing is a queued flag plus a delay, not a second pass per page",
            vm.contains("watchedRefreshAfterPageQueued") &&
                vm.contains("WATCHED_REFRESH_AFTER_PAGE_MS")
        )
        assertTrue(
            "and the immediate path (resume) still refreshes in full",
            vm.contains("fun refreshWatchedStatusForCurrentRails()")
        )
    }

    // ── §3c: the two ungated billboard pre-warms ──

    @Test
    fun `both next-page pre-warms are gated`() {
        val home = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
        assertTrue(
            "one gate, named",
            home.contains("val nextPageWarm =") &&
                home.contains("trailerMode != BillboardTrailerMode.OFF") &&
                home.contains("!trailerSuppressed") &&
                home.contains("!backgrounded")
        )
        assertTrue(
            "and both effects ask it",
            home.contains("nextPageWarm") &&
                home.contains("nextPageTrailerKey, nextPageWarm") &&
                home.contains("nextPage?.tmdbId, nextPageWarm")
        )
    }

    // ── §3d: the keystroke blank ──

    @Test
    fun `the addon rails are not blanked in front of the debounce`() {
        val search = source("com/kennyb1201/kbstream/ui/search/SearchViewModel.kt")
        val debounceAt = search.indexOf("delay(SEARCH_DEBOUNCE_MS)")
        val clearAt = search.indexOf("_addonResultGroups.value = emptyList()", debounceAt)
        assertTrue("the debounce exists", debounceAt > 0)
        assertTrue(
            "the clear runs with the winning wave, after the debounce",
            clearAt > debounceAt
        )
        assertTrue(
            "an emptied field still clears its rails",
            search.contains("An emptied field has no rails to wait for")
        )
    }

    // ── §4b: MPV's ungated progress repaint ──

    @Test
    fun `mpv progress is floored and hidden chrome is spared`() {
        val view = source("com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt")
        assertTrue(
            "the widget owns a floor, whatever mpv emits",
            view.contains("PROGRESS_POST_MIN_INTERVAL_MS") &&
                view.contains("now - lastProgressPostAtMs < PROGRESS_POST_MIN_INTERVAL_MS")
        )
        val activity = source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        val progressAt = activity.indexOf("private fun onProgress(")
        val gateAt = activity.indexOf("if (chrome?.isVisible == true) {", progressAt)
        val refreshAt = activity.indexOf("chrome?.refreshProgress()", progressAt)
        assertTrue("the gate is inside onProgress", progressAt > 0 && gateAt > progressAt)
        assertTrue("and it guards the repaint", refreshAt > gateAt)
    }

    // ── §4e: the picker's full rebind ──

    @Test
    fun `the picker diffs instead of rebinding everything`() {
        val adapter = source("com/kennyb1201/kbstream/ui/player/PickerAdapter.kt")
        assertTrue("DiffUtil is used", adapter.contains("DiffUtil.calculateDiff("))
        assertTrue(
            "row identity is the label the row already shows",
            adapter.contains("old[oldPosition].label == newItems[newPosition].label")
        )
        val submit = adapter.substringAfter("fun submit(newItems: List<PickerItem>)")
            .substringBefore("\n    class ViewHolder")
        assertFalse(
            "and the submit no longer rebinds every visible row",
            submit.contains("notifyDataSetChanged()")
        )
    }

    // ── §3g / §3j: the phone grids ──

    @Test
    fun `the library tile does not scan every download record`() {
        val library = source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")
        assertTrue(
            "the offline keys are resolved once per download change",
            library.contains("val completedDownloadKeys = remember(downloads)") &&
                library.contains("record.state.isComplete")
        )
        assertTrue(
            "the tile tests membership, not the whole list",
            library.contains("candidates.any { key -> key in completedDownloadKeys }")
        )
        assertFalse(
            "and the per-tile scan is gone",
            library.contains("downloadsHaveAny(\n                                downloads,")
        )
    }

    @Test
    fun `the catalog grid does not fade tiles while it is moving`() {
        val catalog = source("com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt")
        assertTrue(
            "the fade is skipped during a scroll",
            catalog.contains("fadeInSpec = if (gridState.isScrollInProgress)") &&
                catalog.contains("null")
        )
    }

    // ── part 1: the instrument ──

    @Test
    fun `the scroll frame meter exists and reaches the paste`() {
        val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        assertTrue(
            "the report carries one line per scrolled surface",
            diagnostics.contains("UiFrameTrace.lines().forEach { report.appendLine(it) }")
        )
        val trace = source("com/kennyb1201/kbstream/data/reporting/UiFrameTrace.kt")
        assertTrue(
            "the sampler is a Choreographer frame callback",
            trace.contains("object : Choreographer.FrameCallback") &&
                trace.contains("postFrameCallback")
        )
        assertTrue(
            "and it is scoped per surface, last session wins",
            trace.contains("startsWith(\"frame: \${session.label} \")")
        )
        val helper = source("com/kennyb1201/kbstream/ui/components/ScrollFrameMeter.kt")
        assertTrue(
            "the Compose helper reads scroll state outside composition",
            helper.contains("snapshotFlow { state.isScrollInProgress }")
        )
        listOf("home", "guide", "library", "catalog").forEach { label ->
            val screen = when (label) {
                "home" -> "ui/mobile/HomeScreenMobile.kt"
                "guide" -> "ui/mobile/GuideScreenMobile.kt"
                "library" -> "ui/mobile/LibraryScreenMobile.kt"
                else -> "ui/mobile/CatalogGridScreenMobile.kt"
            }
            assertTrue(
                "$label is metered",
                source("com/kennyb1201/kbstream/$screen")
                    .contains("TrackScrollFrames(label = \"$label\"")
            )
        }
    }
}
