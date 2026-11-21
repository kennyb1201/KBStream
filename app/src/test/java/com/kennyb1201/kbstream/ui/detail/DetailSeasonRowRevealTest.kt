package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail spec 2026-11-17, F3: the season poster row is never half-cut.
 *
 * Two halves, and only one of them can be checked here. The rule - how far the
 * list must scroll to leave the poster row whole beside the row the hand is on -
 * is arithmetic on rectangles, so it is asserted exactly. The wiring (that each
 * neighbour row asks, and that the strip no longer draws its "RATINGS" header)
 * is read from the source, because a focus landing is a device and a D-pad away.
 *
 * Nothing in this class claims a frame was drawn: the field tests S1-S5 are
 * Kenny's, and the page can be verified only on the TV.
 */
class DetailSeasonRowRevealTest {

    // ── the rule ──────────────────────────────────────────────────────────

    @Test
    fun `a poster row that is already whole costs no scroll`() {
        assertEquals(
            0,
            seasonRowRevealScroll(
                seasonStart = 300,
                seasonEnd = 450,
                neighbourStart = 200,
                neighbourEnd = 290,
                viewportStart = 0,
                viewportEnd = 1000
            )
        )
    }

    @Test
    fun `a poster row cut at the bottom is revealed from above, by the cut only`() {
        val delta = seasonRowRevealScroll(
            seasonStart = 900,
            seasonEnd = 1060,
            neighbourStart = 800,
            neighbourEnd = 890,
            viewportStart = 0,
            viewportEnd = 1000
        )
        assertEquals("forward by exactly the 60px the viewport was eating", 60, delta)
        // After the scroll every item has moved up by `delta`.
        assertEquals("the poster row ends at the viewport's edge", 1000, 1060 - delta)
        assertEquals("and the focused row above is still fully visible", 740, 800 - delta)
        assertEquals(830, 890 - delta)
    }

    @Test
    fun `a poster row cut at the top is revealed from below, by the cut only`() {
        val delta = seasonRowRevealScroll(
            seasonStart = -40,
            seasonEnd = 110,
            neighbourStart = 150,
            neighbourEnd = 300,
            viewportStart = 0,
            viewportEnd = 1000
        )
        assertEquals("backward by exactly the 40px above the viewport", -40, delta)
        assertEquals("the poster row starts at the viewport's edge", 0, -40 - delta)
        assertEquals("and the focused episode row below is still fully visible", 190, 150 - delta)
        assertEquals(340, 300 - delta)
    }

    @Test
    fun `a leading content padding is part of the viewport it is measured against`() {
        // The list has a 6dp top padding: item offsets start at a negative
        // viewportStart, and a row flush with the padding is already whole.
        assertEquals(
            0,
            seasonRowRevealScroll(
                seasonStart = 0,
                seasonEnd = 150,
                neighbourStart = -16,
                neighbourEnd = 0,
                viewportStart = -16,
                viewportEnd = 1000
            )
        )
    }

    @Test
    fun `when both rows cannot fit, the focused row wins and the poster takes the sliver`() {
        // The 2026-11-17 photo's case, in numbers: the episode row below the
        // poster row is the one the hand is on, cut at the viewport's bottom edge
        // by 20px. The neighbour's cut is the scroll; the poster row is the row
        // left cut afterwards - which is the whole point of F1.
        val decision = seasonRowRevealDecision(
            seasonStart = 0,
            seasonEnd = 150,
            neighbourStart = 160,
            neighbourEnd = 260,
            viewportStart = 0,
            viewportEnd = 240
        )
        assertEquals("the focused row's cut is the one fixed", 20, decision.delta)
        assertEquals(SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR, decision.branch)
        assertEquals("the focused row ends at the viewport's edge", 240, 260 - decision.delta)
        assertTrue(
            "and the poster row is the row left cut",
            0 - decision.delta < 0
        )
    }

    @Test
    fun `a focused row cut at the top is revealed from below, not left cut`() {
        val decision = seasonRowRevealDecision(
            seasonStart = 40,
            seasonEnd = 190,
            neighbourStart = -60,
            neighbourEnd = 40,
            viewportStart = 0,
            viewportEnd = 200
        )
        assertEquals("backward by exactly the focused row's 60px", -60, decision.delta)
        assertEquals(SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR, decision.branch)
        assertEquals("the focused row starts at the viewport's edge", 0, -60 - decision.delta)
    }

    @Test
    fun `a focused row that is already whole in a dont-fit page costs no scroll`() {
        val decision = seasonRowRevealDecision(
            seasonStart = 100,
            seasonEnd = 220,
            neighbourStart = 0,
            neighbourEnd = 130,
            viewportStart = 0,
            viewportEnd = 200
        )
        assertEquals("nothing to fix on the row the hand is on", 0, decision.delta)
        assertEquals(SEASON_ROW_REVEAL_DONT_FIT_NEIGHBOUR, decision.branch)
    }

    @Test
    fun `the fit case still names itself and still costs nothing when settled`() {
        val decision = seasonRowRevealDecision(
            seasonStart = 300,
            seasonEnd = 450,
            neighbourStart = 200,
            neighbourEnd = 290,
            viewportStart = 0,
            viewportEnd = 1000
        )
        assertEquals(SEASON_ROW_REVEAL_FIT, decision.branch)
        assertEquals(0, decision.delta)
    }

