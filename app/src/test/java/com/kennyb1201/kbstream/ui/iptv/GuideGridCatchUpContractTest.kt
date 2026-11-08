package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.gameScoreLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV grid catch-up (spec T1-T6, T4m).
 *
 * Four reports, one answer to the scroll question: every in-grid move went
 * through the async pending path and died silently, the NOW button was still
 * there, no cell showed a score, and every channel cell carried its group's name
 * under it.
 *
 * The behaviour that can run on the JVM is here: the auto-follow rule, the score
 * sentence, and the one per-cell score lookup both surfaces use. The focus
 * handoffs and the render itself cannot be pressed or drawn without Compose, so
 * they are read from the sources the way this module's other guide tests do it -
 * T1's "press it on the TV" half is S1, a field check.
 */
class GuideGridCatchUpContractTest {

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private fun cell(
        startUtcMillis: Long,
        durationMinutes: Long = 60,
        title: String? = "Program",
        channel: String = "ch1",
    ) = GridCell(
        id = GridCell.cellId(channel, startUtcMillis, startUtcMillis + durationMinutes * minute, title == null),
        channelKey = channel,
        startUtcMillis = startUtcMillis,
        endUtcMillis = startUtcMillis + durationMinutes * minute,
        title = title,
    )

    private fun team(abbr: String, score: String?, name: String = abbr) = SportsTeam(
        abbreviation = abbr,
        displayName = name,
        logoUrl = null,
        score = score,
        isHome = abbr == "MIN",
        record = null,
    )

    private fun game(
        state: GameState,
        statusDetail: String = "2nd 14:32",
        kickoff: Long = 1_700_000_000_000L,
        awayScore: String? = "3",
        homeScore: String? = "2",
    ) = SportsGame(
        id = "game-1",
        league = "football/nfl",
        name = "TB at MIN",
        dateMs = kickoff,
        state = state,
        statusDetail = statusDetail,
        away = team("TB", awayScore, "Tampa Bay Buccaneers"),
        home = team("MIN", homeScore, "Minnesota Vikings"),
        broadcastNames = listOf("NFL Sunday Ticket"),
    )

    // ── T3: auto-follow now (F2a) ─────────────────────────────────────────

    @Test
    fun `a window the clock has just left slides exactly one page`() {
        val window = GridWindow.opening(1_700_000_000_000L, windowHours = 3)
        val crossed = window.endUtcMillis + 30_000L

        val next = autoFollowWindow(window, crossed, windowHours = 3, scheduleEndMs = null)

        assertNotNull("the window must follow the clock", next)
        assertEquals(
            "one page, at the window's own zoom",
            window.startUtcMillis + GuideGrid.PAGE_MS,
            next!!.startUtcMillis
        )
        assertEquals(window.durationMs, next.durationMs)
    }

    @Test
    fun `a window still holding now does not move`() {
        val window = GridWindow.opening(1_700_000_000_000L, windowHours = 3)
        val inside = window.startUtcMillis + hour

        assertNull(autoFollowWindow(window, inside, windowHours = 3, scheduleEndMs = null))
    }

    @Test
    fun `a viewer paged into the future is never yanked`() {
        val now = 1_700_000_000_000L
        val window = GridWindow.opening(now, windowHours = 3)
            .paged(direction = 1, floorStartUtcMillis = now, maxEndUtcMillis = null)

        assertNull(
            "a window that does not contain now must stay where the viewer put it",
            autoFollowWindow(window, now + 30_000L, windowHours = 3, scheduleEndMs = null)
        )
    }

    @Test
    fun `a window the clock left long ago is not dragged forward`() {
        val window = GridWindow.opening(1_700_000_000_000L, windowHours = 3)
        val hoursLater = window.endUtcMillis + 3 * hour

        assertNull(
            "an idle guide is not a missing press: only just-crossed follows",
            autoFollowWindow(window, hoursLater, windowHours = 3, scheduleEndMs = null)
        )
    }

    @Test
    fun `the guide's schedule end still stops the auto-follow`() {
        val window = GridWindow.opening(1_700_000_000_000L, windowHours = 3)
        val crossed = window.endUtcMillis + 30_000L

        assertNull(
            "there is no time past the guide's last program to follow to",
            autoFollowWindow(window, crossed, windowHours = 3, scheduleEndMs = window.endUtcMillis)
        )
    }

    // ── T4: the score sentence and the one lookup (F3b) ───────────────────

    @Test
    fun `a live game reads its two sides, the score and the clock`() {
        assertEquals(
            "TB 3 – 2 MIN · 2nd 14:32",
            gameScoreLine(game(GameState.LIVE))
        )
    }

    @Test
    fun `a final game reads Final and the score, with no clock left to report`() {
        assertEquals("Final 3 – 2", gameScoreLine(game(GameState.FINAL, statusDetail = "Final")))
    }

