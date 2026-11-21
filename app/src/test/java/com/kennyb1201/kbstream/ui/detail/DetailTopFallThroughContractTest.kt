package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail page's UP trap above the list (Dark Marvels, 2026-11-08 21:58).
 *
 * After walking the full page down and back up, UP stopped on a genre chip (and
 * sometimes on the overview's "View more" toggle) and would not go past it. The
 * discriminators said the interesting thing: focus was ALIVE on the chip, UP
 * reached PLAY on a fresh open, and DOWN then UP cleared it. So the landing
 * `focusTopOfPage()` asks for fails in that one moment, and the press had
 * already been consumed - a miss that read as the edge.
 *
 * The rule this pins is the chain's own, already stated on `focusByGeometry`:
 * "a press that moves nothing must not be a press the page took." The DOWN half
 * of the toggle follows it; this is the UP half, in both paths:
 *
 *  - a chain UP whose target is HEADER and which could not land there runs the
 *    geometric search instead of reaching the edge arm (F1a, T1/T2),
 *  - the toggle's own UP does the same when `focusTopOfPage()` answers false
 *    (F1b, T3),
 *  - and both start INSIDE the move coroutine, because the key handler already
 *    consumed the press (F1c).
 *
 * What must NOT move is as much of the point: `isPageEdge` is untouched (UP off
 * the genres is still the page's top in the chain's sense - a landing that was
 * asked and refused is a miss, not an edge), UP from PLAY still stays, DOWN off
 * the Media row still stays, and the 15 x 40 ms retry is unchanged (F1d, F3,
 * T4-T6).
 *
 * T1/T2 are one code path - the arm does not care how the focus got to the chip
 * - and are settled on the device (§6 S1/S2); source-reading style, like the
 * page's other focus contracts, because these are key handlers and need a
 * remote to press.
 */
class DetailTopFallThroughContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The page's single vertical handoff. */
    private val move: String by lazy {
        detail.substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
    }

    /** The overview toggle's UP and DOWN. */
    private val toggleHandler: String by lazy {
        detail.substringAfter("fun overviewToggleVerticalFocus(")
            .substringBefore("suspend fun focusFirstMediaCard(")
    }

    /** The last-resort landing every UP above the list goes through. */
    private val topOfPage: String by lazy {
        detail.substringAfter("suspend fun focusTopOfPage(): Boolean {")
            .substringBefore("val effectiveSeason")
    }

    private fun presence(
        genres: Boolean = false,
        keywords: Boolean = false,
        episodes: Boolean = false,
        media: Boolean = false,
        /** The overview's toggle, off unless a case is about it. */
        overviewToggle: Boolean = false
    ) = DetailRowPresence(
        hasGenreRow = genres,
        hasOverviewRow = overviewToggle,
        hasKeywordsRow = keywords,
        hasEpisodesRow = episodes,
        hasPeopleRow = false,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMediaRow = media,
        hasMoreLikeThisRow = false
    )

    // ── T1/T2: a header landing that fails moves anyway ───────────────────

    @Test
    fun `an UP that reaches the header and cannot land there falls through`() {
        val landed = move.indexOf("if (landed) return true")
        val arm = move.indexOf(
            "if (target == DetailRow.HEADER && direction == DetailFocusDirection.UP) {"
        )
        val edge = move.indexOf("val fellThrough = fallThroughIfMidPage(from, direction, settledRows)")

        assertTrue("the landing's own answer is read first", landed >= 0)
        assertTrue(
            "F1a: HEADER + UP is its own arm, because that is the press the field " +
                "reported - a chip, PLAY one row up, and nothing happening",
            arm > landed
        )
        assertTrue(
            "and it runs BEFORE the edge check: a landing that was asked for and " +
                "refused is a miss, not the top of the page",
            edge > arm
        )
        assertTrue(
            "the arm hands the press to the platform's search, from where the viewer " +
                "actually is - PLAY sits directly above the chips in the fixed header - " +
                "and returns its answer",
            move.substringAfter(
                "if (target == DetailRow.HEADER && direction == DetailFocusDirection.UP) {"
            ).substringBefore("}").contains("focusByGeometry(DetailFocusDirection.UP)") &&
                move.contains("tracedVia = \"geometry\"")
        )
    }

    @Test
    fun `the search is what the page calls a move that cannot be placed`() {
        assertTrue(
            "the same entry point the DOWN fall-through uses, so there is one " +
                "definition of \"hand it to the platform\"",
            detail.contains("fun focusByGeometry(direction: DetailFocusDirection): Boolean =") &&
                detail.contains("focusManager.moveFocus(")
        )
        assertTrue(
            "and it reports whether the search moved anything, so a press with " +
                "nothing anywhere that way is still visible as such",
            detail.substringAfter("fun focusByGeometry(").contains(".getOrDefault(false)")
        )
    }

    // ── T3: the toggle's UP moves too ─────────────────────────────────────

    @Test
    fun `the toggle's UP falls through when the header will not take it`() {
        assertTrue(
            "F1b: the toggle's UP is the row the chain places above it, with the " +
                "page's own header landing as the fallback - and a failure moves",
            toggleHandler.contains(
                "onUp = { startFocusMove(DetailRow.OVERVIEW, DetailFocusDirection.UP, " +
                    "presence)"
            ) && toggleHandler.contains("|| focusTopOfPage()")
        )
        assertTrue(
            "F1c: it runs INSIDE the move coroutine - the handler consumed the key, " +
                "so \"return false and let the default search run\" is not available " +
                "any more (see focusByGeometry's own doc)",
            toggleHandler.contains(
                "startFocusMove(DetailRow.OVERVIEW, DetailFocusDirection.UP, presence) { val rows = latestRowPresence.value ?: presence"
            )
        )
        assertTrue(
            "and UP is still consumed by the page: the toggle has no unowned UP to " +
                "hand back, only a move to finish",
            detail.substringAfter("fun nonRailVerticalFocusWithDownOutcome(")
                .substringBefore("fun overviewToggleVerticalFocus(")
                .contains("Key.DirectionUp -> { onUp() true }")
        )
    }

    // ── T4/T5: the real edges still hold ──────────────────────────────────

    @Test
    fun `UP from PLAY itself still stays`() {
        assertNull(
            "the chain has no row above the header - UP there is not a walk",
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.UP,
                presence(genres = true, keywords = true, episodes = true)
            )
        )
        assertTrue(
            "F1d: and the edge rule is untouched - UP off the header is a real edge " +
                "with nothing above it in the chain",
            DetailFocusChain.isPageEdge(
                DetailRow.HEADER,
                DetailFocusDirection.UP,
                presence(genres = true)
            )
        )
        assertTrue(
            "2026-11-21: the edge is no longer EATEN. The page has nothing above " +
                "the action row, so the press goes back to the app shell, whose " +
                "dead-press handler raises the TV top bar - consuming it here is " +
                "exactly why the bar could never be reached from this page",
            detail.contains("detail header UP -> top bar") &&
                !detail.contains("onUp = {},")
        )
    }

    @Test
    fun `DOWN off the Media row is still the bottom edge`() {
        val lastRow = presence(media = true)
        assertNull(
            "nothing below the Media row",
            DetailFocusChain.nextRow(DetailRow.MEDIA, DetailFocusDirection.DOWN, lastRow)
        )
        assertTrue(
            "so that DOWN is a real edge, and stays put",
            DetailFocusChain.isPageEdge(DetailRow.MEDIA, DetailFocusDirection.DOWN, lastRow)
        )
        assertTrue(
            "the new arm is UP-only: the DOWN side of the page is not in this spec",
            move.contains(
                "if (target == DetailRow.HEADER && direction == DetailFocusDirection.UP) {"
            ) && !move.contains(
                "if (target == DetailRow.HEADER && direction == DetailFocusDirection.DOWN) {"
            )
        )
        assertEquals(
            "and the page's eleven content rows are untouched - the strip is the " +
                "route into them and the overview's toggle is the one row added above " +
                "it (2026-11-21)",
            13,
            DetailRow.entries.size
        )
    }

    // ── T6: a title with no genres is unchanged ───────────────────────────

    @Test
    fun `a title with no genres still walks UP to the toggle or the header`() {
        assertEquals(
            "no genre row: UP out of the first list row is the strip painted above " +
                "it - the page's third stop",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.KEYWORDS,
                DetailFocusDirection.UP,
                presence(keywords = true)
            )
        )
        assertEquals(
            "with genres, it is still the strip: the chips hang above IT now",
            DetailRow.TABS,
            DetailFocusChain.nextRow(
                DetailRow.KEYWORDS,
                DetailFocusDirection.UP,
                presence(genres = true, keywords = true)
            )
        )
        assertTrue(
            "and the toggle is a chain row above the strip now (2026-11-21), so the " +
                "same walk reaches it from the strip's own UP - no special case in " +
                "the move, and the strip's UP passes the chips first when they exist",
            DetailFocusChain.nextRow(
                DetailRow.TABS,
                DetailFocusDirection.UP,
                presence(overviewToggle = true)
            ) == DetailRow.OVERVIEW && !move.contains("focusOverviewToggle()")
        )
    }

    // ── T7 / F2: the silent failure is logged ─────────────────────────────

    @Test
    fun `an exhausted top landing says so and the press still moves`() {
        assertTrue(
            "F2a: which requesters were asked and how many attempts ran, at the " +
                "moment the arm gives up",
            topOfPage.contains(
                "\"focusTopOfPage: PLAY and details would not take the focus after \" + " +
                    "\"\$RETURN_FOCUS_STEPS attempts\""
            )
        )
        assertTrue(
            "and it is the LAST thing before the honest false, so the line and the " +
                "answer cannot disagree",
            topOfPage.indexOf("focusTopOfPage: PLAY and details") in 0 until
                topOfPage.lastIndexOf("return false")
        )
        assertTrue(
            "T7: both callers of that false move on - the chain arm through the " +
                "geometric search, the toggle through its own row and the header " +
                "landing - so a failed landing is never a dead press",
            move.contains("focusByGeometry(DetailFocusDirection.UP)") &&
                toggleHandler.contains("focusRow(target, 0, DetailRow.OVERVIEW, " +
                    "DetailFocusDirection.UP, rows) || focusTopOfPage()")
        )
        assertTrue(
            "and the exhaustion reaches the diagnostics ring as E2, which is the " +
                "half a TV can actually show",
            topOfPage.contains(
                "DetailTrace.note(\"detail top landing failed after \$attempts attempts\")"
            )
        )
    }

    // ── F3: the retry, the edge rule and the DOWN paths are untouched ─────

    @Test
    fun `the retry budget and the edge rule are unchanged`() {
        assertTrue(
            "F3b: the same 15 x 40 ms - the fall-through only runs after the arm has " +
                "already failed",
            detail.contains("private const val RETURN_FOCUS_STEPS = 15") &&
                detail.contains("private const val RETURN_FOCUS_DELAY_MS = 40L")
        )
        assertTrue(
            "the loop, the scroll and the PLAY-first order are as they were",
            topOfPage.contains("runCatching { detailListState.scrollToItem(0) }") &&
                topOfPage.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                topOfPage.contains("delay(RETURN_FOCUS_DELAY_MS)") &&
                topOfPage.indexOf("playButtonFocusRequester.requestFocus()") in 0 until
                topOfPage.indexOf("movieDetailsFocusRequester.requestFocus()")
        )
        assertTrue(
            "F1d: the edge rule itself is untouched - UP off the header OR the genres " +
                "is still the page's top in the chain's sense",
            detail.contains("DetailFocusChain.isPageEdge(from, direction, presence)") &&
                chainSource().contains(
                    "DetailFocusDirection.UP -> row == DetailRow.HEADER || row == DetailRow.GENRE"
                )
        )
        assertTrue(
            "and the toggle's DOWN keeps its rule, now asked from the toggle's own " +
                "row: the strip is under it, so the press is always placeable",
            chainSource().contains(
                "rowsStillArriving || nextRow(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, " +
                    "presence) != null"
            )
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    /** The chain's own rule file, where the edge rule and the row order live. */
    private fun chainSource(): String =
        normalized(stripComments(read(CHAIN_FILE)))

    private fun read(fileName: String): String =
        File(findMainSourceRoot(), "com/kennyb1201/kbstream/ui/detail/$fileName").readText()

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
        const val CHAIN_FILE = "DetailFocusChain.kt"
    }
}
