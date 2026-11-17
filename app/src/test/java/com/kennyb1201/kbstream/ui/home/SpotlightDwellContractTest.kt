package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight card's two-second dwell (spec 2026-11-12).
 *
 * Field evidence, build 0.9.245: five cards each took focus once (focusToken
 * 1→5, a snapshot each), the delay was 2000 ms, no menu was open - and no panel
 * ever opened. The dwell effect never reached its `expanded = …` line, which
 * means the effect was RESTARTED before the delay completed.
 *
 * The branch: the effect keyed on `hasSnapshot`, i.e. `tracker.snapshot != null`,
 * and the tracker nulled that snapshot on every blur. A blur the composition
 * causes and the same card takes straight back - a flicker - therefore cancelled
 * the wait mid-hold, and a card that flickers every second could never finish
 * one. The fix moves the key to a DEBOUNCED card identity, keeps the snapshot
 * across a blur (with `cardFocused` as the honest focus bit), and arms on the
 * card captured when the wait started rather than on whatever the tracker holds
 * at the end.
 *
 * What must not regress (S2/S3): moving to another card still restarts the
 * dwell, moving before the delay still opens nothing, and the pill handoff and
 * the BACK collapse still work.
 */
class SpotlightDwellContractTest {

    private val host by lazy { source(SPOTLIGHT_HOME) }
    private val tracker by lazy { source(SPOTLIGHT_PREVIEW) }

    /** The dwell effect's own body, from its keys to its completion. */
    private val dwellEffect: String by lazy {
        host.substringAfter("LaunchedEffect(heldNode, expandDelayMs, menuOpen, heldSnapshotIdentity) {")
            .substringBefore("// A scroll under a still-focused card")
    }

    // ── The key that cancelled the wait ─────────────────────────────────

    @Test
    fun `the dwell keys on a debounced card identity, not on the live snapshot`() {
        assertTrue(
            "the effect exists with these keys",
            host.contains("LaunchedEffect(heldNode, expandDelayMs, menuOpen, heldSnapshotIdentity) {")
        )
        assertFalse(
            "a live hasSnapshot must not be a key: a flicker cancelled the wait",
            host.contains("LaunchedEffect(tracker.focusToken, hasSnapshot,")
        )
        assertFalse(
            "and the focus token must not be either: a re-entrant focus on the card " +
                "already held would restart the two seconds",
            host.contains("LaunchedEffect(heldCard, tracker.focusToken")
        )
        assertTrue(
            "a card taking focus is immediate; losing it is only believed after a grace",
            host.contains("var heldNode by remember { mutableStateOf<Any?>(null) }") &&
                host.contains("LaunchedEffect(heldNodeNow) {") &&
                host.contains("delay(SPOTLIGHT_BLUR_GRACE_MS)") &&
                host.contains("private const val SPOTLIGHT_BLUR_GRACE_MS = 600L")
        )
        assertTrue(
            "the identity is the card NODE while it holds focus, nothing otherwise " +
                "(rework 2026-11-12 F3: the title's type:id collides across rails)",
            host.contains("val heldNodeNow = if (tracker.cardFocused) tracker.focusedNode else null")
        )
    }

    @Test
    fun `the tracker keeps the card across a blur, and says so separately`() {
        assertTrue(
            "the honest focus bit the debounce reads",
            tracker.contains("var cardFocused by mutableStateOf(false)")
        )
        assertTrue(
            "focus set on a card taking focus",
            tracker.contains("cardFocused = true")
        )
        val blur = tracker.substringAfter("fun onCardFocus(")
            .substringAfter("} else {")
            .substringBefore("fun updateBounds")
        assertTrue("the blur clears the focus bit", blur.contains("cardFocused = false"))
        assertFalse(
            "but it must NOT clear the snapshot: the Home debounces and decides",
            blur.contains("snapshot = null")
        )
    }

