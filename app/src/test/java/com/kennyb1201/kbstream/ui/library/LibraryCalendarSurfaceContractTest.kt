package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's regressions, pinned where a device would find them.
 *
 * Reported together on one evening: the calendar "took really long to load on
 * first try"; "it only shows one episode per day ... clicking the day does
 * nothing doesnt bring up a list of the multiple episodes and it only shows 1
 * episode at the bottom"; "the little numbers under the day ... are
 * clipped/truncated almost half of it is clipped"; and "theres nothing in future
 * dates for profile one but profile 2s future dates work" - beside the Home
 * Upcoming heading, whose focus fill was "an almost solid white ... u can barely
 * read upcoming".
 *
 * Each of those has one cause here:
 *
 *  - the day list shared the month's column, so six rows of grid left it about
 *    one row of height, and its entry requester sat on a container that is not a
 *    focus target at all - so a day's episodes could not be reached with a
 *    remote (layout + focus, below);
 *  - the cell was 42dp, less than a day number's line box plus the count's, so
 *    the count was drawn outside the Surface (the height assertion);
 *  - the month was built behind a single publish, after every show's TMDB call
 *    had answered, and the history read used a DAO captured once at
 *    construction against the UNSCOPED database (the ViewModel assertions) - so
 *    a profile's own shows could be missing entirely and a first visit looked
 *    like it would never load;
 *  - the Home heading passed only its resting colours to tv-material3, whose
 *    focused fill is not this app's (the Home assertion).
 *
 * Read from source: this is layout, focus and paint order, which need a
 * television and a remote to press.
 */
class LibraryCalendarSurfaceContractTest {

    private val pane = normalized(stripComments(source(PANE)))
    private val viewModel = normalized(stripComments(source(VIEW_MODEL)))
    private val home = normalized(stripComments(source(HOME)))

    @Test
    fun `the day list gets its own column, with the pane's whole height`() {
        assertTrue(
            "a television arranges the grid and the day list side by side",
            pane.contains("CalendarPaneLayout(") &&
                pane.contains("Row( horizontalArrangement = Arrangement.spacedBy(22.dp)") &&
                pane.contains(
                    "modifier = Modifier .weight(1.1f) .fillMaxHeight()"
                ) &&
                pane.contains("modifier = Modifier .weight(1f) .fillMaxHeight()")
        )
        assertTrue(
            "and the stacked arrangement (a phone) gives the list the REMAINING " +
                "height rather than letting the grid take its size first",
            pane.contains("modifier = Modifier .fillMaxWidth() .weight(1f)")
        )
    }

    @Test
    fun `a day's episodes are all drawn, and the list can be entered and left`() {
        assertTrue(
            "every episode of the selected day is an item - not one row, not a take(1)",
            pane.contains("itemsIndexed( items = episodes,") &&
                !pane.contains(".take(1)")
        )
        assertTrue(
            // ANDROID-11: the watched key is NOT a row identity - a day can hold
            // the same show/season/episode twice, and Compose crashed the list on
            // the duplicate key. The rows come from one precomputed, unique list.
            "row keys are unique per row, not per watched key",
            pane.contains("val rowKeys = remember(episodes) { calendarRowKeys(episodes) }") &&
                pane.contains("key = { index, _ -> rowKeys[index] }")
        )
        assertTrue(
            "the FIRST row is the list's entry point, because a Column is not a " +
                "focus target and the requester used to sit on one",
            pane.contains("requester = if (index == 0) firstEpisodeRequester else null") &&
                pane.contains("if (requester == null) Modifier else Modifier.focusRequester(requester)")
        )
        assertTrue(
            "and Up on that row hands the D-pad back to the selected day",
            pane.contains("onUpPressed = if (index == 0 && !touch) onUpToGrid else null") &&
                pane.contains("event.key == Key.DirectionUp") &&
                pane.contains("onUpPressed() true")
        )
        assertTrue(
            "pressing a day takes the D-pad into that day's list - 'clicking the " +
                "day does nothing' was exactly this: the press only retitled the " +
                "pane beside the grid, which Down could reach from the bottom row " +
                "alone",
            pane.contains("if (hasEpisodesBelow) onOpenDay()") &&
                pane.contains("onEnterDayList = { listLandRequest++ }") &&
                pane.contains("LaunchedEffect(listLandRequest) {") &&
                pane.contains("landed = runCatching { firstEpisodeRequester.requestFocus() }")
        )
        assertTrue(
            "Down on the grid's bottom row is consumed on a fresh press; it only " +
                "enters the list when there IS one to land in",
            pane.contains("hasEpisodesBelow = days[day].orEmpty().isNotEmpty()") &&
                pane.contains("event.key == Key.DirectionDown && isLastRow -> {") &&
                pane.contains("if (hasEpisodesBelow) onEnterDayList()") &&
                pane.contains("onEnterDayList() true")
        )
        assertFalse(
            "and an empty day's press no longer falls through to Compose's " +
                "focus search, which had nothing below the last row to find",
            pane.contains("event.key == Key.DirectionDown && isLastRow && hasEpisodesBelow")
        )
        assertTrue(
            "and the heading says how many, so the grid's count has a word beside it",
            pane.contains("text = if (episodes.size == 1) {") &&
                pane.contains("${'$'}{episodes.size} episodes")
        )
    }

