package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight audit's remaining findings (spec 2026-11-12).
 *
 * The companion to [SpotlightScrollHeroContractTest]: that spec owns the
 * scroll-away hero, the entry focus gate, the landing inset and the expand
 * dwell, and it fixes the restore/pill race, the hero pop-in and the UP carve-out
 * as preconditions. What is left here is what the scroll-away design does not
 * absorb: pills that waited on a resolve that can fail, a hero that kept cycling
 * behind an expanded card, a panel BACK could not close, a mark the panel never
 * drew, TV segments the D-pad could not reach, and an empty media box.
 *
 * These are structural pins (which value a pill acts on, which key pauses the
 * cycle, which handler takes BACK) because the screen's own behaviour needs a
 * device. The arithmetic stays where it was pinned.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out app/src/test/java/.../SpotlightAuditFixesContractTest.kt, then
 * JUnitCore with -Duser.dir at the repository root.
 */
class SpotlightAuditFixesContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /** Whitespace collapsed: these pins are about shape, not layout of the source. */
    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun home(): String = flat(source(HOME))
    private fun section(): String = flat(source(SECTION))
    private fun preview(): String = flat(source(PREVIEW))
    private fun posterCard(): String = flat(source(POSTER_CARD))
    private fun mobile(): String = flat(source(MOBILE))
    private fun viewModel(): String = flat(source(VIEW_MODEL))

    // ── (a) a visible pill is never a silent dead press ────────────────────

    @Test
    fun `the panel's pills act on the card's own identity, not on the resolve`() {
        assertTrue(
            "the card hands its Detail identity to the snapshot (A4)",
            posterCard().contains("id = spotlightId") &&
                posterCard().contains("type = spotlightType")
        )
        assertTrue(
            "and the snapshot carries it",
            preview().contains("val id: String? = null") &&
                preview().contains("val type: String? = null")
        )
        val s = section()
        assertTrue(
            "PLAY and DETAILS are built from the snapshot's id, falling back to " +
                "the meta rather than requiring it",
            s.contains("val preview = (panel.id ?: meta?.id)?.let { id ->")
        )
        assertFalse(
            "the pills are no longer gated on the resolved meta",
            s.contains("val preview = meta?.let { detail ->")
        )
        // The rails have the id at the card: the catalog rows pass the item's
        // own, and a continue-watching card passes its parent's (Detail opens
        // the show, not the episode) - the two shapes Home actually renders.
        val h = home()
        assertTrue(
            "catalog cards pass their own id and type",
            h.contains("spotlightId = meta.id") &&
                h.contains("spotlightType = meta.type")
        )
        assertTrue(
            "a CW/Upcoming card passes the show it belongs to",
            h.contains("spotlightId = item.parentId ?: item.id") &&
                h.contains("spotlightId = upcoming.parentId")
        )
    }

    @Test
    fun `the panel says it is loading and offers its own retry when the resolve fails`() {
        val p = preview()
        assertTrue(
            "the meta line is a placeholder while resolving, not silence",
            p.contains("metaLoading -> \"LOADING DETAILS")
        )
        assertTrue(
            "and a failed resolve says so",
            p.contains("metaFailed -> \"DETAILS UNAVAILABLE\"")
        )
        assertTrue(
            "with a retry affordance in the panel itself",
            p.contains("if (showRetry) {") && p.contains("label = \"RETRY\"")
        )
        assertTrue(
            "the failure is a real state - \"not yet\" and \"never\" were both null",
            viewModel().contains("val heroMetaFailed: StateFlow<Boolean>") &&
                viewModel().contains("_heroMetaFailed.value = true")
        )
        assertTrue(
            "and the retry re-runs the resolve for the focused card",
            home().contains("onRetryMeta = { viewModel.resolveHeroMeta(focusedItemState.value) }")
        )
    }

    // ── (b) the hero holds still behind an expanded card ───────────────────

    @Test
    fun `the hero's cycle and sweep pause while a card is expanded and resume on collapse`() {
        val m = mobile()
        val cycle = m.substringAfter("val autoTrailerArmed =")
            .substringBefore("val sweepPaused")
        assertTrue(
            "the cycle stops on the same suppression that stops new trailers",
            cycle.contains("if (trailerSuppressed) return@LaunchedEffect")
        )
        assertTrue(
            "and the suppression is a key, so lifting it re-arms the delay " +
                "(the landing intent) rather than leaving the hero frozen",
            cycle.contains("trailerSuppressed")
        )
        assertTrue(
            "the Ken Burns sweep pauses with it",
            m.substringAfter("val sweepPaused =").substringBefore("var sweptPage")
                .contains("trailerSuppressed")
        )
        assertTrue(
            "the expanded card is what suppresses the hero",
            section().contains("trailerSuppressed = cardExpanded")
        )
    }

    // ── (c) BACK closes the panel before it leaves Home ────────────────────

    @Test
    fun `BACK collapses a panel that is up and hands focus back to its card`() {
        val s = section()
        val handler = s.substringAfter("BackHandler(enabled = expanded != null) {")
            .substringBefore("}")
        assertTrue(
            "the panel owns BACK while it is expanded",
            s.contains("BackHandler(enabled = expanded != null)")
        )
        assertTrue(
            "and collapses itself",
            handler.contains("expanded = null") && handler.contains("onExpandedChange(false)")
        )
        assertTrue(
            "returning focus to the card that owns it",
            handler.contains("tracker.cardRequester?.requestFocus()")
        )
        assertTrue(
            "Home's own leave-Home BACK is still there for when no panel is up",
            home().contains("BackHandler(enabled =")
        )
    }

    // ── (d) the panel draws the partial-watch mark ─────────────────────────

    @Test
    fun `a half-watched card's panel shows the mark its card carries`() {
        val p = preview()
        assertTrue(
            "the panel reads the partial mark it was already handed",
            p.contains("else if (snapshot.isPartiallyWatched)")
        )
        assertTrue(
            "and mirrors the card's own eye, not a second invented mark",
            p.contains("Icons.Filled.Visibility") &&
                posterCard().contains("Icons.Filled.Visibility")
        )
        assertTrue(
            "the card reports the flag in the first place",
            posterCard().contains("isPartiallyWatched = isPartiallyWatched")
        )
    }

    // ── (e) no control the D-pad cannot reach ──────────────────────────────

    @Test
    fun `the touch-only page segments are not drawn on TV`() {
        assertTrue(
            "the segments are touch-only, so TV does not render them at all",
            mobile().contains("if (pages.size > 1 && heroHeight == null) {")
        )
        assertFalse(
            "nor may a TV build reach a focusable-by-accident segment row",
            mobile().contains("if (pages.size > 1) { The segmented progress bar")
        )
    }

    // ── (f) the media area always has something in it ──────────────────────

    @Test
    fun `a title with no art shows a placeholder in the panel's media area`() {
        val p = preview()
        assertTrue(
            "the panel checks the same three sources the trailer does",
            p.contains("val stillNow = snapshot.stillUrl ?: meta?.background ?: meta?.poster")
        )
        val placeholder = p.substringAfter("if (stillNow.isNullOrBlank()) {")
            .substringBefore("SpotlightPanelTrailer(")
        assertTrue(
            "and fills the box with the app's art-less fallback - the title",
            placeholder.contains(".background(KBSurface)") &&
                placeholder.contains("text = snapshot.title.orEmpty()")
        )
    }

    @Test
    fun `the diff touches no Modern or phone layout`() {
        val p = preview()
        assertEquals(
            "one panel, one media placeholder",
            1,
            Regex("if \\(stillNow\\.isNullOrBlank\\(\\)\\)").findAll(p).count()
        )
        assertFalse(
            "the phone's own Home is not the surface this spec changed",
            mobile().contains("spotlightId =")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SECTION = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
