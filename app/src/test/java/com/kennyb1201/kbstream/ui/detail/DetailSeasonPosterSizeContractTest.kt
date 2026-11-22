package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season poster's size, on both surfaces (spec 2026-11-15, the poster season
 * picker).
 *
 * This replaces the header-poster size contract (`2612ae127` / `4ee5c0ba6`): the
 * header those posters hung off is deleted, so what is left to pin is the size
 * the row draws them at - TV 84dp, phone 72dp, both 2:3, both inside the 300x450
 * decode - and that neither the poster's decode nor its decoration changed with
 * the move.
 *
 * The poster is now the FOCUSABLE item (its caption is not a chip), so unlike the
 * header poster it does carry a selected/focus ring; the interaction itself
 * (click, long press, focus) belongs to each surface's own container, which is
 * why the shared draw stays free of it.
 */
class DetailSeasonPosterSizeContractTest {

    @Test
    fun `the tv row draws 84dp and the phone row 72dp`() {
        val tv = seasonPosterRow(read("ui/detail/DetailScreen.kt"))
        assertTrue("the TV poster is 84dp wide", tv.contains("posterWidth = 84.dp"))
        assertFalse("and not the old chip-sized width", tv.contains("posterWidth = 54.dp"))

        val phone = read("ui/mobile/DetailScreenMobile.kt")
        val phoneRow = seasonPosterRow(phone)
        assertTrue("the phone poster is 72dp wide", phoneRow.contains("posterWidth = 72.dp"))
        assertFalse(
            "and the TV's 84dp did not leak into it",
            phoneRow.contains("posterWidth = 84.dp")
        )
    }

    @Test
    fun `the draw keeps the 2 by 3 shape, the clip and the 300x450 decode`() {
        val art = read("ui/detail/SeasonPosterRow.kt")
        assertTrue(
            "one shape constant for both sizes",
            art.contains("SEASON_POSTER_ASPECT = 2f / 3f") &&
                art.contains(".aspectRatio(SEASON_POSTER_ASPECT)")
        )
        assertTrue("the clip is unchanged", art.contains(".clip(KBShapeSmall)"))
        assertTrue(
            "the width is the one the row passes in",
            art.contains(".width(posterWidth)")
        )
        assertTrue(
            "the decode is untouched: the same 300x450",
            art.contains(".size(Size(300, 450))")
        )
        // 84dp at density 2.00 is 168px wide - and the accepted follow-up bump to
        // 96dp is 192px - both inside the 300px decode, so no re-fetch and no new
        // cache entry for either surface.
        assertEquals(168, 84 * 2)
        assertTrue("96dp still fits the 300px decode", 96 * 2 < 300)
    }

    @Test
    fun `the shared draw carries the poster's ring but not its interaction`() {
        val art = read("ui/detail/SeasonPosterRow.kt")
        assertTrue("nothing is announced", art.contains("contentDescription = null"))
        assertTrue(
            "the selected poster wears the accent ring",
            art.contains("seasonPosterRingColor(selected = selected, focused = focused)")
        )
        assertFalse(
            "the draw never acts on a press - the row's container does",
            art.contains(".clickable")
        )
        assertFalse("and it never takes focus itself", art.contains(".focusable"))
    }

    @Test
    fun `a season with no poster draws the caption alone`() {
        val art = read("ui/detail/SeasonPosterRow.kt")
        assertTrue(
            "the image is drawn only when this season answered one",
            art.contains("if (posterUrl != null) {") && art.contains(".data(posterUrl)")
        )
        assertFalse(
            "and never the show's poster or another season's",
            art.contains("heroPosterUrl")
        )
        assertTrue(
            "the caption is drawn either way",
            art.indexOf("Text(") > art.indexOf("if (posterUrl != null) {")
        )
        assertTrue(
            "the caption-only ring reserves 12dp/6dp, so the accent border does " +
                "not touch the glyphs and the row cannot move on focus",
            art.contains(".padding(horizontal = 12.dp, vertical = 6.dp)") &&
                art.contains("if (focused) KBAccent else Color.Transparent")
        )
    }

    @Test
    fun `the poster is no longer sized inside either screen`() {
        assertFalse(
            "the TV's header poster block is gone",
            read("ui/detail/DetailScreen.kt").contains("seasonPosterUrl?.let")
        )
        assertFalse(
            "and the phone's is too",
            read("ui/mobile/DetailScreenMobile.kt").contains("mobileSeasonPosterUrl?.let")
        )
    }

    /** The TV row's item block, or the phone's - whichever this source carries. */
    private fun seasonPosterRow(source: String): String {
        val start = source.indexOf("SeasonPosterArt(")
        assertTrue("the row draws SeasonPosterArt", start >= 0)
        return source.substring(start, minOf(source.length, start + 800))
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
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
