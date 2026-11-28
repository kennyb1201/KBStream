package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab strip's own UP press against the page's reclaim (spec 2026-11-27,
 * Detail focus round 5).
 *
 * The field report: from the strip, press UP - focus lands on Play, then the
 * reclaim yanks it back to the strip; press UP again, same thing. Round 4 had
 * just fixed the reclaim's patience, and its paste said "reclaimed to TABS"
 * rather than "reclaim failed", so the reclaim was working perfectly on a
 * revocation that was not one: the strip's `onUp` placed Play with its own raw
 * `scope.launch`, `chainMoveActive` was therefore false while it landed, and the
 * gain arm's rule - "a HEADER gain with no move in flight is the framework's
 * restart-to-Play" - read the page's own deliberate landing as a theft.
 *
 * The fix is the shape every other surface already had: the strip's UP and DOWN
 * are [DetailFocusMoveRules]-governed chain moves (`startFocusMove`), so a press
 * in flight is what the gain arm consults, and the press is finally traced. What
 * is pinned here is that mechanism: the rule's truth table (which gain is a
 * revocation), the wiring that makes the strip's landing fall on the "not a
 * revocation" side of it, and the DOWN path's independence from the fight.
 */
class DetailStripPressOwnsFocusContractTest {

    private val screen: String by lazy { normalized(stripComments(read(DETAIL))) }
    private val chain: String by lazy { normalized(stripComments(read(CHAIN))) }

    /** The `DetailTabStrip` CALL, from its opening to the traced modifier. */
    private val strip: String by lazy {
        screen.substringAfter("DetailTabStrip(")
            .substringBefore("modifier = Modifier.then(traceRowFocus(DetailRow.TABS))")
    }

    /** [startFocusMove] itself, definition to the next helper. */
    private val startMove: String by lazy {
        screen.substringAfter("fun startFocusMove(")
            .substringBefore("fun focusByGeometry(")
    }

    /** DOWN out of the strip: the entry function and nothing after it. */
    private val entry: String by lazy {
        screen.substringAfter("suspend fun enterTabContent(): Boolean {")
            .substringBefore("fun rowsStillArriving()")
    }

    // ── 1. One UP press owns the Play landing it just placed ──────────────

