package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical-focus audit's remaining findings (F3, F4, F6, F7).
 *
 * The invariant the audit is built on: no vertical press is ever consumed
 * without a visible landing. F1 and F2 (the chain's UP and the toggle's UP) are
 * pinned in [DetailTopFallThroughContractTest]; this file pins the narrower
 * cases on the same rule:
 *
 *  - F3: the Media row's internal handoffs (card <-> strip) read their landing
 *    and fall through, instead of returning true whatever happened,
 *  - F4: DOWN into a section that will not take the focus lands geometrically
 *    below, not on PLAY at the top of the page,
 *  - F6: the media card's doc describes what the handler actually does,
 *  - F7: the toggle stop is computed from the rows the page has NOW, not from
 *    the presence the pressing rail was composed with.
 *
 * Read from the source: these are key handlers and Compose call sites, which
 * need a device and a remote to press. The T4/T5 behaviours they stand in for
 * are settled on the device (§6).
 */
class DetailVerticalAuditContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The raw source too, for the doc the screen carries above the handler. */
    private val rawDetail: String by lazy { read(DETAIL_FILE) }

    private val move: String by lazy {
        detail.substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
    }

    private val cardFocus: String by lazy {
        detail.substringAfter("fun mediaCardFocus(")
            .substringBefore("fun trailerPlayerIntent(")
    }

    // ── F3: the Media row reads both of its internal landings ─────────────

    @Test
    fun `a strip the reveal could not bring back falls through UP`() {
        val up = detail
            .substringAfter("onFocusStrip = {")
            .substringBefore("presence = detailRowPresence")

        assertTrue(
            "F3: the card's UP is still the strip first - it must never jump past " +
                "the row's own selector to the rail above while the strip can take it",
            up.contains("focusMediaStrip(")
        )
        assertTrue(
            "and it reads the landing the strip reports",
            up.contains("val landed = focusMediaStrip(")
        )
        assertTrue(
            "the exhausted reveal is a miss, so the platform's search gets the press " +
                "instead of the press dying on the card",
            up.indexOf("focusMediaStrip(") in 0 until
                up.indexOf("focusByGeometry( DetailFocusDirection.UP )") &&
                up.contains("if (!landed) { focusByGeometry( DetailFocusDirection.UP ) }")
        )
        assertTrue(
            "and the press is still consumed by the page, because the move is already " +
                "running inside it",
            up.contains("startFocusMove {") && cardFocus.contains("onFocusStrip() true")
        )
    }

    @Test
    fun `a cards row that will not come back falls through DOWN`() {
        val down = detail
            .substringAfter("onFocusContent = {")
            .substringBefore("presence = detailRowPresence")

        assertTrue(
            "F3: DOWN out of the strip still asks the cards first, in the column the " +
                "section remembers",
            down.contains("val landed = focusFirstMediaCard()") &&
                down.indexOf("focusFirstMediaCard()") in 0 until
                down.indexOf("focusByGeometry( DetailFocusDirection.DOWN )")
        )
        assertTrue(
            "and a cards row the reveal could not place hands the press to the search",
            down.contains("if (!landed) { focusByGeometry( DetailFocusDirection.DOWN ) }")
        )
        assertTrue(
            "the three attempts are unchanged - column, column 0, reveal - and their " +
                "answer is now the function's",
            detail.contains("suspend fun focusFirstMediaCard(): Boolean {") &&
                detail.contains(
                    "if (focusChipColumn(MEDIA_ROW_KEY, lastMediaCardColumn)) return true"
                ) &&
                detail.contains(
                    "return revealAndFocusChipColumn(MEDIA_ROW_KEY, MEDIA_STRIP_KEY,"
                )
        )
    }

    // ── F4: a section that will not take DOWN does not teleport to PLAY ───

    @Test
    fun `DOWN into the episode section falls through downward, not to the top`() {
        // From focusRow itself: DetailRow.EPISODES also names a row-key arm
        // earlier in the page (the reveal's key lookup), and that one is not
        // this arm.
        val arm = detail
            .substringAfter("fun focusRow(")
            .substringAfter("DetailRow.EPISODES -> ")
            .substringBefore("DetailRow.GENRE,")

        assertTrue(
            "F4: the reveal is still the first resort",
            arm.contains("revealAndFocusEpisodes(from)")
        )
        assertTrue(
            "and its failure is the geometric search DOWN - the nearest row below, " +
                "which is what the press meant - not the header PLAY",
            arm.contains("focusByGeometry(DetailFocusDirection.DOWN)") &&
                !arm.contains("focusTopOfPage()")
        )
        assertTrue(
            "the top landing is still the HEADER arm's own fallback, so this fix did " +
                "not remove the only landing that arm has",
            detail.contains("DetailRow.HEADER -> focusTopOfPage()")
        )
    }

    // ── F6: the doc says what the handler does ────────────────────────────

    @Test
    fun `the media card's doc no longer claims DOWN lands on PLAY`() {
        val doc = rawDetail
            .substringAfter("     * A card of the Media row: UP returns to the row's tab strip, and DOWN is")
            .substringBefore("     */")

        assertTrue(
            "F6: the doc names the delegate that actually handles DOWN",
            doc.contains("detailRowVerticalFocus") && doc.contains("MEDIA")
        )
        assertTrue(
            "and the bottom edge it really is - the press is consumed on the card",
            doc.contains("bottom of the page") && doc.contains("stays on the card")
        )
        assertFalse(
            "the behaviour it used to describe (a landing on PLAY) is gone",
            doc.contains("lands on the header PLAY")
        )
        assertTrue(
            "the handler itself is untouched: DOWN still goes through the chain's " +
                "MEDIA row with the card's column",
            cardFocus.contains("detailRowVerticalFocus(") &&
                cardFocus.contains("setOf(DetailFocusDirection.DOWN)")
        )
    }

    // ── F7: the toggle stop is computed from the settled rows ─────────────

    @Test
    fun `the toggle stop reads the presence that landed`() {
        assertTrue(
            "F7: the settled presence, the same one the toggle's DOWN already reads",
            move.contains("val settledRows = latestRowPresence.value ?: presence") &&
                move.contains("from == firstListRow(settledRows)")
        )
        assertFalse(
            "the composition-time presence no longer decides the toggle stop: a rail " +
                "composed before a higher row landed computed the wrong first row and " +
                "skipped the stop",
            move.contains("from == firstListRow(presence)")
        )
        assertTrue(
            "and the stop is still UP-only and still the toggle",
            move.contains("direction == DetailFocusDirection.UP &&") &&
                move.contains("focusOverviewToggle()")
        )
    }

    // ── F1/F2 stay, and the edges do not move ────────────────────────────

    @Test
    fun `the audit's fixes leave the edges and the retry budgets alone`() {
        assertTrue(
            "F1: HEADER + UP still runs the geometric search before the edge arm",
            move.indexOf("if (target == DetailRow.HEADER && direction == DetailFocusDirection.UP) {") in
                0 until move.indexOf("val fellThrough = fallThroughIfMidPage(")
        )
        assertTrue(
            "F2: the toggle's UP still falls through",
            detail.contains("onUp = { startFocusMove { val landed = focusTopOfPage() ||")
        )
        assertEquals(
            "T6: the page still has its eleven rows - no row was added to route around " +
                "a miss",
            11,
            DetailRow.entries.size
        )
        assertTrue(
            "and the two real edges are the chain's, unchanged",
            DetailFocusChain.isPageEdge(
                DetailRow.HEADER,
                DetailFocusDirection.UP,
                presence()
            ) && !DetailFocusChain.isPageEdge(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(keywords = true)
            )
        )
        assertTrue(
            "the retry budgets the fall-throughs run after are unchanged",
            detail.contains("private const val RETURN_FOCUS_STEPS = 15") &&
                detail.contains("private const val RETURN_FOCUS_DELAY_MS = 40L") &&
                detail.contains("while (attempts < RETURN_FOCUS_STEPS)")
        )
    }

    private fun presence(keywords: Boolean = false) = DetailRowPresence(
        hasGenreRow = true,
        hasKeywordsRow = keywords,
        hasEpisodesRow = true,
        hasPeopleRow = true,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMediaRow = true,
        hasMoreLikeThisRow = false
    )

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
    }
}
