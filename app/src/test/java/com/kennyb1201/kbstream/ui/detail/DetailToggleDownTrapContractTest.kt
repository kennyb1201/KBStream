package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overview's "View more" toggle trapping DOWN (Dark Marvels, 2026-11-07).
 *
 * The genre-chip trap of 588dc5b86 was the same page and the row above this one;
 * this is the adjacent control. UP onto the toggle worked, DOWN back off it did
 * nothing at all - and the two things that made it were both in the handler:
 *
 *  1. it asked the chain with the presence it was COMPOSED with. The overview
 *     item draws before the seasons stream in, so that snapshot said the page had
 *     no rows below; the chain answered null and the fall-through called
 *     HEADER+DOWN a page edge, because a page with no row below really is one.
 *     The screen already publishes the landed presence to `latestRowPresence` for
 *     exactly this reason (see `awaitRowsThenMove`), and the toggle was not
 *     reading it.
 *  2. it consumed the press unconditionally, so the key was gone whether the
 *     move landed or not - "consumed but unmoved", the reported trap.
 *
 * The rule half is [DetailFocusChain.toggleDownIsConsumed] (pure, tested as
 * behaviour in [DetailFocusChainTest]); what is pinned here is the wiring, which
 * is a key handler and needs a device and a remote to press.
 */
class DetailToggleDownTrapContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The toggle's handler. */
    private val toggleHandler: String by lazy {
        detail.substringAfter("fun overviewToggleVerticalFocus(")
            .substringBefore("suspend fun focusFirstMediaCard(")
    }

    /** The page's single vertical handoff, where the UP route onto the toggle lives. */
    private val move: String by lazy {
        detail.substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
    }

    /** A presence with just the rows a case cares about. */
    private fun presence(episodes: Boolean = false) = DetailRowPresence(
        hasGenreRow = false,
        hasKeywordsRow = false,
        hasEpisodesRow = episodes,
        hasPeopleRow = false,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMediaRow = false,
        hasMoreLikeThisRow = false
    )

    /** The shared wrapper, which the toggle no longer uses - but others still do. */
    private val sharedWrapper: String by lazy {
        detail.substringAfter("fun nonRailVerticalFocus(")
            .substringBefore("fun nonRailVerticalFocusWithDownOutcome(")
    }

    // ── F1: the press is answered with the rows that landed ────────────────

    @Test
    fun `the toggle's DOWN resolves the presence at press time`() {
        assertTrue(
            "the captured presence becomes only the FALLBACK - the shape " +
                "awaitRowsThenMove already uses, for the same reason: a press for " +
                "a still-arriving section has to re-ask the chain with the " +
                "presence that landed",
            toggleHandler.contains(
                "val settled = latestRowPresence.value ?: presence"
            )
        )
    }

    @Test
    fun `the resolved presence is what the move itself reads`() {
        assertTrue(
            "the chain and the fall-through are asked with the settled presence, " +
                "not the composition-time one - with the stale list, HEADER+DOWN " +
                "read as a page edge and the press had nowhere to go",
            toggleHandler.contains(
                "moveFocusVertical( DetailRow.HEADER, DetailFocusDirection.DOWN, " +
                    "0, latestRowPresence.value ?: settled )"
            )
        )
    }

    // ── F2: a DOWN the page cannot place is not consumed ──────────────────

    @Test
    fun `the toggle's DOWN decides whether the press is the page's to keep`() {
        assertTrue(
            "the toggle goes through the wrapper that returns the DOWN's " +
                "outcome instead of a constant true",
            toggleHandler.contains("nonRailVerticalFocusWithDownOutcome(")
        )
        assertTrue(
            "and the decision is the chain's, made from the settled presence plus " +
                "whether rows are still on the way: absence while loading is " +
                "\"not yet\", never an edge",
            toggleHandler.contains(
                "DetailFocusChain.toggleDownIsConsumed( presence = settled, " +
                    "rowsStillArriving = rowsStillArriving() )"
            )
        )
    }

    @Test
    fun `a press the page cannot place starts nothing and is handed back`() {
        val notKept = toggleHandler
            .substringAfter("toggle DOWN not kept")
            .substringBefore("} else {")
        assertFalse(
            "no move may be started for a press the page is about to hand back",
            notKept.contains("startFocusMove")
        )
        assertTrue(
            "and the branch RETURNS false, which is what gives the press to the " +
                "platform's default search - the rule moveFocusVertical itself " +
                "states, rather than a key the page took and did nothing with",
            notKept.contains("false")
        )
    }

    @Test
    fun `a press the page can place is consumed and moved`() {
        assertTrue(
            "the kept branch runs the move through the chain's HEADER position and " +
                "returns true",
            toggleHandler.contains(
                "} else { startFocusMove { moveFocusVertical( DetailRow.HEADER, " +
                    "DetailFocusDirection.DOWN, 0, latestRowPresence.value ?: settled )"
            )
        )
    }

    @Test
    fun `the wrapper answers DOWN with the outcome it was given`() {
        val outcomeWrapper = detail
            .substringAfter("fun nonRailVerticalFocusWithDownOutcome(")
            .substringBefore("fun overviewToggleVerticalFocus(")
        assertTrue(
            "DOWN is the caller's answer - not called for its effect and then " +
                "consumed anyway",
            outcomeWrapper.contains("Key.DirectionDown -> onDown()")
        )
        assertTrue(
            "UP is untouched: it is always the page's press, because the header is " +
                "always above the list",
            outcomeWrapper.contains("Key.DirectionUp -> { onUp() true }")
        )
    }

    // ── F2b: the shared wrapper's contract is unchanged ───────────────────

    @Test
    fun `the other non-rail surfaces keep the consuming wrapper`() {
        assertEquals(
            "the header's action buttons and the season chips still use the plain " +
                "wrapper, plus its definition - one control's DOWN does not " +
                "redefine the others'",
            3,
            Regex("nonRailVerticalFocus\\(").findAll(detail).count()
        )
        assertTrue(
            "and that wrapper still consumes its DOWN, as they rely on",
            sharedWrapper.contains("Key.DirectionDown -> { onDown() true }")
        )
    }

    // ── T4: the genre-chip handoff and the UP route are untouched ─────────

    @Test
    fun `the genre chips still hand DOWN to the episode section`() {
        assertEquals(
            "588dc5b86's handoff is intact - DOWN off a genre chip still steps " +
                "over the tags to the episodes on a title that has none",
            DetailRow.EPISODES,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(episodes = true)
            )
        )
        assertTrue(
            "and the UP route onto the toggle is still the page's single handoff, " +
                "read from the chain, so no rail can disagree about it",
            move.contains(
                "direction == DetailFocusDirection.UP && " +
                    "from == firstListRow(presence) && focusOverviewToggle()"
            )
        )
    }

    @Test
    fun `UP out of the first row still lands on the toggle`() {
        assertEquals(
            "the declaration, the UP route and the DOWN last resort: the toggle " +
                "is still the UP-only stop, and only its DOWN changed",
            3,
            Regex("focusOverviewToggle\\(\\)").findAll(detail).count()
        )
        assertTrue(
            "and UP still leaves it for the header",
            toggleHandler.contains("onUp = { startFocusMove { focusTopOfPage() } }")
        )
        assertTrue(
            "the captured presence is still what the handler is handed - it is the " +
                "fallback now, and a second source of truth would be worse than one",
            detail.contains("overviewToggleVerticalFocus( detailRowPresence )")
        )
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

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
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
