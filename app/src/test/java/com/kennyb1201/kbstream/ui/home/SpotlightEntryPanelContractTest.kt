package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four Spotlight field reports from build 0.9.267 (spec 2026-11-13).
 *
 * The paste is the evidence for all four:
 *
 *  - `billboard: render list=hero-not-visible first=#2+151px` on a fresh entry -
 *    the hero was scrolled off, because the scroll-to-top restore and the hero's
 *    entry claim were both gated on `isFreshEntry`, which is true once per
 *    ViewModel lifetime (F1).
 *  - three of seven expanded cards expanded: the dwell bailed while a card's
 *    details were still resolving and nothing restarted it when they landed, so
 *    only already-cached cards ever expanded (F2).
 *  - the panel's backdrop was the rail card's own small still, blown up to 62% of
 *    the viewport in 16:9, so it was fuzzy (F3).
 *  - trailers almost never played in the panel: the delay was the viewer's 6s
 *    HERO delay counted from expansion, and the panel collapses within a couple
 *    of seconds of that (F4).
 */
class SpotlightEntryPanelContractTest {

    private val home by lazy { source(HOME) }
    private val section by lazy { source(SPOTLIGHT_HOME) }
    private val preview by lazy { source(SPOTLIGHT_PREVIEW) }
    private val rules by lazy { source(LAYOUT_RULES) }

    // ── F1: every fresh entry starts at the hero ──────────────────────────

    @Test
    fun `the entry claim is gated on there being no card to restore`() {
        assertTrue(
            "the gate is the restore target, read once at composition",
            home.contains(
                "val entryHasRestoreTarget = remember { " +
                    "!viewModel.lastFocusedRailItemKey.isNullOrBlank() }"
            )
        )
        val claim = home.substringAfter("claimEntryFocus = !spotlightEntryFocusClaimed &&")
            .substringBefore("onEntryFocusClaimed")
        assertTrue("and it is the claim's gate: $claim", claim.contains("!entryHasRestoreTarget"))
        assertFalse(
            "not the one-shot fresh-entry flag any more: that is true once per " +
                "ViewModel lifetime, so the hero could never claim again",
            claim.contains("isFreshEntry")
        )
    }

    @Test
    fun `the scroll-to-top restore runs on every entry without a restore target`() {
        val effect = home.substringAfter(
            "LaunchedEffect(spotlightActive, entryHasRestoreTarget, mergedEntries, billboardEnabledForDiag) {"
        ).substringBefore("// The other half of the restore")

        assertTrue("the effect is keyed on the entry gate", effect.isNotBlank())
        assertTrue(
            "and returns when this entry has a card to come back to - the restore owns it",
            effect.contains("if (!spotlightActive || entryHasRestoreTarget) return@LaunchedEffect")
        )
        assertTrue(
            "a fresh entry re-arms the hero's one-shot claim, so it can claim on each entry",
            effect.contains("spotlightEntryFocusClaimed = false")
        )
        assertTrue(
            "still dead once the viewer presses anything",
            effect.contains("if (viewerMovedFocusSinceEntry) return@LaunchedEffect")
        )
        assertTrue(
            "and the list is still put back at the hero",
            effect.contains("railListState.scrollToItem(0)")
        )
    }

    // ── F1 (2026-11-13): the hold is data-bounded ──────────

    @Test
    fun `the entry hold is gated on the billboard settling, not on a frame count`() {
        val effect = home.substringAfter(
            "LaunchedEffect(spotlightActive, entryHasRestoreTarget, mergedEntries, billboardEnabledForDiag) {"
        ).substringBefore("// The other half of the restore")

        assertTrue(
            "the hold waits for the billboard's own answer",
            effect.contains("viewModel.billboardReady.value")
        )
        assertFalse(
            "the 90-frame window is gone: ~1.5 s against the field's 8641 ms cold start",
            home.contains("SPOTLIGHT_ENTRY_TOP_FRAMES")
        )
        assertTrue(
            "and it is bounded by a cap chosen from that evidence",
            home.contains("private const val SPOTLIGHT_ENTRY_HOLD_CAP_MS = 15_000L") &&
                effect.contains("SPOTLIGHT_ENTRY_HOLD_CAP_MS")
        )
        assertTrue(
            "a billboard that is off is nothing to hold for",
            effect.contains("!billboardEnabledForDiag")
        )

        val model = source(VIEW_MODEL)
        assertTrue(
            "the model answers 'settled or empty', so the hold can end either way",
            model.contains("val billboardReady: StateFlow<Boolean>") &&
                model.contains("_billboardReady.value = true") &&
                model.contains("_billboardReady.value = false")
        )
        assertTrue(
            "a cleared billboard is an answer, not a settle that never comes",
            model.substringAfter("fun clearBillboard(reason: String) {").contains(
                "_billboardReady.value = true"
            )
        )
        assertTrue(
            "and an empty deal is one too",
            model.substringAfter("if (dealt.isEmpty()) {").contains("_billboardReady.value = true")
        )
    }