    @Test
    fun `a single UP from the strip owns the landing it just placed`() {
        assertTrue(
            "the strip's UP is a chain move, not a raw launch - the launch is " +
                "what left chainMoveActive false while Play was placed: $strip",
            strip.contains(
                "onUp = { startFocusMove(DetailRow.TABS, DetailFocusDirection.UP) {"
            )
        )
        assertTrue(
            "and the landing itself is unchanged: the genre chips by geometry, " +
                "Play through focusTopOfPage as the fallback",
            strip.contains(
                "if (!focusByGeometry(DetailFocusDirection.UP)) " +
                    "{ focusTopOfPage(\"strip-up\") }"
            )
        )
        assertEquals(
            "a first press with nothing in flight starts the move",
            DetailFocusMoveRules.Decision.LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = false,
                inFlightDirection = null,
                press = DetailFocusDirection.UP
            )
        )
        assertTrue(
            "the move marks the press in flight, so the landing is the press's",
            startMove.contains("chainMoveActive = true")
        )
        assertNull(
            "a HEADER (Play) gain behind the page's own move is NOT a revocation: " +
                "this is the whole fix",
            DetailFocusMoveRules.revokedRow(
                gain = DetailRow.HEADER,
                owned = DetailRow.TABS,
                chainMoveActive = true
            )
        )
    }

    // ── 2. Repeated UP presses: no reclaim, no restart ────────────────────

    @Test
    fun `repeated UP presses neither reclaim nor restart the landing`() {
        // Still retrying (focusTopOfPage's own 15 x 40ms budget): the press
        // agrees with the move in flight, so it coalesces instead of restarting
        // it, and the in-flight window keeps covering the landing.
        assertEquals(
            DetailFocusMoveRules.Decision.COALESCE,
            DetailFocusMoveRules.decide(
                inFlight = true,
                inFlightDirection = DetailFocusDirection.UP,
                press = DetailFocusDirection.UP
            )
        )
        val coalesced = startMove.substringAfter("DetailFocusMoveRules.Decision.COALESCE ->")
            .substringBefore("DetailFocusMoveRules.Decision.LAUNCH")
        assertTrue(
            "the coalesced press says so, so the paste can tell a repeat from a " +
                "reversal: $coalesced",
            coalesced.contains("via=coalesced")
        )
        assertFalse(
            "and it must NOT end the move - clearing chainMoveActive here would " +
                "re-open the landing window for the reclaim on the very press " +
                "that agreed with it",
            coalesced.contains("chainMoveActive = false")
        )
        assertNull(
            "no gain during those presses can be a revocation, however many " +
                "arrive",
            DetailFocusMoveRules.revokedRow(DetailRow.HEADER, DetailRow.TABS, chainMoveActive = true)
        )
        // The trace line the press finally leaves: a move, with its target, so
        // the UP press is visible in the paste instead of a move-less GAIN.
        assertTrue(
            "a strip press is traced as a move: $startMove",
            startMove.contains("detail move \$from \$direction -> ")
        )
    }

    // ── 3. DOWN is a move too, and cannot produce the fight ───────────────

    @Test
    fun `DOWN from the strip is a move and never lands on the header`() {
        assertTrue(
            "the strip's DOWN goes through the same move discipline: $strip",
            strip.contains(
                "onDown = { startFocusMove(DetailRow.TABS, DetailFocusDirection.DOWN) " +
                    "{ enterTabContent() } }"
            )
        )
        assertEquals(
            "a DOWN pressed while an UP is still retrying cancels it - the wrong " +
                "row must never land after the viewer reversed",
            DetailFocusMoveRules.Decision.CANCEL_AND_LAUNCH,
            DetailFocusMoveRules.decide(
                inFlight = true,
                inFlightDirection = DetailFocusDirection.UP,
                press = DetailFocusDirection.DOWN
            )
        )
        // The entry's own arms are untouched: the tab's row (remembered or
        // first), the media strip, geometry, and the armed content sink.
        assertTrue(
            "the entry still asks the tab's row through the chain",
            entry.contains("askRow(target, 0, DetailRow.TABS, DetailFocusDirection.DOWN, rows)")
        )
        assertTrue(
            "the media row is still entered at its own strip",
            entry.contains("askMediaStrip(DetailRow.TABS)")
        )
        assertTrue(
            "and the content sink is still the landing of last resort",
            entry.contains("tabContentFocusSink.requestFocus()")
        )
        // Every exit still names itself, so "failed" and "never asked" stay
        // distinguishable (the 2026-11-22 round).
        assertTrue(
            entry.contains("detail move TABS DOWN -> \$target landed=\$landed verified=\$verified")
        )
    }

    // ── 4. A genuine theft still reclaims ─────────────────────────────────

    @Test
    fun `a genuine theft still files its reclaim`() {
        assertEquals(
            "the framework's restart-to-Play, with no press of the page's in " +
                "flight, revokes the row the chain had placed",
            DetailRow.TABS,
            DetailFocusMoveRules.revokedRow(
                gain = DetailRow.HEADER,
                owned = DetailRow.TABS,
                chainMoveActive = false
            )
        )
        assertEquals(
            DetailRow.GENRE,
            DetailFocusMoveRules.revokedRow(DetailRow.HEADER, DetailRow.GENRE, false)
        )
        assertNull(
            "a gain ON the owned row is not a theft",
            DetailFocusMoveRules.revokedRow(DetailRow.HEADER, DetailRow.HEADER, false)
        )
        assertNull(
            "nothing was owned, so there is nothing to reclaim",
            DetailFocusMoveRules.revokedRow(DetailRow.HEADER, null, false)
        )
        assertNull(
            "a gain on any other row is a hand-back or another surface's own " +
                "landing, and becomes the owned row instead",
            DetailFocusMoveRules.revokedRow(DetailRow.KEYWORDS, DetailRow.TABS, false)
        )

        assertTrue(
            "the gain arm consults the shared rule rather than restating it",
            screen.contains(
                "val revoked = DetailFocusMoveRules.revokedRow(row, owned, chainMoveActive)"
            )
        )
        assertTrue(
            "a non-null answer files the reclaim exactly as before",
            screen.contains("pendingFocusReclaim = DetailFocusReclaim(revoked, row)")
        )
        // The patience and the landing route are unchanged.
        assertTrue(
            "the retry budget is the same bounded one",
            screen.contains("RETURN_FOCUS_STEPS = 15") &&
                screen.contains("RETURN_FOCUS_DELAY_MS = 40L")
        )
        assertTrue(
            "the reclaim still goes back through the chain's own landing ask " +
                "(askRow / askMediaStrip) instead of a second route",
            screen.contains("suspend fun reclaimFocusRow(row: DetailRow, from: DetailRow?)") &&
                screen.contains("askRow( row, lastOwnedColumn,")
        )
    }

    // ── 5. The strip still holds while content arrives ────────────────────

    @Test
    fun `the strip still holds its pills while content arrives`() {
        assertTrue(
            "the tab set is still the stable one, so pills are not rebuilt when " +
                "sections land",
            screen.contains(
                "availableTabs = remember(isSeriesTitle) { DetailTabChain.stableTabs(isSeriesTitle) }"
            )
        )
        assertTrue(
            "pills are still keyed by tab, which is what lets a composed pill " +
                "keep its node (and its focus)",
            screen.contains("tabs.forEach { tab -> key(tab) { DetailTabPill(")
        )
        assertTrue(
            "and the requesters are still the stable per-tab map the chain lands with",
            screen.contains("requesters: Map<DetailTab, FocusRequester>") &&
                screen.contains("requester = requesters.getValue(tab)")
        )
        assertTrue(
            "the strip is still a plain Row (not a lazy list that could dispose " +
                "the pill it holds): ${chain.length}",
            screen.contains("Row( modifier = modifier .fillMaxWidth()")
        )
    }

    private fun read(relative: String): String = File(findMainSourceRoot(), relative).readText()

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
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val CHAIN = "com/kennyb1201/kbstream/ui/detail/DetailFocusChain.kt"
    }
}