    @Test
    fun `a day cell is tall enough for its own two lines`() {
        val height = Regex("private val CALENDAR_CELL_HEIGHT = (\\d+)\\.dp")
            .find(pane)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("CALENDAR_CELL_HEIGHT not found")

        assertTrue(
            "the day number's line box plus the count's is ~40dp; 42dp drew the " +
                "count half outside the cell, which is the clipped number reported",
            height >= 46
        )
        assertTrue(
            "the cell still draws the count under the day number",
            pane.contains("text = episodeCount.toString()")
        )
    }

    @Test
    fun `the month paints show by show instead of after the slowest call`() {
        assertTrue(
            "each resolved show files what it found and paints at once",
            viewModel.contains("private fun noteResolved(") &&
                viewModel.contains("for ((day, episodes) in calendarEpisodesFor(month, show, LocalDate.now()))") &&
                viewModel.contains("days = calendarSortedDays(build.days)") &&
                viewModel.contains("loading = false")
        )
        assertTrue(
            "a build the viewer interrupted keeps what it already resolved",
            viewModel.contains("private class MonthProgress {") &&
                viewModel.contains("val resolved = HashSet<String>()") &&
                viewModel.contains("val build = progress.getOrPut(month) { MonthProgress() }") &&
                viewModel.contains("showIds.filterNot { it in build.resolved }")
        )
        assertTrue(
            "and the viewer's own day is not yanked out from under them when the " +
                "build finally lands",
            viewModel.contains("keepSelection = moved") &&
                viewModel.contains("keepSelection != null && YearMonth.from(keepSelection) == month")
        )
    }

    @Test
    fun `the history read follows the active profile, per read`() {
        assertTrue(
            "the calendar reads the PROFILE-SCOPED history, resolved at each use: " +
                "a DAO captured at construction answers every read after a profile " +
                "switch with a closed database, which the soft-failure wrapping " +
                "turns into an empty calendar",
            viewModel.contains("WatchHistoryDatabase.getInstanceScoped(appContext).watchHistoryDao()") &&
                !viewModel.contains("WatchHistoryDatabase.getInstance(application)")
        )
        assertTrue(
            "and both the tracked-show set and the watched union go through it",
            viewModel.contains("val dao = historyDao()") &&
                viewModel.contains("runCatchingCancellable { historyDao().getCompletedSeriesRows() }")
        )
        assertTrue(
            "the candidate set is source-fair, so a long history cannot crowd the " +
                "tracker-only (currently airing) shows out of the calendar",
            viewModel.contains("showIds = calendarCandidates(") &&
                viewModel.contains("sources = listOf(local, simkl, mdbList, trakt)")
        )
    }

    @Test
    fun `a held key pages one month, not one per auto-repeat`() {
        assertTrue(
            "the grid pages on a FRESH press only: a remote's auto-repeat arrives " +
                "as a stream of KeyDowns, and every repeat used to page the month - " +
                "which cancels the build in flight and fans out over every tracked " +
                "show again, the 'scrolled through the calendar quickly and it " +
                "kicked me out to launcher' report",
            pane.contains("} else if (event.nativeKeyEvent.repeatCount > 0) {") &&
                pane.indexOf("repeatCount > 0") <
                pane.indexOf("event.key == Key.DirectionLeft && isFirstColumn")
        )
        assertTrue(
            "and the first press still does the thing - no arm was removed to " +
                "make that true",
            pane.contains("event.key == Key.DirectionLeft && isFirstColumn") &&
                pane.contains("event.key == Key.DirectionRight && isLastColumn") &&
                pane.contains("event.key == Key.DirectionDown && isLastRow -> {") &&
                pane.contains("onPrevMonth() true") &&
                pane.contains("onNextMonth() true") &&
                pane.contains("onEnterDayList() true")
        )
    }

    @Test
    fun `the calendar's session caches are bounded and releasable`() {
        assertTrue(
            "a session keeps a handful of built months and interrupted builds, not " +
                "a year of them: each one is a whole library's episodes",
            viewModel.contains("const val MAX_CACHED_MONTHS = 4") &&
                viewModel.contains("private fun trimMonthCache(keep: YearMonth) {") &&
                viewModel.contains("while (monthCache.size > MAX_CACHED_MONTHS) {") &&
                viewModel.contains("private fun trimProgress(keep: YearMonth) {") &&
                viewModel.contains("while (progress.size > MAX_CACHED_MONTHS) {")
        )
        assertTrue(
            "and both trims are reached from the paths that grow them",
            viewModel.contains("monthCache[month] = built trimMonthCache(keep = month)") &&
                viewModel.contains(
                    "val build = progress.getOrPut(month) { MonthProgress() } " +
                        "trimProgress(keep = month)"
                )
        )
        assertTrue(
            "the caches are on the app's memory registry as well, so a tight heap or " +
                "the player opening takes them back instead of the box taking the " +
                "process (which is how a death on a 1.7 GB box arrives with nothing " +
                "in Sentry)",
            viewModel.contains("MemoryPressure.Releasable") &&
                viewModel.contains("MemoryPressure.register(this)") &&
                viewModel.contains("override fun releaseCaches() {") &&
                viewModel.contains("override fun cacheStats(): String =")
        )
    }

