package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player episode picker's long-press menu, pinned (spec 2026-11-13).
 *
 * The menu is Detail's episode menu on the picker's own rows: four actions with
 * Detail's toggle labels, the playing episode protected, focus handed back, and
 * playback never touched. What a source-reading test can hold, it holds:
 *
 *  - F1: the long-press slot exists on `PickerItem`, the adapter consumes it
 *    (never also clicks), and only ACTIONABLE episode rows carry it - not the
 *    loading row, not an unaired episode, not a source/season row;
 *  - F2: the four actions and their labels are spelled once and toggled from
 *    the same watched set the rows read; the playing row never offers to clear
 *    itself and the two clearing scopes step around it;
 *  - F2: the menu is the shared action model drawn by the player's View twin,
 *    dismissed by Back or an action, and every dismissal hands focus back to
 *    the same row;
 *  - the writes go through the shared watched writer - no player-only mark
 *    path that skips the tracker fan-out - and no menu path pauses, stops or
 *    restarts playback.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/main/java/.../PickerEpisodeMenuRules.kt
 * app/src/test/java/.../PickerEpisodeMenuContractTest.kt, then JUnitCore with
 * -Duser.dir at the repository root so the source reader finds src/main.
 */
class PickerEpisodeMenuContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── F1: the long-press ─────────────────────────────────────────────────

    @Test
    fun `the rows take a long-press that is consumed, never also a click`() {
        val adapter = flat(source(ADAPTER))
        assertTrue(
            "PickerItem carries the slot, defaulted so every non-episode row is unaffected",
            adapter.contains("val onLongClick: (() -> Unit)? = null")
        )
        assertTrue(
            "a recycled row that has no menu must drop the listener it was given",
            adapter.contains("holder.itemView.setOnLongClickListener(null)")
        )
        assertTrue(
            "and a row that has one consumes it - a long-press never also picks the episode",
            adapter.contains("holder.itemView.setOnLongClickListener { longClick() true }")
        )
    }

    @Test
    fun `only an actionable episode row gets the menu`() {
        val picker = flat(source(PICKER))
        assertTrue(
            "rows() takes the menu callback beside onPick",
            picker.contains("onEpisodeMenu: ((ResolvedEpisode) -> Unit)? = null")
        )
        assertTrue(
            "the slot is passed for an episode row that can act, and withheld for an unaired one",
            picker.contains(
                "onLongClick = onEpisodeMenu?.takeIf { !unavailable }?.let { menu -> { menu(episode) } }"
            )
        )
        assertFalse(
            "the loading row is not an episode and has no menu",
            picker.contains("PickerItem(label = LOADING_LABEL, onClick = {}, onLongClick")
        )
        val strip = flat(between(picker, "fun seasonStrip(", "fun seasonPoster("))
        assertFalse(
            "and the season strip stays click-only",
            strip.contains("onLongClick")
        )
    }

    // ── F2: Detail's four actions, Detail's labels ─────────────────────────

    @Test
    fun `the menu offers detail's four actions with its toggle labels`() {
        val unwatched = PickerEpisodeMenuRules.rows(
            episode = 5,
            isEpisodeWatched = false,
            isSeasonWatched = false,
            isWholeSeriesWatched = false,
            isPlaying = false
        )
        assertEquals(
            listOf(
                "Mark as Watched",
                "Mark Previous as Watched",
                "Mark Season as Watched",
                "Mark Entire Series as Watched"
            ),
            unwatched.map { it.label }
        )
        assertTrue(
            "the whole-series row keeps Detail's sub-line",
            unwatched.last().description == "Every season at once, not just this one"
        )

        val watched = PickerEpisodeMenuRules.rows(
            episode = 5,
            isEpisodeWatched = true,
            isSeasonWatched = true,
            isWholeSeriesWatched = true,
            isPlaying = false
        )
        assertEquals(
            listOf(
                "Mark as Unwatched",
                "Mark Previous as Unwatched",
                "Mark Season as Unwatched",
                "Mark Entire Series as Unwatched"
            ),
            watched.map { it.label }
        )
        assertFalse(
            "and the clearing labels carry the clearing direction, so the write cannot disagree",
            watched.any { it.marksWatched }
        )
    }

    @Test
    fun `episode one has no previous row`() {
        val rows = PickerEpisodeMenuRules.rows(
            episode = 1,
            isEpisodeWatched = false,
            isSeasonWatched = false,
            isWholeSeriesWatched = false,
            isPlaying = false
        )
        assertEquals(3, rows.size)
        assertFalse(rows.any { it.action == PickerEpisodeMenuAction.PREVIOUS })
    }

    // ── F2: the playing episode is never cleared ───────────────────────────

    @Test
    fun `the playing row never offers to clear itself`() {
        val rows = PickerEpisodeMenuRules.rows(
            episode = 5,
            isEpisodeWatched = true,
            isSeasonWatched = false,
            isWholeSeriesWatched = false,
            isPlaying = true
        )
        val episodeRow = rows.first { it.action == PickerEpisodeMenuAction.EPISODE }
        assertEquals("only Mark as Watched shows on the row that is playing", "Mark as Watched", episodeRow.label)
        assertTrue("and it marks, never clears", episodeRow.marksWatched)
        assertFalse(
            "there is no unwatch row on the playing episode at all",
            rows.any { row -> !row.marksWatched && row.action == PickerEpisodeMenuAction.EPISODE }
        )
    }

    @Test
    fun `clearing a season or the series steps around the playing episode`() {
        val seasonRow = PickerEpisodeMenuRules.rows(
            episode = 3,
            isEpisodeWatched = true,
            isSeasonWatched = true,
            isWholeSeriesWatched = true,
            isPlaying = false
        ).first { it.action == PickerEpisodeMenuAction.SEASON }
        val seasonNumbers = (1..8).toList()
        val seriesNumbers = listOf(1 to (1..10).toList(), 2 to seasonNumbers)
        val seasonScope = PickerEpisodeMenuRules.numbersToChange(
            row = seasonRow,
            season = 2,
            pressedEpisode = 3,
            seasonEpisodeNumbers = seasonNumbers,
            seriesEpisodeNumbers = seriesNumbers,
            playingSeason = 2,
            playingEpisode = 5
        )
        assertEquals(
            "every episode of the season clears except the one playing",
            listOf(2 to listOf(1, 2, 3, 4, 6, 7, 8)),
            seasonScope
        )

        val seriesRow = PickerEpisodeMenuRules.rows(
            episode = 3,
            isEpisodeWatched = true,
            isSeasonWatched = true,
            isWholeSeriesWatched = true,
            isPlaying = false
        ).first { it.action == PickerEpisodeMenuAction.SERIES }
        val seriesScope = PickerEpisodeMenuRules.numbersToChange(
            row = seriesRow,
            season = 2,
            pressedEpisode = 3,
            seasonEpisodeNumbers = seasonNumbers,
            seriesEpisodeNumbers = seriesNumbers,
            playingSeason = 2,
            playingEpisode = 5
        )
        assertEquals(
            "the whole-series clear covers every season and still leaves the playing episode",
            listOf(1 to (1..10).toList(), 2 to listOf(1, 2, 3, 4, 6, 7, 8)),
            seriesScope
        )
    }

    @Test
    fun `marking includes the playing episode`() {
        val seasonRow = PickerEpisodeMenuRules.rows(
            episode = 3,
            isEpisodeWatched = false,
            isSeasonWatched = false,
            isWholeSeriesWatched = false,
            isPlaying = false
        ).first { it.action == PickerEpisodeMenuAction.SEASON }
        assertEquals(
            "marking a season marks all of it - the playing episode is already watched",
            listOf(2 to (1..8).toList()),
            PickerEpisodeMenuRules.numbersToChange(
                row = seasonRow,
                season = 2,
                pressedEpisode = 3,
                seasonEpisodeNumbers = (1..8).toList(),
                seriesEpisodeNumbers = emptyList(),
                playingSeason = 2,
                playingEpisode = 5
            )
        )
    }

    // ── F2: one menu, one writer, focus back, playback untouched ───────────

    @Test
    fun `the menu is the shared action model drawn over the open panel`() {
        val menu = flat(source(MENU))
        assertTrue(
            "the rows are the shared PosterContextAction model, not a second menu vocabulary",
            menu.contains("actions: List<PosterContextAction>")
        )
        assertTrue(
            "the row plate is the picker's own, so a focused row reads like a focused source",
            menu.contains("R.drawable.picker_item_bg")
        )
        assertTrue(
            "Back closes it",
            menu.contains("OnBackPressedCallback") && menu.contains("handleOnBackPressed() { dismiss() }")
        )
        assertTrue(
            "an action closes it too, before it runs",
            menu.contains("setOnClickListener { dismiss() action.onClick() }")
        )
        assertTrue(
            "and every dismissal hands focus back to the row it came from",
            menu.contains("if (restoreFocus) onDismissed()")
        )
        assertTrue(
            "the menu has no player handle and cannot pause, stop or restart anything",
            !menu.contains("exoPlayer") && !menu.contains("mpv") &&
                !menu.contains(".pause()") && !menu.contains(".stop()")
        )
    }

    @Test
    fun `the press-time repaint keeps the playing episode watched`() {
        val target = PickerEpisodeMenuTarget(
            parentId = "tt1",
            parentType = "series",
            showName = "Show",
            posterUrl = null,
            tmdbId = null,
            season = 2,
            pressedEpisode = 3,
            seasonEpisodeNumbers = (1..8).toList(),
            seriesEpisodeNumbers = listOf(1 to (1..10).toList(), 2 to (1..8).toList()),
            playingSeason = 2,
            playingEpisode = 5
        )
        val clearSeason = PickerEpisodeMenuRules.rows(3, true, true, true, false)
            .first { it.action == PickerEpisodeMenuAction.SEASON }
        val cleared = PickerEpisodeMenuRules.preview(
            clearSeason,
            target,
            (1..8).toSet(),
            (1..8).map { 2 to it }.toSet()
        )
        assertEquals(
            "every other episode clears on the repaint, the playing one keeps its checkmark",
            setOf(5),
            cleared.seasonWatched
        )

        val markSeason = PickerEpisodeMenuRules.rows(3, false, false, false, false)
            .first { it.action == PickerEpisodeMenuAction.SEASON }
        val marked = PickerEpisodeMenuRules.preview(markSeason, target, emptySet(), emptySet())
        assertEquals(
            "and a mark paints the whole season at once, without waiting for the trackers",
            (1..8).toSet(),
            marked.seasonWatched
        )
        assertEquals((1..8).map { 2 to it }.toSet(), marked.watchedPairs)
    }

    @Test
    fun `both engines open the menu on the same watched set the rows read`() {
        listOf(NATIVE, MPV).forEach { path ->
            val player = flat(source(path))
            assertTrue(
                "$path must pass the menu to the shared row builder",
                player.contains("onEpisodeMenu = { pressed -> openEpisodeMenu(pressed) }")
            )
            assertTrue(
                "$path labels the episode row from the picker's own watched set",
                player.contains("isEpisodeWatched = pressed.episodeNumber in episodePickerWatched")
            )
            assertTrue(
                "$path hands focus back to the row the menu came from",
                player.contains("PickerEpisodeMenu(this) { restoreEpisodePickerFocus() }")
            )
            assertTrue(
                "$path lets the menu own the D-pad while it is up",
                player.contains("if (menu?.isShowing == true)")
            )
            assertTrue(
                "$path writes through the shared apply layer",
                player.contains("PickerEpisodeMarks.apply(")
            )
            assertTrue(
                "$path repaints the row from the same rule on the press, not after the trackers",
                player.contains("PickerEpisodeMenuRules.preview(")
            )
        }
    }

    @Test
    fun `the writes are the shared writer, never a player-only mark path`() {
        val marks = flat(source(MARKS))
        assertTrue(
            "the mark action is the repository's own, with its tracker fan-out",
            marks.contains("repository.markEpisodesWatchedLocal(")
        )
        assertTrue(
            "and so is the clear direction",
            marks.contains("repository.markEpisodesUnwatchedLocal(")
        )
        assertTrue(
            "the whole-series mark also records the title-level mark",
            marks.contains("repository.markWatchedLocal(")
        )
        assertTrue(
            "the whole-series clear is the shared whole-title unmark, then the playing row is put back",
            marks.contains("repository.markUnwatchedLocal(") &&
                marks.contains("if (playingSeason != null && playingEpisode != null && playingEpisode > 0)")
        )
        assertFalse(
            "no menu path writes history directly - that is what the writer is for",
            marks.contains("historyDao") || marks.contains("WatchHistoryEntity(")
        )
        assertFalse(
            "and nothing in the player-side layer pushes trackers itself",
            marks.contains("pushWatched") || marks.contains("removeWatched")
        )
    }

    @Test
    fun `the menu region never touches playback`() {
        val section = flat(
            between(
                source(NATIVE),
                "private fun openEpisodeMenu(",
                "// --- Channel guide overlay (live only) ---"
            )
        )
        assertTrue("the menu section must be locatable", section.isNotEmpty())
        listOf(
            "pause()",
            "exoPlayer.stop",
            "dismissPicker()",
            "startActivity(",
            "finish()"
        ).forEach { token ->
            assertFalse(
                "the menu must not $token - playback and the open panel are untouched",
                section.contains(token)
            )
        }
    }

    @Test
    fun `the series numbers come from the cached season summaries`() {
        val episodes = flat(source(PLAYER_EPISODES))
        val from = episodes.indexOf("suspend fun seriesEpisodeNumbers(")
        assertTrue("the reader must exist", from >= 0)
        // To the end of the object: the reader is the last member, and the rule
        // is about what this function does, not what the file does elsewhere.
        val series = episodes.substring(from)
        assertTrue(
            "it counts from each season's summary, through Detail's own aired-episode rule",
            series.contains("summary.episodeCount") &&
                series.contains("DetailSeasonEpisodes.numbers(")
        )
        assertFalse(
            "and never loads a season's full episode list to find out",
            series.contains("getSeasonEpisodes(")
        )
    }

    private companion object {
        const val ADAPTER = "com/kennyb1201/kbstream/ui/player/PickerAdapter.kt"
        const val PICKER = "com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt"
        const val MENU = "com/kennyb1201/kbstream/ui/player/PickerEpisodeMenu.kt"
        const val MARKS = "com/kennyb1201/kbstream/ui/player/PickerEpisodeMarks.kt"
        const val PLAYER_EPISODES = "com/kennyb1201/kbstream/ui/player/PlayerEpisodes.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