    // ── F2 (2026-11-13): the built-ins take the shared Up hook ─────────

    @Test
    fun `the built-in rails go through the shared Up hook`() {
        val builtins = home.substringAfter("val drawBuiltinRail:")
            .substringBefore("fun topRailUpHook(")

        assertFalse(
            "the built-ins no longer hardwire the top bar: that skipped the Spotlight carve-out",
            builtins.contains("openTopBar(requester)")
        )
        assertTrue(
            "they take the hook the other rails take",
            builtins.contains("upHook: ((FocusRequester) -> Unit)?")
        )
        assertTrue(
            "handed the shared hook from their position in the merged order",
            home.split("topRailUpHook(index)").size - 1 >= 2
        )

        val hook = home.substringAfter("fun topRailUpHook(entryIndex: Int)")
            .substringBefore("One pinned Library list")
        assertTrue(
            "and the hook still opens the bar when Spotlight is not active (Modern, S4)",
            hook.contains("else -> { requester: FocusRequester -> openTopBar(requester) }")
        )
    }

    // ── F3 (2026-11-13): the claim retries, then gives up ─

    @Test
    fun `a failed pill claim retries bounded, then gives up for the visit`() {
        val claim = section.substringAfter("LaunchedEffect(pages.isNotEmpty(), claimEntryFocus) {")
            .substringBefore("MobileBillboard(")

        assertTrue(
            "the attempt is retried instead of losing the visit to one miss",
            claim.contains("while (!taken && frames < SPOTLIGHT_CLAIM_RETRY_FRAMES)")
        )
        assertTrue(
            "one frame first, so the pill has a node to take focus",
            claim.contains("withFrameNanos { }")
        )
        assertTrue(
            "the outcome is still traced",
            claim.contains("SpotlightDiagTrace.spotlightClaim(taken)")
        )
        assertTrue("a taken claim closes the latch", claim.contains("if (taken) onEntryFocusClaimed()"))
        assertTrue(
            "a given-up claim closes it too - it must not re-fire on a re-deal",
            claim.contains("else onEntryFocusClaimGivenUp()")
        )
        assertTrue(
            "and the window is a named, bounded constant",
            section.contains("private const val SPOTLIGHT_CLAIM_RETRY_FRAMES = 12")
        )

        val latch = home.substringAfter("onEntryFocusClaimGivenUp = {").substringBefore("},")
        assertTrue(
            "the Home latches the give-up exactly as it latches a claim",
            latch.contains("spotlightEntryFocusClaimed = true")
        )
    }

    @Test
    fun `the Continue Watching landing keeps its own fresh-entry gate`() {
        // Explicitly out of scope: `isFreshEntry` and `recordHomeEntry` are the
        // CW head card's gate, and its one-shot-per-composition behaviour is
        // correct there.
        assertTrue(
            "the CW landing still reads the screen's fresh-entry signal",
            home.contains("isFreshEntry = isFreshEntry,")
        )
        assertTrue(
            "and a return from Detail is still the restore's, not the landing's",
            home.contains("if (isFreshEntry || homeReturnLanded) return@LaunchedEffect")
        )
        assertTrue(
            "the screen's return signal is unchanged",
            home.contains("val isFreshEntry = remember { viewModel.recordHomeEntry() }")
        )
    }

    // ── F2: the dwell re-arms when the details land ───────────────────────

    @Test
    fun `the held card's snapshot identity is a dwell key`() {
        assertTrue(
            "the identity comes from the snapshot, not the object",
            section.contains("val heldSnapshotIdentity = snapshot?.cardIdentity()")
        )
        assertTrue(
            "and it is a key, so the arrival re-runs the effect",
            section.contains(
                "LaunchedEffect(heldNode, expandDelayMs, menuOpen, heldSnapshotIdentity) {"
            )
        )
        assertFalse(
            "the snapshot OBJECT must not be a key - updateBounds copies it on every scroll",
            section.contains("LaunchedEffect(heldNode, expandDelayMs, menuOpen, snapshot)")
        )
    }

