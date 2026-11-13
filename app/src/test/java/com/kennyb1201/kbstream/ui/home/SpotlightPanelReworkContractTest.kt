package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight panel rework and the billboard entry scroll (spec 2026-11-12).
 *
 * Five things were wrong on build 0.9.253, and four of them are one design: the
 * panel had drawn its actions on a card-sized surface, so they were clipped; it
 * had a focus neighbourhood of its own, so DOWN entered the pills instead of the
 * rails; and its lifetime was tied to a snapshot rather than to the card the
 * viewer is on, so it never collapsed. The fifth is the hero: the render line
 * finally named it - `list=hero-not-visible first=#1+4px section=composed
 * art=backdrop` - the hero exists, composes and has art, and the rails list
 * starts 379 px down, with the 52% hero above the top. That is a scroll, not a
 * render.
 *
 * What this pins:
 *
 *  - F1  expand is CATALOG ONLY: CW and Upcoming cards are handed
 *        `spotlightExpandable = false`, so they never report a snapshot;
 *  - F2  the panel has no PLAY/DETAILS row, and the card's own click is what
 *        opens Details - the click never starts playback;
 *  - F3  the panel follows the CARD (a node, not the title's `type:id`, which
 *        collides across rails), collapses on a different card or a real
 *        departure, and survives a transient blur that lands back on it;
 *  - F4  the panel's trailer plays on the viewer's autoplay pref (OFF is the
 *        only veto now that there is no manual pill), and its own state is
 *        reported so the next paste can name a failure;
 *  - F5  a Spotlight entry never runs the Continue Watching landing - the
 *        landing scrolls the column, and the hero is IN that column - and the
 *        list is put back to the hero if anything else moves it.
 */
class SpotlightPanelReworkContractTest {

    private val home by lazy { source(HOME_SCREEN) }
    private val section by lazy { source(SPOTLIGHT_HOME) }
    private val preview by lazy { source(SPOTLIGHT_PREVIEW) }
    private val trailer by lazy { source(SPOTLIGHT_PANEL_TRAILER) }
    private val poster by lazy { source(POSTER_CARD) }
    private val landscape by lazy { source(LANDSCAPE_CARD) }
    private val rules by lazy { source(LAYOUT_RULES) }

    private val tracker by lazy {
        preview.substringAfter("internal class SpotlightCardFocus")
            .substringBefore("staticCompositionLocalOf")
    }

    // ── F1: expand is catalog only ────────────────────────────────────────

    @Test
    fun `CW and Upcoming cards are handed a card that can never expand`() {
        assertTrue(
            "a card knows which rail handed it its art",
            poster.contains("spotlightExpandable: Boolean = true") &&
                landscape.contains("spotlightExpandable: Boolean = true")
        )
        assertTrue(
            "and a card that may not expand does not report at all - no snapshot, " +
                "no dwell, no panel",
            poster.contains("if (spotlightFocus == null || !spotlightExpandable)") &&
                landscape.contains("if (spotlightFocus == null || !spotlightExpandable)")
        )
        val upcoming = home.substringAfter("private fun UpcomingEpisodeCard(")
            .substringBefore("@Composable")
        val cw = home.substringAfter("private fun CompactUpNextCard(")
            .substringBefore("@Composable")
        assertTrue(
            "the Upcoming rail's card declines",
            upcoming.contains("spotlightExpandable = false")
        )
        assertTrue(
            "and so does the Continue Watching rail's",
            cw.contains("spotlightExpandable = false") || cw.contains("spotlightExpandable = false,")
        )
        assertFalse(
            "no catalog rail declines: the default is what makes a catalog card " +
                "expandable, and the two exceptions are named above",
            countOf(home, "spotlightExpandable = false") != 2
        )
    }

    // ── F2: no pills, and the click is Details ────────────────────────────

    @Test
    fun `the panel's actions are gone and the card's own click opens Details`() {
        assertFalse(
            "the pill row and its pill are deleted",
            preview.contains("SpotlightPillRow(") || preview.contains("SpotlightPill(")
        )
        assertFalse(
            "the panel takes no action callbacks at all",
            preview.contains("onPlay: () -> Unit") ||
                preview.contains("onDetails: () -> Unit") ||
                preview.contains("onRetryMeta")
        )
        assertTrue(
            "the card's own click is untouched, and a catalog card's click opens " +
                "Detail - which is now the whole of 'click the expanded card'",
            poster.contains("KBCard(\n        onClick = onClick,") &&
                home.contains("onClick = { onOpen(meta) }")
        )
        assertFalse(
            "and nothing in the panel starts playback: the old PLAY pill's " +
                "auto-press is deliberately not carried over",
            preview.contains("BillboardPlaySelection")
        )
    }