    @Test
    fun `a game that has not started has no score to draw`() {
        assertNull(
            "an upcoming game would read 0 - 0, which is a lie dressed as a result",
            gameScoreLine(game(GameState.UPCOMING, awayScore = null, homeScore = null))
        )
    }

    @Test
    fun `a feed with no score for a side is never filled in with a zero`() {
        assertNull(gameScoreLine(game(GameState.LIVE, homeScore = null)))
    }

    private fun score(start: Long, live: Boolean, channel: String = "ch1") = GuideScore(
        channelIds = setOf(channel),
        startUtcMillis = start,
        line = if (live) "TB 3 – 2 MIN · 2nd 14:32" else "Final 3 – 2",
        live = live,
    )

    @Test
    fun `a live game sits on the cell on air now on its own channel`() {
        val kickoff = 1_700_000_000_000L
        val now = kickoff + 40 * minute
        // The cell was CLIPPED to the window and starts before kickoff: the
        // program is the game's broadcast slot even though the kickoff is not
        // inside the drawn span, which is the case the containment rule missed.
        val onAir = cell(startUtcMillis = now - 10 * minute, durationMinutes = 120)

        assertEquals(
            "TB 3 – 2 MIN · 2nd 14:32",
            scoreFor("ch1", onAir, now, listOf(score(kickoff, live = true)))?.line
        )
        assertNull(
            "another channel is not carrying it",
            scoreFor("ch2", onAir, now, listOf(score(kickoff, live = true)))
        )
        assertNull(
            "a gap is not carrying anything",
            scoreFor("ch1", onAir.copy(title = null), now, listOf(score(kickoff, live = true)))
        )
    }

    @Test
    fun `a finished game keeps reading Final on its own program only`() {
        val kickoff = 1_700_000_000_000L
        val carried = cell(startUtcMillis = kickoff - 20 * minute, durationMinutes = 180)
        val after = cell(startUtcMillis = kickoff + 160 * minute, durationMinutes = 60)
        val now = kickoff + 200 * minute

        assertEquals(
            "the program the game was scheduled in",
            "Final 3 – 2",
            scoreFor("ch1", carried, now, listOf(score(kickoff, live = false)))?.line
        )
        assertNull(
            "the next program does not inherit it",
            scoreFor("ch1", after, now, listOf(score(kickoff, live = false)))
        )
    }

    @Test
    fun `a cell with no resolved games draws no score`() {
        val now = 1_700_000_000_000L
        assertNull(scoreFor("ch1", cell(startUtcMillis = now), now, emptyList()))
    }

    // ── T1: every in-grid move asks the target directly first ─────────────

    @Test
    fun `every cell and channel arrow delivers through the direct-first handoff`() {
        val grid = gridSource()
        val cellKeys = region(grid, "fun onCellKey(", "Key.DirectionCenter")
        listOf("cell-left", "cell-right", "cell-up", "cell-down").forEach { direction ->
            assertTrue(
                "$direction must go through the direct-first handoff",
                cellKeys.contains("handoffFocus(") && cellKeys.contains("\"$direction\"")
            )
        }
        val channelKeys = region(grid, "fun onChannelKey(", "Key.DirectionCenter")
        listOf("channel-right", "channel-up", "channel-down").forEach { direction ->
            assertTrue(
                "$direction must go through the direct-first handoff",
                channelKeys.contains("\"$direction\"")
            )
        }
    }

    @Test
    fun `the handoff asks the requester first and falls back to the pending path`() {
        val handoff = region(gridSource(), "fun handoffFocus(", "fun activateCell(")

        assertTrue("the target's own requester is asked", handoff.contains("requester.requestFocus()"))
        assertTrue(
            "the direct answer moves the focused state itself",
            handoff.contains("focusedRow = rowIndex") && handoff.contains("focusedCellId = cell?.id")
        )
        assertTrue(
            "and a failure posts the pending target as the fallback",
            handoff.contains("pendingFocus = GridPendingFocus(")
        )
        assertTrue(
            "one GUIDE line per move, the enterGrid shape (F1b)",
            handoff.contains("\"grid handoff \$direction")
        )
    }

    @Test
    fun `a handoff that runs out of retries says so instead of ending silently`() {
        val grid = gridSource()
        assertTrue(
            "F1c: the exhaustion is logged with the target and the attempts used",
            grid.contains("pending-giveup") &&
                region(grid, "pending-giveup", "F1b: the sidebar").contains("attempts=\$attempts")
        )
    }

