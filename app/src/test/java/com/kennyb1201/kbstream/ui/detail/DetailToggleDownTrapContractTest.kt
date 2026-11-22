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
    fun `both of the toggle's presses resolve the presence at press time`() {
        assertTrue(
            "the captured presence is only the FALLBACK - the shape " +
                "awaitRowsThenMove already uses, for the same reason: a press that " +
                "outlives the composition the handler was made with has to re-ask " +
                "the chain with the rows that landed",
            toggleHandler.contains(
                "val rows = latestRowPresence.value ?: presence"
            )
        )
    }

    @Test
    fun `the resolved presence is what the UP move itself reads`() {
        assertTrue(
            "UP asks the chain for the row above OVERVIEW with the settled rows, " +
                "so the chips are found when they exist and PLAY when they do not",
            toggleHandler.contains(
                "DetailFocusChain.nextRow( DetailRow.OVERVIEW, DetailFocusDirection.UP, rows )"
            )
        )
        assertTrue(
            "and the landing is the chain's target, through the same focusRow every " +
                "other row uses - not a hand-rolled list of stops",
            toggleHandler.contains(
                "focusRow(target, 0, DetailRow.OVERVIEW, DetailFocusDirection.UP, rows)"
            )
        )
    }

    // ── F2: a DOWN the page cannot place is not consumed ──────────────────

    @Test
    fun `the toggle's DOWN is placeable only when Details has a row below it`() {
        assertTrue(
            "the toggle still goes through the wrapper that returns the DOWN's own " +
                "outcome rather than a constant true",
            toggleHandler.contains("nonRailVerticalFocusWithDownOutcome(")
        )
        assertFalse(
            "with no row below and nothing arriving, the press goes back to the " +
                "platform search for the leaf fact cards",
            DetailFocusChain.toggleDownIsConsumed(
                presence(episodes = false),
                rowsStillArriving = false
            )
        )
        assertTrue(
            "but a row below keeps it",
            DetailFocusChain.toggleDownIsConsumed(
                presence(episodes = true),
                rowsStillArriving = false
            )
        )
    }

    @Test
    fun `the toggle's DOWN starts the move into the next Details row`() {
        assertTrue(
            "DOWN is a real move, started from the toggle's own chain row",
            toggleHandler.contains(
                "startFocusMove(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, rows)"
            )
        )
        assertTrue(
            "and it lands on the next chain row, with the geometric search as the " +
                "arm for a Details tab whose content is only leaf cards",
            toggleHandler.contains(
                "focusByGeometry(DetailFocusDirection.DOWN)"
            )
        )
    }

    @Test
    fun `a press the page can place is consumed and moved`() {
        assertTrue(
            "the handler consumes the DOWN it can place, and traces where it went " +
                "so the next paste can read it",
            toggleHandler.contains("detail toggle DOWN -> ") &&
                toggleHandler.contains("true")
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
            "the season chips still use the plain wrapper, plus its definition - " +
                "one control's DOWN does not redefine the others'. The header's " +
                "buttons left it on 2026-11-21 for a handler of their own, because " +
                "their UP is released to the shell (see the top bar test)",
            2,
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
                "over the tags: it now stops on the strip, which is what opens the " +
                "episode tab",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(episodes = true)
            )
        )
        assertFalse(
            "and the UP route onto the toggle is the CHAIN's now (2026-11-21), not a " +
                "special case in the move: content UP passes through the strip first, " +
                "so a first-row press that jumped straight onto the toggle would skip " +
                "the row painted directly above it",
            move.contains("focusOverviewToggle()")
        )
        assertEquals(
            "the row above the strip is the genre chips, not the overview below it",
            DetailRow.GENRE,
            DetailFocusChain.nextRow(
                DetailRow.TABS,
                DetailFocusDirection.UP,
                DetailRowPresence(
                    hasGenreRow = true,
                    hasOverviewRow = true,
                    hasKeywordsRow = false,
                    hasEpisodesRow = true,
                    hasPeopleRow = false,
                    hasNetworkRow = false,
                    hasProductionRow = false,
                    hasReviewsRow = false,
                    hasCollectionRow = false,
                    hasMediaRow = false,
                    hasMoreLikeThisRow = false
                )
            )
        )
    }

    @Test
    fun `UP out of the toggle is the strip, reached only through the chain`() {
        assertEquals(
            "one place lands on the toggle: focusRow's OVERVIEW arm. The move no longer " +
                "carries an extra route, and the handler is the other caller",
            2,
            Regex("focusOverviewToggle\\(\\)").findAll(detail).count()
        )
        assertTrue(
            "and UP leaves it for the row the chain names above it - the strip - " +
                "with the header as the failure arm (the UP trap's F1b, see " +
                "DetailTopFallThroughContractTest)",
            toggleHandler.contains(
                "onUp = { startFocusMove(DetailRow.OVERVIEW, DetailFocusDirection.UP, " +
                    "presence) { val rows = latestRowPresence.value ?: presence val " +
                    "target = DetailFocusChain.nextRow( DetailRow.OVERVIEW, " +
                    "DetailFocusDirection.UP, rows ) ?: DetailRow.HEADER"
            )
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
