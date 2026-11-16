package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's poster season picker (spec 2026-11-15, the poster season picker, F2).
 *
 * This replaces the phone header-poster contract: the "Episodes" heading row (the
 * heading, the 72dp poster beside it, the trailing spacer) and the LazyRow of
 * `TouchChoiceChip`s are deleted. In their place, in the same position, one
 * horizontal row where each season is its own poster with a "SEASON N" caption
 * under it - the same row the TV page draws, touch-sized.
 *
 * Pinned here: no heading and no chip in the section; the row is still one item;
 * a tap selects the season and loads its episodes; a long press still opens the
 * season menu; the poster keeps its 72dp draw and its one retry; and a season
 * with no poster of its own reads as a tappable caption with no empty frame.
 */
class PhoneSeasonPosterPlacementContractTest {

    private val phone by lazy { read("ui/mobile/DetailScreenMobile.kt") }

    /** The season row item, through to the episode-order row that follows it. */
    private val row: String by lazy {
        phone.substringAfter("item(key = \"seasons\")")
            .substringBefore("item(key = \"episode-order\")")
    }

    @Test
    fun `the heading and the chips are gone`() {
        assertFalse(
            "no Episodes heading is composed from the section header",
            row.contains("MobileSectionHeader(")
        )
        assertFalse("no season chip is composed", row.contains("TouchChoiceChip("))
        assertFalse("no spacer holds the old header row open", row.contains("Spacer(Modifier"))
        assertEquals("one season row", 1, phone.count("item(key = \"seasons\")"))
    }

    @Test
    fun `each season is a poster with a caption under it`() {
        assertTrue("the row is horizontal", row.contains("LazyRow("))
        assertTrue("one item per season", row.contains("items(") && row.contains("seasons,"))
        assertTrue("keyed by season number", row.contains("key = { it.seasonNumber }"))
        assertTrue("each item draws the shared poster unit", row.contains("SeasonPosterArt("))
        assertTrue(
            "the caption is the season's name, else its number - the chip's label",
            row.contains("summary.name") && row.contains("\"Season \$seasonNumber\"")
        )
    }

    @Test
    fun `a tap selects and loads, a long press opens the menu`() {
        assertTrue("the press is the item's", row.contains(".combinedClickable("))
        assertTrue(
            "tap selects the season",
            row.contains("season = seasonNumber")
        )
        assertTrue(
            "and loads that season's episodes, exactly as the chip did",
            row.contains("viewModel.loadEpisodesForSeason(")
        )
        assertTrue(
            "long press opens the same season menu",
            row.contains("onLongClick =") && row.contains("seasonMenu = SeasonMenuTarget(")
        )
    }

    @Test
    fun `the poster keeps its 72dp draw and its one retry`() {
        assertTrue("72dp wide", row.contains("posterWidth = 72.dp"))
        assertFalse("not the TV's size", row.contains("posterWidth = 84.dp"))
        // The retry and the trace line live in the shared draw now, once for both
        // surfaces, instead of being copied into each screen.
        val art = read("ui/detail/SeasonPosterRow.kt")
        assertTrue("one retry on a failed draw", art.contains("if (!retried) {"))
        assertTrue("and the trace records it", art.contains("imageLine(surface, \"error\")"))
        assertTrue(
            "the phone still labels its own draws",
            row.contains("surface = \"phone\"")
        )
    }

    @Test
    fun `the poster is resolved per season, not for the one header slot`() {
        assertTrue(
            "each item asks for its own season's poster",
            row.contains("rememberSeasonPoster(") && row.contains("season = seasonNumber")
        )
        assertTrue(
            "through the shared resolver the page holds",
            phone.contains("rememberSeasonPosterResolver(")
        )
        assertFalse(
            "and the single header poster's state is gone",
            phone.contains("mobileSeasonPosterUrl")
        )
    }

    @Test
    fun `the tv poster is its own landing`() {
        val tv = read("ui/detail/DetailScreen.kt")
        assertTrue("the television poster is 84dp", tv.contains("posterWidth = 84.dp"))
        assertTrue(
            "and it is the same shared unit, not a second copy",
            tv.contains("SeasonPosterArt(")
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

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }
}