    @Test
    fun `the sidebar handoffs keep their direct-first shape`() {
        val grid = gridSource()
        val enter = region(grid, "fun enterGrid(", "fun handoffFocus(")
        assertTrue(enter.contains("requester.requestFocus()"))
        assertTrue(enter.contains("direct=\$direct fallback=\${!direct}"))

        val channelLeft = region(grid, "fun onChannelKey(", "fun onSidebarKey(")
        assertTrue(
            "the sidebar move that already worked is untouched (T6)",
            channelLeft.contains("grid handoff channel->sidebar")
        )
    }

    // ── T5: the channel cell's group line is gone (F4) ────────────────────

    @Test
    fun `the channel cell's second line is the number, the loading state, or nothing`() {
        val cellSource = region(gridSource(), "private fun ChannelCell(", "private fun ProgramRow(")

        assertTrue("the number still wins", cellSource.contains("number != null -> number"))
        assertTrue(
            "loading still says so",
            cellSource.contains("guidePending -> \"Loading guide…\"")
        )
        assertTrue("otherwise the line is blank", cellSource.contains("else -> \" \""))
        assertFalse(
            "the group title is no longer rendered under the channel name",
            cellSource.contains("groupTitle")
        )
    }

    // ── T6 / F2b: the NOW button is gone; the re-anchor path is not ───────

    @Test
    fun `the header no longer carries a NOW button`() {
        val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
        assertFalse(
            "round-2 F7 / F2b: the button goes",
            screen.contains("text = \"NOW\"") || screen.contains("onJumpToNow")
        )
        assertFalse(
            "and its signal with it - the grid follows now on its own",
            gridSource().contains("jumpToNowSignal")
        )
    }

    @Test
    fun `the re-anchor path survives the button's removal`() {
        val grid = gridSource()
        assertTrue("the auto-follow rule drives it", grid.contains("autoFollowWindow("))
        assertTrue(
            "and it still lands focus on the focused row's live program",
            grid.contains("focusLiveOnNextWindow = true")
        )
        assertTrue(
            "the rule itself stays in the shared, JVM-tested rules",
            rulesSource().contains("fun autoFollowWindow(")
        )
    }

    // ── F3m: the phone draws the same lookup, not a second one ────────────

    @Test
    fun `the phone cell renders the shared score and gives the slot to it`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        val programCell = region(mobile, "private fun MobileProgramCell(", "private fun MobileGridDetailStrip(")

        assertTrue("it draws what the shared lookup returned", programCell.contains("score?.line"))
        assertTrue(
            "the reminder indicator gives the slot up while a game is on (F3m-c)",
            programCell.contains("\"REMINDER SET\".takeIf { reminderSet }")
        )
        assertTrue(
            "the cell asks the ONE lookup",
            mobile.contains("scoreFor(")
        )
        assertFalse(
            "and matches no game itself: no second score pipeline in the mobile file",
            mobile.contains("SportsChannelMatcher") || mobile.contains("LiveScoreFeed")
        )
        assertTrue(
            "the phone grid is handed the same list the TV grid is",
            mobile.contains("scores: List<GuideScore>")
        )
    }

    @Test
    fun `both surfaces are wired to the ViewModel's one score list`() {
        val tv = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
        val phone = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")

        assertTrue(
            "the TV screen reads the ViewModel's list and passes it to the grid",
            tv.contains("viewModel.liveScores") && tv.contains("scores = liveScores")
        )
        assertTrue(
            "the phone screen does the same",
            phone.contains("viewModel.liveScores") && phone.contains("scores = gridScores")
        )
        assertTrue(
            "the poll is attached while a grid is on screen, on both",
            tv.contains("viewModel.startLiveScores()") && phone.contains("viewModel.startLiveScores()")
        )
    }

    // ── T2: the destinations are unchanged ────────────────────────────────

    @Test
    fun `the move targets are the ones they were before the delivery changed`() {
        val cells = listOf(
            cell(0, 30, "A"),
            cell(30 * minute, 30, null),
            cell(60 * minute, 60, "B"),
        )

        assertEquals(cells[0], horizontalTarget(cells, cells[1].id, -1))
        assertEquals(cells[2], horizontalTarget(cells, cells[1].id, 1))
        assertNull(horizontalTarget(cells, cells[2].id, 1))
        assertEquals(
            "one row up, landing on the program at the pressed cell's start",
            GridFocusTarget(rowIndex = 0, cell = cells[2]),
            verticalTarget(listOf(cells, cells), fromRow = 1, fromCell = cells[2], direction = -1)
        )
        assertNull(verticalTarget(listOf(cells), fromRow = 0, fromCell = cells[0], direction = -1))
    }

    // ── Source helpers ────────────────────────────────────────────────────

    private fun gridSource(): String = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")

    private fun rulesSource(): String = source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")

    /** [source] between [from] and the first [to] after it, whitespace-normalized. */
    private fun region(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("missing marker: $from", start >= 0)
        val end = source.indexOf(to, start).takeIf { it > start } ?: source.length
        return source.substring(start, end)
    }

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
