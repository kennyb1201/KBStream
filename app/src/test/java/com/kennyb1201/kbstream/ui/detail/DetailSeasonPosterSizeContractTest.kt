package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bigger season poster on TV Detail (spec 2026-11-15, 13:21).
 *
 * Kenny: the season poster beside EPISODES is hard to see. It drew 54dp wide -
 * ~108px at the 1080p TV's density 2.00 - while the decoded art was already
 * 300x450, so the draw was the only thing small. This pins the widened draw at
 * 84dp (84 x 126, keeping 2:3), that the decode did NOT change with it (further
 * enlargement up to 96dp stays inside 300px, so no re-fetch and no new cache
 * entry), and that the rules around it are untouched: the season's own poster
 * only, decorative and non-focusable, and the phone's own 48dp poster left alone.
 */
class DetailSeasonPosterSizeContractTest {

    @Test
    fun `the tv season poster draws at 84dp, keeping its 2 by 3 shape`() {
        val screen = read("ui/detail/DetailScreen.kt")
        val draw = posterDraw(screen)
        assertTrue("84dp wide, not the old 54dp", draw.contains(".width(84.dp)"))
        assertFalse(
            "and the old width is gone from the whole screen",
            screen.contains(".width(54.dp)")
        )
        assertTrue("the shape is unchanged", draw.contains(".aspectRatio(2f / 3f)"))
        assertTrue("and so is the clip", draw.contains(".clip(KBShapeSmall)"))
    }

    @Test
    fun `the draw is still inside the decode, so nothing is re-fetched`() {
        val draw = posterDraw(read("ui/detail/DetailScreen.kt"))
        assertTrue(
            "the request is untouched: the same 300x450",
            draw.contains(".size(Size(300, 450))")
        )
        // 84dp at density 2.00 is 168px wide, and even the spec's follow-up bump to
        // 96dp is 192px - both inside the 300px decode, which is why F2 needs no
        // change and the image cache gains no entry.
        val widthAtDensity2 = 96 * 2
        assertTrue(
            "96dp - the accepted follow-up - still fits the 300px decode",
            widthAtDensity2 < 300
        )
    }

    @Test
    fun `the poster stays decorative - no description, no click, no ring`() {
        val draw = posterDraw(read("ui/detail/DetailScreen.kt"))
        assertTrue("nothing is announced", draw.contains("contentDescription = null"))
        assertFalse("it does not act on a click", draw.contains(".clickable"))
        assertFalse("and it never takes focus", draw.contains(".focusable"))
        assertFalse("so it carries no focus ring", draw.contains(".border("))
    }

    @Test
    fun `a season with no poster of its own still draws nothing`() {
        val screen = read("ui/detail/DetailScreen.kt")
        val draw = posterDraw(screen)
        assertTrue(
            "the draw is inside the season's-own-poster guard",
            draw.contains(".data(poster)") && !draw.contains("heroPosterUrl")
        )
        assertTrue(
            "and the guard is the season answer, not the show's art",
            screen.contains("seasonPosterUrl?.let { poster ->")
        )
    }

    @Test
    fun `the phone's poster is its own spec, not this one`() {
        // The phone has its own season poster and its own spec (72dp, moved next
        // to its heading). What this test guards is that the TV's 84dp did not
        // leak into it: the two surfaces are sized by separate landings.
        val phone = read("ui/mobile/DetailScreenMobile.kt")
        assertTrue(
            "the phone keeps its own width",
            phone.contains(".width(72.dp)\n                                                .aspectRatio(2f / 3f)")
        )
        assertFalse(
            "and did not inherit the TV's bump",
            phone.substringAfter("SeasonPosterTrace.imageLine(\"phone\"").take(1_500)
                .contains(".width(84.dp)")
        )
    }

    /** The poster's draw block: from the season guard to the row's trailing Spacer. */
    private fun posterDraw(screen: String): String {
        val draw = screen.substringAfter("seasonPosterUrl?.let { poster ->")
            .substringBefore("Spacer(Modifier.weight(1f))")
        assertTrue("the poster draw block is found", draw.isNotEmpty())
        return draw
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
