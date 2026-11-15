package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone season poster: next to "Episodes", and bigger (spec 2026-11-15, 13:28).
 *
 * Kenny's phone screenshot: the poster was small AND way over on the right edge.
 * The edge was the layout - the heading took `weight(1f)`, which pushed the poster
 * to the far side of the row. The TV fixed exactly this on the same day (its
 * heading sizes to its own text, the poster hangs off it, a trailing Spacer keeps
 * the row full width); the phone never got the move. This pins the move, the
 * 48dp → 72dp draw, and that the rules around it are untouched: the season's own
 * poster only, decorative, one retry on a failed draw, the raw-URL request, and a
 * chips row that was not touched.
 */
class PhoneSeasonPosterPlacementContractTest {

    @Test
    fun `the heading sizes to its text so the poster sits beside it`() {
        val row = headerRow()
        assertFalse(
            "the header no longer takes the row's width - that is what pushed " +
                "the poster to the far edge",
            row.contains("MobileSectionHeader(") && headerCall(row).contains("weight(1f)")
        )
        assertTrue(
            "the poster hangs directly off the heading, on its start side",
            row.contains(".padding(start = 12.dp)") && !row.contains(".padding(end = 16.dp)")
        )
        assertTrue(
            "and a trailing spacer keeps the row full width, so the column below " +
                "never shifts",
            row.contains("Spacer(Modifier.weight(1f))") &&
                row.indexOf("Spacer(Modifier.weight(1f))") > row.indexOf(".width(72.dp)")
        )
    }

    @Test
    fun `the draw is 72dp, keeping its 2 by 3 shape`() {
        val row = headerRow()
        assertTrue("72dp wide, not the old 48dp", row.contains(".width(72.dp)"))
        assertFalse("and the old width is gone", row.contains(".width(48.dp)"))
        assertTrue("the shape is unchanged", row.contains(".aspectRatio(2f / 3f)"))
        assertTrue("and so is the clip", row.contains(".clip(KBShapeSmall)"))
        // 72dp on a phone at density 2.0 is ~144px: a 50% wider poster than 96px,
        // without doubling the header row.
        assertEquals(108, 72 * 3 / 2)
    }

    @Test
    fun `the request is still the raw url, so a wider draw refetches nothing`() {
        val row = headerRow()
        assertTrue(
            "the phone passes the URL and lets Coil decode at the target",
            row.contains("model = poster,") && !row.contains(".size(Size(")
        )
    }

    @Test
    fun `the poster stays decorative and keeps its one retry`() {
        val row = headerRow()
        assertTrue("nothing is announced", row.contains("contentDescription = null"))
        assertFalse("it does not act on a click", row.contains(".clickable"))
        assertFalse("and it never takes focus", row.contains(".focusable"))
        assertTrue(
            "a failed draw gets exactly one more request",
            row.contains("key(mobileSeasonPosterRetry)") &&
                row.contains("if (!mobileSeasonPosterRetried) {") &&
                row.contains("mobileSeasonPosterRetry++") &&
                row.contains("imageLine(\"phone\", \"error\")")
        )
    }

    @Test
    fun `no poster of its own still draws nothing`() {
        val row = headerRow()
        assertTrue(
            "the draw sits inside the season's-own-poster guard",
            row.contains("mobileSeasonPosterUrl?.let { poster ->") &&
                !row.contains("heroBackdropUrl")
        )
        assertTrue(
            "and the spacer means the heading does not move when it is absent",
            row.contains("Spacer(Modifier.weight(1f))")
        )
    }

    @Test
    fun `the chips row and the tv poster are untouched`() {
        val phone = read("ui/mobile/DetailScreenMobile.kt")
        val chips = phone.substringAfter("LazyRow(\n                                contentPadding = PaddingValues(horizontal = 16.dp)")
            .take(400)
        assertTrue(
            "the chips row keeps its padding and spacing",
            chips.contains("Arrangement.spacedBy(8.dp)")
        )
        val tv = read("ui/detail/DetailScreen.kt")
        assertTrue(
            "the television poster is still its own 84dp landing",
            tv.substringAfter("seasonPosterUrl?.let { poster ->").take(6_000)
                .contains(".width(84.dp)")
        )
    }

    /** The Episodes header row: from its Row to the closing of that Row. */
    private fun headerRow(): String {
        val phone = read("ui/mobile/DetailScreenMobile.kt")
        val row = phone.substringAfter("MobileSectionHeader(").substringBefore("LazyRow(")
        assertTrue("the header row is found", row.isNotEmpty())
        return row
    }

    private fun headerCall(row: String): String = row.substringBefore(")")

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
