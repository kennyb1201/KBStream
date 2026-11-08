package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's entry focus grab (spec F1-F2, T1-T4), pinned from the sources.
 *
 * The field report: the guide opened with the persisted grid view and nothing
 * moved - no scrolling, no focus, every D-pad press falling on no handler -
 * while a list->grid toggle fixed it. The cause is in §1: the list has had an
 * 8-frame entry grab for a while, the grid never had one, and on direct entry
 * focus starts nowhere.
 *
 *  - the grab is armed by the SCREEN, once per guide composition, and never on
 *    the view toggle (F1a, F2a, T1, T4),
 *  - it waits for the rows and retries like the list's, landing on the selected
 *    group's sidebar row - or the first channel cell when the sidebar is
 *    hidden (F1b/F1c, T2, T3),
 *  - it stands down for a header return or an in-grid handoff in flight, and
 *    for a return from a player (F2b/F2c, T4).
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and S1-S4 settle it.
 */
class GuideGridEntryFocusContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")

    /** The screen's arming: the flag and the one effect that sets it. */
    private val arming = screen.slice(
        "var gridEntryFocusArmed by remember",
        "The grid's zoom, in hours"
    )

    /** The grid's grab effect and its retry loop. */
    private val grab = grid.slice(
        "LaunchedEffect(entryFocusArmed, rows)",
        "/**\n     * F1a/F1b: delivers one in-grid move"
    )

    /** The screen's call into the grid: what it passes down. */
    private val call = screen.slice(
        "entryFocusArmed = gridEntryFocusArmed",
        "sidebarVisible = gridSidebarVisible,"
    )

    /** The List/Grid toggle's own button handling. */
    private val toggle = screen.slice(
        "onToggleView = {",
        "viewToggleRequester = viewToggleFocusRequester,"
    )

    /** The position save every live play passes through. */
    private val playHandoff = screen.slice(
        "fun rememberGuidePosition(",
        "fun resolveChannelNumber("
    )

    // ── T1: arms once on guide open in grid, disarms after landing ──────────

    @Test
    fun `the grab arms once per guide open in grid view and disarms after it runs`() {
        assertTrue(
            "F1a: one flag, off until the screen arms it",
            screen.contains("var gridEntryFocusArmed by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "F1a: armed by the screen's once-per-composition effect, in grid view",
            arming.contains("LaunchedEffect(Unit) {") &&
                arming.contains(
                    "if (guideView == GuideViewMode.GRID && !fromPlayback) gridEntryFocusArmed = true"
                )
        )
        assertEquals(
            "F1a: nothing else arms it - not the toggle, not a recomposition",
            1,
            screen.countOccurrences("gridEntryFocusArmed = true")
        )
        assertTrue(
            "F1a: the grid consumes the flag, so it cannot re-fire mid-session",
            call.contains("entryFocusArmed = gridEntryFocusArmed") &&
                call.contains("onEntryFocusDone = { gridEntryFocusArmed = false }")
        )
        assertTrue(
            "F1b: the grab is keyed on (armed, rows) - the rows arrive after the grid",
            grab.startsWith("LaunchedEffect(entryFocusArmed, rows)") &&
                grab.contains("if (!entryFocusArmed || rows.isEmpty()) return@LaunchedEffect")
        )
        assertEquals(
            "F1a: consumed on the landed path and on the give-up path, never left armed",
            2,
            grab.countOccurrences("onEntryFocusDone()")
        )
    }

    // ── T2: entry with rows still loading lands on the sidebar row ──────────

    @Test
    fun `an entry with rows still loading lands on the selected group sidebar row`() {
        assertTrue(
            "F1b: the sidebar row of the SELECTED group, through the same requester",
            grab.contains("val groupIndex = groups.indexOf(selectedGroup).coerceAtLeast(0)") &&
                grab.contains("val label = groups.getOrNull(groupIndex)") &&
                grab.contains("label?.let { sidebarRequesters[it] }")
        )
        assertTrue(
            "F1b: a selected group the sidebar opens away from has no requester until it " +
                "is scrolled into place - the retry scrolls it, like the in-grid handoffs do",
            grab.contains("sidebarState.scrollToItem(groupIndex)")
        )
        assertTrue(
            "F1b: the home rule is the return effect's - the sidebar while it shows",
            grab.contains("val home = if (sidebarVisible) \"sidebar\" else \"channel\"")
        )
        assertTrue(
            "F1c: eight frames of awaitFrame + requestFocus, checking the result - the " +
                "list's own entry loop, because the row may still be composing",
            grid.contains("private const val ENTRY_FOCUS_ATTEMPTS = 8") &&
                grab.contains("while (attempts < ENTRY_FOCUS_ATTEMPTS)") &&
                grab.contains("runCatching { requester.requestFocus() }.getOrDefault(false)") &&
                grab.contains("awaitFrame()")
        )
        assertTrue(
            "and the landing is recorded as the grid's focus home, so the next arrow moves",
            grab.contains("focusedRow = 0") &&
                grab.contains(
                    "focusHome = if (home == \"sidebar\") GridFocusHome.SIDEBAR else GridFocusHome.CHANNEL"
                )
        )
        assertTrue(
            "F1c: one GUIDE line either way - a still-dead entry must be readable in " +
                "COPY DIAGNOSTICS instead of silent",
            grab.contains("\"grid entry-focus landed=\$target attempts=\$attempts\"") &&
                grab.contains("\"grid entry-focus exhausted home=\$home attempts=\$attempts\"")
        )
    }

    // ── T3: sidebar hidden on entry lands on the first channel cell ─────────

    @Test
    fun `a sidebar hidden on entry lands on the first row channel cell`() {
        val channelBranch = grab.slice("val target = if (home == \"sidebar\") {", "var attempts = 0")
        assertTrue(
            "F1b: with no sidebar to land on, the first row's channel cell",
            channelBranch.contains("\"channel row=0 key=\${firstRow.channelKey}\"") &&
                grab.contains("channelRequesters[firstRow.channelKey]")
        )
        assertTrue(
            "F1b: ...and it is the first row of the rows that actually arrived, not " +
                "the row the viewer never had",
            grab.contains("val firstRow = rows.first()") &&
                grab.contains("val requester = requesterFor()")
        )
    }

    // ── T4: the paths that must not be touched ─────────────────────────────

    @Test
    fun `a toggle, a player return and the list keep their own focus paths`() {
        assertFalse(
            "F2a: the toggle re-arms nothing - the screen is already composed there, so " +
                "focus stays on the header and DOWN lands through the header return",
            toggle.contains("gridEntryFocusArmed")
        )
        assertFalse(
            "F2a/F2c: the arming effect is the once-only shape; keying it on the view " +
                "would fire it on every toggle",
            screen.contains("LaunchedEffect(guideView")
        )
        assertTrue(
            "F2c: leaving for a player is marked at the one place every live play goes " +
                "through, and the entry effect consumes it",
            playHandoff.contains("viewModel.noteGuideHandedOffToPlayer()") &&
                arming.contains("val fromPlayback = viewModel.consumeGuidePlaybackReturn()") &&
                arming.contains("&& !fromPlayback")
        )
        assertTrue(
            "F2c: the marker is in memory on an activity-scoped ViewModel, so it survives " +
                "the player without a disk write that a crash could leave set",
            viewModel.contains("private var guideLeftForPlayback = false") &&
                viewModel.contains("internal fun noteGuideHandedOffToPlayer()") &&
                viewModel.contains("internal fun consumeGuidePlaybackReturn(): Boolean")
        )
        assertTrue(
            "F2b: a header return or an in-grid handoff in flight owns focus, and the grab " +
                "stands down for good rather than racing it",
            grab.contains(
                "if (pendingFocus != null || pendingSidebarFocus || returnFocusSignal > 0)"
            )
        )
        assertTrue(
            "F2d: the list's own entry grab is untouched",
            screen.contains("LaunchedEffect(moveFocusToChannelList, groupedChannels)") &&
                screen.contains("while (!focused && attempts < 8)") &&
                screen.contains("channelListFocusRequester.requestFocus()")
        )
        assertFalse(
            "F2d: the phone grid is not wired to this at all",
            grid.contains("GuideGridMobileView") || screen.contains("phoneEntryFocusArmed")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
