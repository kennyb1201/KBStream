package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poster season picker on TV Detail (spec 2026-11-15, the poster season
 * picker, F1).
 *
 * This replaces the header-poster placement contract: the "EPISODES" heading and
 * the single selected-season poster beside it are deleted, and so are the season
 * chips. In their place, in the same position in the list - between the
 * episode-order row and the episode cards - one horizontal row where each season
 * is its own poster with a "SEASON N" caption under it.
 *
 * Pinned here: the header item is gone (that is the clip bug closed by deletion -
 * there is no heading above the row to be cut against); the row is still its own
 * vertical item, in the same position; the poster is the focusable item, focus
 * still SELECTS the season and a tap still selects it with the episode load; a
 * long press still opens the season menu; and no chip is left anywhere in the
 * section.
 *
 * Source-reading style, like this page's other contract tests.
 */
class DetailSeasonPosterPlacementContractTest {

    private val detail by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The season row item, through to the first episode state that follows it. */
    private val row: String by lazy {
        detail.substringAfter("item(key = SEASON_ROW_KEY")
            .substringBefore("when { episodesLoading")
    }

    @Test
    fun `the header and the chips are gone`() {
        assertFalse(
            "no EPISODES header item is left to cut the row against",
            detail.contains("episodesheader")
        )
        assertFalse("no header label is composed", detail.contains("\"EPISODES\""))
        assertFalse(
            "no season chip is composed",
            detail.contains("private fun SeasonChip(") || detail.contains("SeasonsChip(")
        )
        assertFalse("and the chip row is not composed either", detail.contains("SeasonRow("))
    }

    @Test
    fun `the row is still its own item, between the order row and the cards`() {
        // The order row's key is the constant now (the poster row's reveal
        // names its neighbours by key) - the same string as before.
        val order = detail.indexOf("item(key = EPISODE_ORDER_ROW_KEY")
        val seasonRow = detail.indexOf("item(key = SEASON_ROW_KEY")
        val cards = detail.indexOf("episodesloading")
        assertTrue(
            "order row, then the season row, then the episode states",
            order >= 0 && order < seasonRow && seasonRow < cards
        )
        assertEquals("one season row", 1, detail.count("item(key = SEASON_ROW_KEY"))
        assertTrue("and it draws the poster row", row.contains("SeasonPosterRow("))
    }

    @Test
    fun `each season is a poster with a caption under it`() {
        val posterRow = posterRowSource()
        assertTrue("one poster per season", posterRow.contains("SeasonPosterArt("))
        assertTrue(
            "seasons are the row's items, keyed by number",
            posterRow.contains("items(") && posterRow.contains("key = { it }")
        )
        assertTrue(
            "the caption is the season's own label, as the chip had it",
            posterRow.contains("\"SEASON \$season\"") && posterRow.contains("\"SPECIALS\"")
        )
        assertTrue(
            "and a season that names itself wears that name - the label the " +
                "heading used to carry",
            posterRow.contains("seasonNameFor(season)") &&
                posterRow.contains("ownName.uppercase(Locale.US)")
        )
        // The caption is text inside the shared draw, never a chip: no pill, no
        // chip component, no chip shape.
        assertFalse("no chip in the row", posterRow.contains("TouchChoiceChip"))
        assertFalse(
            "and the caption carries no chip chrome of its own",
            posterRow.contains("KBCard(")
        )
    }

    @Test
    fun `focus still selects, tap still selects, long press still opens the menu`() {
        val posterRow = posterRowSource()
        assertTrue(
            "focus on a poster selects the season",
            posterRow.contains("onSeasonFocused(season)")
        )
        assertTrue(
            "and the press selects it with the episode load",
            posterRow.contains("onSeasonSelected(season)")
        )
        assertTrue(
            "long press opens the same season menu",
            posterRow.contains("rememberLongPressModifier(") &&
                posterRow.contains("onSeasonLongPress(")
        )
        assertTrue(
            "the row's requesters are still filed per season, so the landing can find them",
            posterRow.contains("seasonFocusRequesters[") &&
                posterRow.contains("FocusRequester()")
        )
        assertTrue(
            "and the page's own vertical press is still what the item answers",
            posterRow.contains("onPreviewKeyEvent(chipVerticalFocus)")
        )
    }

    @Test
    fun `a season that has not aired withholds the menu, not the selection`() {
        val posterRow = posterRowSource()
        assertTrue(
            "no menu for a season with no episodes to mark",
            posterRow.contains("seasonUnavailable || episodeNumbers.isNullOrEmpty()")
        )
        // The press belongs to the item, not to the poster: a season whose poster
        // no source answered is still a caption you can select.
        assertTrue("the press is on the item", posterRow.contains(".clickable {"))
        assertTrue(
            "and the click does not test the poster first",
            posterRow.substringAfter(".clickable {").substringBefore("}")
                .contains("onSeasonSelected(season)")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    /** The SeasonPosterRow composable, which the item calls. */
    private fun posterRowSource(): String {
        val start = detail.indexOf("private fun SeasonPosterRow(")
        assertTrue("the poster row is composed", start >= 0)
        val end = detail.indexOf("private fun ", start + 10)
        return detail.substring(start, if (end > start) end else detail.length)
    }

    private fun read(fileName: String): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/detail/$fileName"
        ).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }

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
        const val DETAIL_FILE = "DetailScreen.kt"
    }
}