    @Test
    fun `the panel holds the card the dwell armed on`() {
        assertTrue(
            "armed before the wait",
            // Still captured before the wait - now through the pause-on-blip
            // branch (spec 2026-11-16, A2) instead of a bare `?: return`.
            dwellEffect.contains("val armedOn = tracker.snapshot ?: run {") &&
                dwellEffect.contains("val armedNode = tracker.focusedNode") &&
                dwellEffect.contains("dwellRun.armed = true")
        )
        assertTrue(
            "and expanded from THAT, not from the live snapshot at the end",
            dwellEffect.contains("expanded = ExpandedPanel(armedOn, armedNode)") &&
                !dwellEffect.contains("expanded = tracker.snapshot")
        )
        assertFalse(
            "no panelExpanded flag is published any more: DOWN has nothing to enter " +
                "(rework 2026-11-12, F2)",
            host.contains("tracker.panelExpanded")
        )
        assertTrue(
            "and drawn from the held snapshot",
            host.contains("val panel = expanded?.snapshot ?: return") &&
                host.contains("snapshot = panel,")
        )
    }

    // ── S2/S3: what a move must still do ────────────────────────────────

    // ── F1 (2026-11-13): the clock survives a re-created card ─────────────

    @Test
    fun `a re-created card resumes the held time instead of restarting it`() {
        // The field report this fixes: "you have to wait multiple minutes before
        // they all work", on a build where a quiet screen expands in 2 s. The
        // effect is ALSO keyed on `heldNode` (the card's FocusRequester), and the
        // trace only names card / delay / menu changes - so a card rebuilt under
        // the viewer (a rail re-deal, a re-landed focus) restarted the two
        // seconds SILENTLY, every time, until the screen went quiet.
        // Since spec 2026-11-16 (A1) the clock is a per-card map rather than a
        // single slot: it still lives outside the effect, and it now also keeps
        // the time across a MOVE TO ANOTHER CARD (see
        // SpotlightExpandAndLogoContractTest for that half). The F1 property
        // pinned here is unchanged: a re-created card resumes.
        assertTrue(
            "the clock is the CARD's, kept outside the effect",
            host.contains("val dwellClock = remember { SpotlightDwellClock() }")
        )
        assertTrue(
            "the time a replaced run held is banked, so a rebuild resumes",
            dwellEffect.contains(
                "val alreadyHeldMs = dwellClock.start(cardNow, SystemClock.uptimeMillis())"
            )
        )
        assertTrue(
            "a deliberate close spends the wait, so the next hold waits it out again",
            host.contains("fun SpotlightDwellClock.spend") ||
                dwellEffect.contains("dwellClock.spend(cardNow)")
        )
        val back = host.substringAfter("BackHandler(enabled = expanded != null) {")
            .substringBefore("val panel =")
        assertTrue(
            "BACK is a deliberate close too",
            back.contains("dwellClock.spend(closedCard)")
        )
        assertTrue(
            "and the viewer's delay itself is unchanged - no longer wait, no shorter one",
            !host.contains("SPOTLIGHT_DWELL_CLOCK") &&
                host.contains("delay((expandDelayMs - alreadyHeldMs)")
        )
    }

    // ── F2/F3 (2026-11-13): the details are the panel's content ───────────

    @Test
    fun `the held catalog card starts its own resolve and the rails warm it first`() {
        val home = source(HOME)
        assertTrue(
            "Spotlight resolves the FOCUSED card directly - no separate hero path",
            home.contains("viewModel.resolveHeroMeta(focused)")
        )
        assertTrue(
            "behind the 150 ms dwell that keeps transitive focus from resolving",
            home.contains("delay(HERO_RESOLVE_DEBOUNCE_MS)")
        )
        val model = source(VIEW_MODEL)
        assertTrue(
            "and every rail item is prefetched, so the card the dwell is about to open " +
                "is a cache hit (F2 is a verification, not a change)",
            model.contains("prefetchHeroArt(\n                    finalRails.flatMap { it.items }\n                )")
        )
        assertTrue(
            "bounded, as before: two permits, sixty cards",
            model.contains("Semaphore(permits = 2)") &&
                model.contains("heroArtPrefetchLimit = 60")
        )
    }