    @Test
    fun `a profile switch forgets every per-profile fact`() {
        assertTrue(
            "show set, details, months and partial builds all belong to the " +
                "profile that built them",
            viewModel.contains("private fun forgetEverything() {") &&
                viewModel.contains("details.clear()") &&
                viewModel.contains("monthCache.clear()") &&
                viewModel.contains("progress.clear()")
        )
    }

    @Test
    fun `the Home Upcoming heading names every state's colours`() {
        val title = home.substringAfter("private fun SectionTitle(")
        val plainBranch = title.substringAfter("if (onClick == null) {")
            .substringBefore("} else {")

        assertTrue(
            "a rail heading with no destination stays a plain caption, with no " +
                "extra focus stop",
            plainBranch.contains("KBSectionHeader(") && !plainBranch.contains("Surface(")
        )
        assertTrue(
            "the control is still a Surface, so the D-pad and a tap share one target",
            title.contains("Surface( onClick = onClick,")
        )
        assertTrue(
            "the focused and pressed fills come from this app's palette, not from " +
                "tv-material3's default near-white",
            title.contains("focusedContainerColor = KBSurfaceRaised") &&
                title.contains("focusedContentColor = KBTextHi") &&
                title.contains("pressedContainerColor = KBSurfaceRaised") &&
                title.contains("pressedContentColor = KBTextHi")
        )
        assertFalse(
            "and the resting pair alone is no longer the whole colour set",
            title.contains("containerColor = Color.Transparent, contentColor = KBTextHi )")
        )
        assertTrue(
            "focus marks it as the one rail heading in Home that is also a door",
            title.contains("focusedBorder = androidx.tv.material3.Border(") &&
                title.contains("BorderStroke(1.dp, KBAccent)")
        )
    }

    @Test
    fun `focus is legible, and a phone can press the calendar at all`() {
        // Two reports from the same evening on the same screen (2026-10-24):
        // "when u click on a day in calendar then focus is bright white and hard
        // to read what ur focused on" and "the calendar on mobile is
        // unclickable". One cause each.
        val dayCell = pane.substringAfter("private fun CalendarDayCell(")
            .substringBefore("private fun CalendarDayCellContent(")
        val episodeRow = pane.substringAfter("private fun CalendarEpisodeRow(")
            .substringBefore("private fun RowScope.CalendarEpisodeContent(")
        val chip = pane.substringAfter("private fun CalendarChip(")
            .substringBefore("private fun CalendarChipLabel(")

        listOf(dayCell, episodeRow, chip).forEach { control ->
            assertTrue(
                "every pressable names its FOCUSED fill and label: tv-material3's " +
                    "default focused container is an almost solid white this palette " +
                    "never uses, and the label keeps its bright resting colour on it",
                control.contains("focusedContainerColor =")
            )
            assertTrue(
                "and its pressed pair, so a press is the same plate as focus",
                control.contains("pressedContainerColor =") &&
                    control.contains("pressedContentColor =")
            )
            assertTrue(
                "the D-pad path is still a tv Surface - the fix added a touch " +
                    "branch, it did not replace focus",
                control.contains("Surface( onClick =")
            )
        }

        assertTrue(
            "focus is also RUNG, not only filled, so where focus is reads without " +
                "relying on a colour change at ten feet",
            dayCell.contains("focusedBorder = Border(") &&
                episodeRow.contains("focusedBorder = Border(")
        )

        assertTrue(
            "a phone presses the same plate through a plain foundation clickable: " +
                "a tv `Surface` is a FOCUS surface and a finger cannot press it at " +
                "all, which is why the whole calendar was dead to touch",
            chip.contains(".clickable(onClick = onClick)") &&
                dayCell.contains(".clickable { onSelect(day) }") &&
                episodeRow.contains(".clickable(onClick = onClick)")
        )
        assertTrue(
            "and that branch is wired from the form factor the pane already reads",
            pane.contains("val touch = !FormFactor.isTv") &&
                pane.contains("if (touch) {") &&
                chip.contains("if (touch) {") &&
                dayCell.contains("if (touch) {") &&
                episodeRow.contains("if (touch) {")
        )
        assertTrue(
            "the day a phone taps still selects, and the header chips still work",
            pane.contains("CalendarChip( label = \"\u2039\", touch = touch,") &&
                pane.contains("CalendarChip(label = \"TODAY\", touch = touch, onClick = onToday)")
        )
    }

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
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

    private companion object {
        const val PANE =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarPane.kt"
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarViewModel.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