    @Test
    fun `a still-unresolved card returns without arming, and is not a dead end`() {
        val effect = section
            .substringAfter("LaunchedEffect(heldNode, expandDelayMs, menuOpen, heldSnapshotIdentity) {")
            .substringBefore("// A scroll under a still-focused card")

        assertTrue(
            "with no snapshot there is nothing to expand yet",
            effect.contains("val armedOn = tracker.snapshot ?: return@LaunchedEffect")
        )
        assertTrue(
            "the held NODE is still required - a card the viewer left never expands",
            effect.indexOf("if (heldNode == null) return@LaunchedEffect") in
                0 until effect.indexOf("val armedOn = tracker.snapshot")
        )
        assertTrue(
            "the panel is built from the arm-time snapshot, not the live one",
            effect.contains("expanded = ExpandedPanel(armedOn, armedNode)")
        )
        assertTrue(
            "and the trace can name the card once its details arrive",
            effect.contains("if (heldNode != null && heldLabel == null) {") &&
                effect.contains("heldLabel = snapshot?.cardIdentity()")
        )
    }

    @Test
    fun `the dwell's own length and the blur grace are untouched`() {
        assertTrue(
            "the 2s expand delay is the viewer's option, unchanged",
            rules.contains("NO_EXPAND_MS") &&
                home.contains("HomeExpandDelay")
        )
        assertTrue(
            "and the blur grace is still the 600ms the survival fix set",
            section.contains("SPOTLIGHT_BLUR_GRACE_MS = 600L")
        )
    }

    // ── F3: the panel draws the full-resolution backdrop ──────────────────

    @Test
    fun `the panel prefers the meta backdrop over the card's still`() {
        assertTrue(
            "full-resolution first, the card's rail image second, the poster last",
            preview.contains("val stillNow = meta?.background ?: snapshot.stillUrl ?: meta?.poster")
        )
        assertFalse(
            "the old order is what made it fuzzy",
            preview.contains("val stillNow = snapshot.stillUrl ?: meta?.background")
        )
        assertTrue(
            "and a title with no art still gets the text tile",
            preview.contains("if (stillNow.isNullOrBlank()) {")
        )
        // F3 fixed the EMPTY-STATE value only, and the trailer's still - the one
        // the panel actually DRAWS (SpotlightPanelTrailer paints it under the
        // surface until the first frame, and whenever no trailer plays) - was
        // left on the old rail-image-first order. 0.9.285 was still fuzzy
        // because of exactly that (spec 2026-11-13, F1 follow-up).
        assertTrue(
            "the panel's drawn still is the one computed above, full-resolution first",
            preview.contains("stillUrl = stillNow,")
        )
        assertFalse(
            "no visible panel image may keep the rail image first",
            preview.contains("stillUrl = snapshot.stillUrl ?: meta?.background")
        )
    }

    @Test
    fun `the rail card keeps its own small image`() {
        // Only the panel changed: the card's `stillUrl` is what it always was.
        val poster = source(POSTER_CARD)
        assertTrue(
            "the card still hands its own poster to the tracker",
            poster.contains("stillUrl = posterUrl")
        )
    }

    // ── F4: the panel's trailer does not outlive the panel ────────────────

    @Test
    fun `the panel's delay is capped, and the billboard's is not`() {
        assertEquals(2_000L, SpotlightTrailerRule.expandedCardDelayMs(6_000L))
        assertEquals(2_000L, SpotlightTrailerRule.expandedCardDelayMs(8_000L))
        assertEquals(2_000L, SpotlightTrailerRule.expandedCardDelayMs(2_000L))
        assertEquals(0L, SpotlightTrailerRule.expandedCardDelayMs(0L))
        assertEquals(0L, SpotlightTrailerRule.expandedCardDelayMs(-5L))

        assertTrue(
            "the cap is a named constant beside the rule",
            rules.contains("const val MAX_EXPANDED_CARD_DELAY_MS = 2_000L")
        )
        assertTrue(
            "the hero passes the viewer's billboard delay itself: Home hands the " +
                "hero section its own trailerDelayMs, untouched by this cap",
            home.contains("trailerDelayMs = spotlightTrailerDelayMs")
        )
    }

    @Test
    fun `the autoplay rule is unchanged, so OFF still vetoes`() {
        assertTrue(
            rules.contains(
                "fun autoplaysInCard(mode: BillboardTrailerMode, heroTrailerAutoplay: Boolean): Boolean =\n" +
                    "        mode != BillboardTrailerMode.OFF && heroTrailerAutoplay"
            )
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
        const val ROOT = "com/kennyb1201/kbstream/ui/home/"
        const val HOME = "${ROOT}HomeScreen.kt"
        const val SPOTLIGHT_HOME = "${ROOT}SpotlightHome.kt"
        const val SPOTLIGHT_PREVIEW = "${ROOT}SpotlightPreview.kt"
        const val LAYOUT_RULES = "${ROOT}HomeLayoutRules.kt"
        const val VIEW_MODEL = "${ROOT}HomeViewModel.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
    }
}