    // ── F3: the panel follows the card ────────────────────────────────────

    @Test
    fun `the panel collapses on another card and survives a transient blur`() {
        assertTrue(
            "the identity is the card's NODE: the same show on two rails is two " +
                "cards, and the title's type:id cannot tell them apart",
            tracker.contains("var focusedNode: Any? = null") &&
                tracker.contains("focusedNode = cardRequester")
        )
        assertTrue(
            "the host holds that node and judges the panel against it",
            section.contains("val heldNodeNow = if (tracker.cardFocused) tracker.focusedNode else null") &&
                section.contains("live.node !== heldNode")
        )
        assertTrue(
            "a different card is named apart from a departure, so the paste says which",
            section.contains("\"cardChanged\"") && section.contains("\"focusLeft\"")
        )
        assertTrue(
            "a blur is only believed after the grace, which is what keeps the " +
                "4c29def2a survival",
            section.contains("delay(SPOTLIGHT_BLUR_GRACE_MS)") &&
                section.contains("heldNode = null")
        )
        assertTrue(
            "and BACK still closes it and hands focus back",
            section.contains("BackHandler(enabled = expanded != null)") &&
                section.contains("runCatching { tracker.cardRequester?.requestFocus() }")
        )
    }

    @Test
    fun `a card no longer consumes DOWN, so DOWN is a move`() {
        assertFalse(
            "the DOWN hand-off went with the pills it handed to",
            poster.contains("onDownFromCard") || landscape.contains("onDownFromCard")
        )
        assertFalse(
            "and the tracker has no panelExpanded flag left",
            tracker.contains("panelExpanded")
        )
    }

    // ── F4: the trailer autoplays, and says why it does not ───────────────

    @Test
    fun `the panel's trailer plays on autoplay, and its own state is reported`() {
        assertTrue(
            "OFF is the only veto: with no manual pill, treating MANUAL as 'no " +
                "trailer' would leave those viewers with none at all",
            rules.contains("mode != BillboardTrailerMode.OFF && heroTrailerAutoplay")
        )
        assertFalse(
            "the pill-only rules are gone with the pill",
            rules.contains("showsTrailerPill(") || rules.contains("showsTrailerButton(")
        )
        assertTrue(
            "the panel's trailer reports its own gate, its wait and its resolve",
            trailer.contains("report(\"none (no trailer key)\")") &&
                trailer.contains("report(\"waiting ") &&
                trailer.contains("report(\"resolving\")") &&
                trailer.contains("report(\"mounted\")")
        )
        assertTrue(
            "and that lands on the panel line of the paste",
            source(TRACE).contains("fun spotlightPanelTrailer(status: String)") &&
                source(TRACE).contains("trailer=")
        )
        assertTrue(
            "the wait is still counted from the EXPANSION, not from focus",
            trailer.contains("delay(delayMs.coerceAtLeast(0L))")
        )
    }

    // ── F5: the entry starts at the hero ──────────────────────────────────

    @Test
    fun `a Spotlight entry declines the Continue Watching landing`() {
        val rule = source(HOME_UP_NEXT).substringAfter("internal fun continueWatchingLandsOnFreshEntry(")
        assertTrue(
            "the landing is gated on the hero being present",
            rule.contains("heroPresent: Boolean = false") &&
                rule.contains("!heroPresent")
        )
        assertTrue(
            "and the CW rail passes Spotlight's flag into it",
            home.contains("heroPresent = spotlightActive")
        )
        assertTrue(
            "the entry also puts the list back to the hero if anything moves it, " +
                "until the viewer presses anything",
            // The guard widened on 2026-11-13 (F1): every entry without a card to
            // restore starts at the hero, not just the first of the process.
            home.contains(
                "if (!spotlightActive || entryHasRestoreTarget) return@LaunchedEffect"
            ) &&
                home.contains("runCatching { railListState.scrollToItem(0) }") &&
                home.contains("if (viewerMovedFocusSinceEntry) return@LaunchedEffect")
        )
        assertFalse(
            "and the two CW HORIZONTAL scrollToItem casts are not 'fixed' - they " +
                "scroll the rail's row, not the column",
            countOf(home, "railState.scrollToItem(CONTINUE_WATCHING_HEAD_INDEX)") == 0
        )
    }

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val SPOTLIGHT_PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val SPOTLIGHT_PANEL_TRAILER = "com/kennyb1201/kbstream/ui/home/SpotlightPanelTrailer.kt"
        const val LAYOUT_RULES = "com/kennyb1201/kbstream/ui/home/HomeLayoutRules.kt"
        const val HOME_UP_NEXT = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
        const val LANDSCAPE_CARD = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
        const val TRACE = "com/kennyb1201/kbstream/data/reporting/SpotlightDiagTrace.kt"
    }
}
