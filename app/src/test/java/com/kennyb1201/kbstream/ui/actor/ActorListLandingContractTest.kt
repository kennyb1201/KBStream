package com.kennyb1201.kbstream.ui.actor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the Actor page's own vertical list comes to rest, and who owns that
 * decision.
 *
 * The reported fault, twice over: the biography - the thing the page opens on -
 * was above the viewport, so it had to be scrolled UP to be read, and focusing
 * "View more" cut the bio off at the list's top edge. Both were one rule: the
 * screen installed the RAIL landing (bring the focused card to 30% of the row)
 * on every scroll surface it contained, the vertical list included. Opening a
 * person moves focus to the first filmography card, the spec scrolled the list
 * so that card sat a third of the way down, and everything above it - the bio -
 * went off the top. Focusing the toggle then re-applied the same line.
 *
 * The first three cases are the arithmetic this page now answers for itself (see
 * [ActorListBringIntoViewSpec]); the last three pin the wiring, so the rail line
 * cannot creep back onto the page or the page's line onto a row.
 *
 * There is no TV in CI and the module has no Compose UI harness for a screen
 * that wants a ViewModel and a network round trip, so the structure is read out
 * of the source the way this tree's other UI contracts are.
 */
class ActorListLandingContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The screen's source with runs of whitespace collapsed, for wiring reads. */
    private fun flat(): String =
        source(SCREEN).replace(Regex("\\s+"), " ")

    // ── the landing itself ────────────────────────────────────────────────

    private val spec = ActorListBringIntoViewSpec(insetPx = INSET_PX)

    @Test
    fun `a row already inside the viewport asks for no scroll`() {
        // The regression, and the reason the bio was lost: the rail line
        // answered a fixed distance for a node that was already on screen.
        assertEquals(
            "a row the viewer can already see must not move the list",
            0f,
            spec.calculateScrollDistance(offset = 120f, size = 200f, containerSize = 1000f),
            0f
        )
        assertEquals(
            "and nor must one resting well below the top edge",
            0f,
            spec.calculateScrollDistance(offset = 500f, size = 300f, containerSize = 1000f),
            0f
        )
    }

    @Test
    fun `a row off the bottom is scrolled just far enough to clear the inset`() {
        // 900 + 200 = 1100 against a 1000 viewport with a 32 inset: 132, not the
        // distance to a 30% line.
        assertEquals(
            "the margin, not a fixed landing line, decides the distance",
            132f,
            spec.calculateScrollDistance(offset = 900f, size = 200f, containerSize = 1000f),
            0f
        )
    }

    @Test
    fun `a row above the top edge is scrolled back by the same margin`() {
        assertEquals(
            "a row scrolled past the top clears the inset by exactly that much",
            -82f,
            spec.calculateScrollDistance(offset = -50f, size = 200f, containerSize = 1000f),
            0f
        )
    }

    @Test
    fun `a row taller than the viewport lands on the nearest edge`() {
        assertEquals(
            "a margin can never eat the viewport",
            132f,
            spec.calculateScrollDistance(offset = 0f, size = 400f, containerSize = 300f),
            0f
        )
        assertEquals(
            "and the margin is capped at a third of it",
            60f,
            spec.calculateScrollDistance(offset = 0f, size = 100f, containerSize = 60f),
            0f
        )
    }

    @Test
    fun `a focused poster comes to rest with its caption row on screen`() {
        // The same fault as the bio, one row down: the rail reports the POSTER
        // as the focused node, and the title/year/rating are drawn below it. The
        // page's margin therefore carries the caption block as well, and the
        // arithmetic shows it scrolling further than a poster-only inset would.
        val captionSpec = ActorListBringIntoViewSpec(insetPx = INSET_PX + CAPTION_PX)
        // 900 + 200 = 1100 against a 1000 viewport clearing 32 + 88 of margin:
        // 220, where a poster-only inset would have answered 132.
        assertEquals(
            "the caption room is part of the landing, not left below the fold",
            220f,
            captionSpec.calculateScrollDistance(offset = 900f, size = 200f, containerSize = 1000f),
            0f
        )
        assertTrue(
            "and it is strictly more room than the poster alone needs",
            captionSpec.calculateScrollDistance(offset = 900f, size = 200f, containerSize = 1000f) >
                spec.calculateScrollDistance(offset = 900f, size = 200f, containerSize = 1000f)
        )
        assertEquals(
            "a row already on screen still asks for no scroll at all",
            0f,
            captionSpec.calculateScrollDistance(offset = 100f, size = 200f, containerSize = 1000f),
            0f
        )
    }

    // ── getting back to the info ─────────────────────────────────────────

    @Test
    fun `the first rail scrolls the page back to its info`() {
        // The reported trap: once the rails are in view the bio is a disposed
        // list item, so focus search had nothing above the first rail to move to
        // and the page could not be scrolled back up at all.
        val page = flat()
        assertTrue(
            "the page hands its own list state to the rails",
            page.contains("pageListState = browseListState")
        )
        val rail = page.substringAfter("private fun ActorCreditRail(")
            .substringBefore("private fun ActorCreditCard(")
        assertTrue(
            "UP out of the first rail scrolls the page when it is scrolled",
            rail.contains("event.key == Key.DirectionUp") &&
                rail.contains("pageListState.animateScrollToItem(0)")
        )
        assertTrue(
            "and it is the first rail that does it, so UP between rails still " +
                "moves between them",
            rail.contains("if (isFirstRail) {")
        )
    }

    @Test
    fun `focusing the toggle only snaps the top while the bio is collapsed`() {
        // Expanded, the bio item is taller than the viewport, so snapping its
        // top to the viewport's top put the toggle below the bottom edge - the
        // clipped-on-focus report. Collapsed, the snap is what recovers the
        // paragraph's first lines.
        assertTrue(
            "the snap has to know whether the bio is expanded",
            flat().contains("if (focusState.hasFocus && !bioExpanded) {")
        )
    }

    // ── who installs which rule ───────────────────────────────────────────

    @Test
    fun `the page installs the list landing, not the rail's line`() {
        val page = flat()
        val install = page.indexOf("LocalBringIntoViewSpec provides listBringIntoViewSpec")
        val list = page.indexOf("LazyColumn(")
        assertTrue(
            "the page must install its own vertical landing",
            install >= 0
        )
        assertTrue(
            "and it must be the list's spec",
            page.contains("ActorListBringIntoViewSpec(")
        )
        assertTrue(
            "built from the app's focus-row inset PLUS the caption row under a" +
                " poster: a landing that clears only the poster leaves the date," +
                " title and rating off the bottom edge (the field report)",
            page.contains("insetPx = with(density) { (KBFocusRowInset + ActorCaptionRoom).toPx() }")
        )
        assertTrue("the list must be inside that provider", list > install)
        // The exact shape of the bug: the rail's line installed over the whole
        // screen, so the bio answered to it.
        val beforeRail = page.substringBefore("private fun ActorCreditRail(")
        assertFalse(
            "the rail's 30% line must not be supplied to the page - it is what " +
                "scrolled the biography off the top on open",
            beforeRail.contains("LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec")
        )
    }

    @Test
    fun `each credit rail keeps the rail line around its own row`() {
        val rail = flat().substringAfter("private fun ActorCreditRail(")
            .substringBefore("private fun ActorCreditCard(")
        val provider = rail.indexOf("LocalBringIntoViewSpec provides LocalActorBringIntoViewSpec")
        val row = rail.indexOf("LazyRow(")
        assertTrue(
            "the rail's landing must still be supplied, or a focused card stops " +
                "coming to rest a third of the way in",
            provider >= 0
        )
        assertTrue("supplied around the row itself", row > provider)
    }

    @Test
    fun `the list is the scroll surface that carries the bio`() {
        // The bio and its toggle live in the vertical list - which is why its
        // landing is the one the report is about.
        val page = flat()
        val list = page.substringAfter("LazyColumn(")
            .substringBefore("if (movies.isNotEmpty())")
        assertTrue("the biography must still be the list's first item", list.contains("key = \"biography\""))
        assertTrue("with its View more toggle", list.contains("\"View more\""))
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt"

        /**
         * What `KBFocusRowInset` resolves to on a TV-density screen (16dp at
         * density 2), the number the wiring line above builds the spec with.
         */
        const val INSET_PX = 32f

        /** `ActorCaptionRoom` (44dp) at density 2, for the arithmetic above. */
        const val CAPTION_PX = 88f
    }
}