    @Test
    fun `a poster row taller than the viewport is left alone rather than chased`() {
        assertEquals(
            0,
            seasonRowRevealScroll(
                seasonStart = 0,
                seasonEnd = 200,
                neighbourStart = 0,
                neighbourEnd = 10,
                viewportStart = 0,
                viewportEnd = 100
            )
        )
    }

    // ── the wiring ────────────────────────────────────────────────────────

    @Test
    fun `every neighbour row asks for the reveal`() {
        val detail = stripComments(detailSource()).replace(Regex("\\s+"), "")
        listOf(
            "RATINGS_ROW_KEY",
            "DETAIL_FACTS_ROW_KEY",
            "KEYWORDS_ROW_KEY",
            "EPISODE_ORDER_ROW_KEY",
            "EPISODES_ROW_KEY"
        ).forEach { key ->
            assertTrue(
                "no reveal on $key",
                detail.contains(".revealSeasonRowOnFocus(seasonRowReveal,$key)")
            )
        }
    }

    @Test
    fun `the reveal measures the poster row and waits for the landing to settle`() {
        val reveal = normalized(stripComments(detailSource()))
            .substringAfter("private class SeasonRowReveal(")
            .substringBefore("private enum class EpisodeFocusEdge")
        assertTrue("it measures the poster row's item", reveal.contains("it.key == SEASON_ROW_KEY"))
        assertTrue("the rule decides the delta", reveal.contains("seasonRowRevealDecision("))
        assertTrue("and it applies it as a scroll", reveal.contains("listState.animateScrollBy("))
        assertTrue(
            "after the focus landing has stopped moving the list",
            reveal.contains("listState.isScrollInProgress") &&
                reveal.contains("SEASON_ROW_REVEAL_SETTLE_FRAMES")
        )
        assertTrue(
            "with a collapsed, bounded number of passes",
            reveal.contains("SEASON_ROW_REVEAL_PASSES")
        )
    }

    @Test
    fun `a mid-flight zero does not end the reveal`() {
        val reveal = normalized(stripComments(detailSource()))
            .substringAfter("private class SeasonRowReveal(")
            .substringBefore("private enum class EpisodeFocusEdge")
        // The old rule ended the whole job on the first zero, whatever it
        // measured. Now the zero has to be measured on a settled list.
        assertTrue(
            "a still-moving list falls through to the next pass",
            reveal.contains("if (listState.isScrollInProgress) {")
        )
        assertFalse(
            "no early return on a raw delta",
            reveal.contains("if (delta == 0) return@launch")
        )
        assertTrue(
            "an unmeasured pass is named, and only the last one is reported",
            reveal.contains("SEASON_ROW_REVEAL_UNMEASURED") &&
                reveal.contains("pass == SEASON_ROW_REVEAL_PASSES - 1")
        )
        assertTrue(
            "and a walk across rows re-measures the newest row, not the first",
            reveal.contains("requestedKey")
        )
    }

    @Test
    fun `the reveal files its decision in the diagnostics report`() {
        val reveal = normalized(stripComments(detailSource()))
        assertTrue(
            "every decision is recorded with its branch and its delta",
            reveal.contains("SeasonRowRevealTrace.record(") &&
                reveal.contains("branch = decision.branch") &&
                reveal.contains("delta = decision.delta")
        )
        assertTrue(
            "and a settled pass says so by name",
            reveal.contains("SEASON_ROW_REVEAL_SETTLED_ZERO")
        )
        val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        assertTrue(
            "the paste carries the lines",
            diagnostics.contains("SeasonRowRevealTrace.lines().forEach { report.appendLine(it) }")
        )
        val trace = source("com/kennyb1201/kbstream/data/reporting/SeasonRowRevealTrace.kt")
        assertTrue(
            "one prefixed line per decision, named by the neighbour row's key",
            trace.contains("\"detail: seasonRowReveal neighbour=\$neighbourKey branch=\$branch delta=\$delta\"")
        )
    }

    @Test
    fun `the ratings strip no longer draws a header`() {
        val strip = normalized(stripComments(
            source("com/kennyb1201/kbstream/ui/detail/DetailRatingsStrip.kt")
        ))
        assertFalse("the RATINGS label is gone", strip.contains("\"RATINGS\""))
        assertFalse("and its title style with it", strip.contains("titleSmall"))
        assertTrue("the chips are unchanged", strip.contains("MdbListRatingChips("))
        assertTrue(
            "and the strip only adds the tightened padding",
            strip.contains("top = 2.dp, bottom = 4.dp")
        )
    }

    @Test
    fun `the fact row sits closer to the ratings than it used to`() {
        val detail = normalized(stripComments(detailSource()))
        val facts = detail
            .substringAfter("item(key = DETAIL_FACTS_ROW_KEY")
            .substringBefore("if (activeTab == DetailTab.DETAILS && keywords.isNotEmpty())")
        assertTrue("no leading gap above the facts", facts.contains("top = 0.dp"))
        assertTrue("and a tighter trailing gap", facts.contains(".padding(bottom = 4.dp)"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun detailSource(): String =
        source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }

    /** Both comment shapes, so prose naming a call is not counted as the call. */
    private fun stripComments(text: String): String =
        text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")
}