    @Test
    fun `the open panel takes the details when they land`() {
        val home = source(HOME)
        assertTrue(
            "the panel's details are LIVE state, not a copy taken at expansion time",
            home.contains("meta = heroMetaState.value,") &&
                home.contains("metaLoading = heroMetaState.value == null && !heroMetaFailedState.value,") &&
                home.contains("metaFailed = heroMetaFailedState.value,")
        )
        assertTrue(
            "and the host hands them to the panel it is already drawing",
            host.contains("meta = meta,") &&
                host.contains("metaLoading = metaLoading,") &&
                host.contains("metaFailed = metaFailed,")
        )
        val panel = source(SPOTLIGHT_PREVIEW)
        assertTrue(
            "the panel says it is still working, and says when the resolve failed",
            panel.contains("metaLoading -> \"LOADING DETAILS…\"") &&
                panel.contains("metaFailed -> \"DETAILS UNAVAILABLE\"")
        )
        assertTrue(
            "and the panel's own picture arrives the same way (the panel-backdrop spec), " +
                "so a late resolve fills the whole panel, not just its text",
            home.contains("settledBackdropUrl = heroBackdropPictureState.value")
        )
    }

    @Test
    fun `moving to another card restarts the dwell, and moving early opens nothing`() {
        assertTrue(
            "another card is a new identity, and the identity is a key",
            host.contains("Triple(\"hasSnapshot\", card ?: \"none\", next.card ?: \"none\")")
        )
        assertTrue(
            "and an open panel is ended by a menu, a disabled expand, another " +
                "card, or the focus leaving the cards",
            dwellEffect.contains("if (collapseGuard == \"cardChanged\") {") &&
                dwellEffect.contains("if (heldNode == null) {")
        )
        assertTrue(
            "the wait is the delay (less whatever this card had already held), after " +
                "which the panel opens and nothing earlier",
            dwellEffect.contains("delay((expandDelayMs - alreadyHeldMs).coerceAtLeast(0L))") &&
                dwellEffect.indexOf("delay((expandDelayMs") <
                dwellEffect.indexOf("expanded = ExpandedPanel(armedOn, armedNode)")
        )
        // Repointed by the 2026-11-12 collapse spec: `!hasSnapshot` is NOT a
        // collapse reason any more - that blur fired a few hundred ms after a
        // panel opened and took the panel with it. A different card does collapse
        // the old one, which is what keeps a panel from getting stuck.
        assertTrue(
            "the collapse guards are the viewer's own actions, plus a real move",
            dwellEffect.contains("\"menuOpen\"") &&
                dwellEffect.contains("\"!expands\"") &&
                dwellEffect.contains("\"cardChanged\"") &&
                dwellEffect.contains("\"focusLeft\"")
        )
        assertFalse(
            "a blur must not be a collapse guard: the card cannot re-earn focus it " +
                "lost to nothing, so the panel has to outlive the blur",
            dwellEffect.contains("\"!hasSnapshot\"")
        )
        assertTrue(
            "BACK still collapses and hands focus back (S3)",
            host.contains("BackHandler(enabled = expanded != null)") &&
                host.contains("runCatching { tracker.cardRequester?.requestFocus() }")
        )
        assertFalse(
            "and DOWN no longer enters anything (rework 2026-11-12, F2): with no " +
                "pills, DOWN from a card is the next rail - which is a move, and " +
                "collapses the panel through the guard above",
            tracker.contains("panelExpanded")
        )
    }

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
        const val SPOTLIGHT_HOME = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val SPOTLIGHT_PREVIEW = "com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
