package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poster-less season's ring has to be OUTSIDE its padding (spec 2026-11-25).
 *
 * Two photos told the same story: "SEASON 1" focused with the accent ring
 * touching the glyphs. The first fix widened the reservation from 6dp/3dp to
 * 12dp/6dp, and the photo did not change - because a modifier chain is applied
 * OUTSIDE-IN in the order it is written. The padding was listed before the
 * border, so all of it landed outside the ring: a gap to the neighbouring
 * season, never to the text. The visible gap was the font's own side bearings.
 *
 * So what is pinned here is ORDER, and then the gap that order produces:
 * the border first, the padding inside it, and the padding large enough that
 * 12dp/6dp minus the 2dp stroke is still 10dp horizontally and 4dp vertically.
 *
 * The measurement is arithmetic rather than rendered, for the reason
 * `UpdateDialogLabelContractTest` gives for the same choice: there is no Compose
 * runtime in this JVM suite. What the render depends on - which modifier is
 * outermost, and how much space the inner one reserves - is exactly what is
 * checked, and the old order fails the first assertion.
 */
class SeasonPosterCaptionRingGapContractTest {

    /** The caption-only branch: the one that draws a ring around the text. */
    private val captionOnly: String by lazy {
        val art = read("ui/detail/SeasonPosterRow.kt")
        val start = art.indexOf("if (posterUrl == null) {")
        assertTrue("the caption-only branch must exist", start >= 0)
        val end = art.indexOf("} else {", start)
        assertTrue("the branch must end at its else", end > start)
        art.substring(start, end).replace(Regex("\\s+"), " ")
    }

    @Test
    fun `the ring is outside the padding, not the other way round`() {
        val border = captionOnly.indexOf(".border(")
        val padding = captionOnly.indexOf(".padding(horizontal =")
        assertTrue(
            "the branch must carry both a ring and its reserved space: $captionOnly",
            border >= 0 && padding >= 0
        )
        assertTrue(
            "a chain applies outside-in, so the border has to be listed first - " +
                "with the padding first the space lands outside the ring and the " +
                "glyphs keep touching it (this is the 2026-11-25 photo): $captionOnly",
            border < padding
        )
    }

    @Test
    fun `the padding inside the ring leaves a real gap to the glyphs`() {
        val padded = Regex("padding\\(horizontal = (\\d+)\\.dp, vertical = (\\d+)\\.dp\\)")
            .find(captionOnly)
        val stroke = Regex("border\\( (\\d+)\\.dp,").find(captionOnly)
        assertTrue("the ring's padding must be literal dp: $captionOnly", padded != null)
        assertTrue("and its stroke too: $captionOnly", stroke != null)

        val horizontal = padded!!.groupValues[1].toInt()
        val vertical = padded.groupValues[2].toInt()
        // The stroke draws INSIDE its bounds, so what the eye sees between the
        // glyphs and the ring is the padding minus the stroke.
        val ringStroke = stroke!!.groupValues[1].toInt()
        assertTrue(
            "the horizontal gap is the padding less the stroke: " +
                "$horizontal - $ringStroke dp",
            horizontal - ringStroke >= MIN_HORIZONTAL_GAP_DP
        )
        assertTrue(
            "and the vertical gap likewise: $vertical - $ringStroke dp",
            vertical - ringStroke >= MIN_VERTICAL_GAP_DP
        )
    }

    @Test
    fun `focusing a poster-less season still cannot resize it`() {
        assertTrue(
            "the space is reserved unconditionally - one literal padding call, never " +
                "a focus-gated one - or the item grows on focus and pushes the row: " +
                captionOnly,
            captionOnly.split(".padding(horizontal = 12.dp, vertical = 6.dp)").size - 1 == 1
        )
        assertTrue(
            "and the ring is in the chain in both states, transparent until focused",
            captionOnly.contains("if (focused) KBAccent else Color.Transparent")
        )
    }

    @Test
    fun `the poster item's own spacing and ring are untouched`() {
        val art = read("ui/detail/SeasonPosterRow.kt")
        assertTrue(
            "a caption under a poster keeps its 6dp top padding, and the base " +
                "4dp sides stay outermost for both shapes",
            art.contains("top = if (posterUrl != null) 6.dp else 0.dp") &&
                art.contains("start = 4.dp")
        )
        assertTrue(
            "the poster's ring is still the shared season cue",
            art.contains("seasonPosterRingColor(selected = selected, focused = focused)")
        )
        assertTrue(
            "and the caption-only branch is the only place the text gets a ring",
            (art.split("KBShapeSmall").size - 1) >= 3
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private companion object {
        /** 12dp of padding less the 2dp stroke, as the spec's field check reads it. */
        const val MIN_HORIZONTAL_GAP_DP = 10
        const val MIN_VERTICAL_GAP_DP = 4
    }
}
