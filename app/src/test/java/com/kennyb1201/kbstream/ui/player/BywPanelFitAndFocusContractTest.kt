package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two field defects on the finished-title credits panel, and the seams that
 * make each one impossible to reintroduce silently.
 *
 * 1. **The thumbs were unreachable by D-pad.** The Like / Dislike pair for the
 *    just-finished title was drawn, but both TV players swallowed UP/DOWN while
 *    the panel was up - a rule written when nothing lived above the pick row.
 *    Letting the press through is only safe once the panel pins the ends of its
 *    own vertical chain (UP from a thumb, DOWN from a pill: both no-ops), which
 *    is what keeps focus search from walking out onto the seek bar underneath.
 *
 * 2. **The pills and the hint were clipped.** Credits mode anchors the panel to
 *    the TOP with `WRAP_CONTENT` height, so anything that overflows is lost off
 *    the bottom - the pills on each card, then the hint. The strip was also
 *    inserted by the FIRST PILL FOCUS, so the panel grew by its whole height at
 *    the exact moment a pill lit up. The fix reserves the strip at build time and
 *    adds a fit pass that shrinks artwork (strip, then posters, then the strip
 *    is dropped) - never the pills and never the hint.
 *
 * There is no device in CI: the wiring is pinned by source contract, and the one
 * pure decision (the fit level) by plain assertions.
 */
class BywPanelFitAndFocusContractTest {

    // ── 1. the vertical chain ────────────────────────────────────────────────

    @Test
    fun `both ends of the vertical chain are self-pinned`() {
        val src = source(BYW)

        assertTrue(
            "UP from a thumb must be a no-op: the row is the top of the panel, and " +
                "an unpinned UP is what walked focus out onto the overlay",
            src.contains("nextFocusUpId = id")
        )

        val build = buildBody(src)
        assertTrue(
            "DOWN from a pill must be a no-op too, or a vertical press on the panel's " +
                "bottom edge escapes it",
            build.contains("play.nextFocusDownId = play.id") &&
                build.contains("details.nextFocusDownId = details.id")
        )
        assertTrue(
            "and that trap must cover EVERY card, not only the first",
            src.contains("// The chain's bottom end: DOWN from a pill is a no-op, for EVERY")
        )
    }

    @Test
    fun `the wiring that makes the thumbs reachable is unchanged`() {
        val build = buildBody(source(BYW))

        assertTrue(
            "UP from a pill reaches the thumb above its column",
            build.contains("play.nextFocusUpId = likeThumb?.id ?: View.NO_ID") &&
                build.contains("details.nextFocusUpId = dislikeThumb?.id ?: View.NO_ID")
        )
        assertTrue(
            "DOWN from a thumb lands on the FIRST card's pills, guarded by index == 0",
            build.contains("if (index == 0) {") &&
                build.contains("likeThumb?.nextFocusDownId = play.id") &&
                build.contains("dislikeThumb?.nextFocusDownId = details.id")
        )
    }

    /**
     * The marks both thumb drawables ship with are baked near-white (#FFF3EFE4),
     * but the SIGNALLED plate is the accent fill - so a pressed thumb drew a
     * near-white mark on a pale accent plate and read as a blank white box (field
     * 2026-10-30, the same frame that reported the pills being clipped: the thumbs
     * were visibly there but gave no sign of what they were). The filled state
     * must tint the mark with the fill's own contrast colour, the way PLAY's label
     * on that same chip does.
     */
    @Test
    fun `a signalled thumb's mark is tinted off its accent plate`() {
        val look = section(
            source(BYW),
            "private fun applyThumbLook(",
            "private fun isThumbActive"
        )
        assertTrue(
            "the mark takes the chip's contrast colour while the plate is filled",
            look.contains("view.imageTintList") && look.contains("R.color.kb_void")
        )
        assertTrue(
            "and only while it is filled: the unsignalled plate keeps the baked mark",
            look.contains("if (active) {") && look.contains("} else {")
        )
    }

    @Test
    fun `hasTasteRow answers from the row's visibility, not from the thumbs existing`() {
        val src = source(BYW)
        assertTrue(
            "the accessor the two players gate on must read the row's visibility: the " +
                "thumbs are built once and survive a row that is GONE",
            src.contains("val hasTasteRow: Boolean") &&
                src.contains("get() = tasteRow?.visibility == View.VISIBLE")
        )
    }

    // ── 2. the key gating in both TV players ────────────────────────────────

    @Test
    fun `the Exo credits branch lets UP and DOWN through only with a thumbs row`() {
        val branch = section(
            source(NATIVE),
            "if (creditsPanelForeground() && event.action == KeyEvent.ACTION_DOWN &&",
            "if (plainPlaybackForeground() && horizontal &&"
        )

        assertTrue(
            "no focus in the panel still parks on the first pick and swallows, so the " +
                "seek bar underneath never sees the press",
            branch.contains("if (!bywUi.hasFocus()) {") &&
                branch.contains("bywUi.focusFirst()") &&
                branch.contains("return true")
        )
        assertTrue(
            "with the thumbs row up, the vertical press goes to the view tree",
            branch.contains("if (isOverlayRaisingKey(event.keyCode)) {") &&
                branch.contains("if (bywUi.hasTasteRow) return super.dispatchKeyEvent(event)")
        )
        assertTrue(
            "a raise without the thumbs row keeps the old swallow: the let-through is " +
                "the only arm that reaches the view tree",
            Regex(
                "if \\(isOverlayRaisingKey\\(event\\.keyCode\\)\\) \\{\\s*" +
                    "if \\(bywUi\\.hasTasteRow\\) return super\\.dispatchKeyEvent\\(event\\)\\s*" +
                    "return true"
            ).containsMatchIn(branch)
        )
        assertFalse(
            "the let-through must return super DIRECTLY, not fall out of the branch: " +
                "plainPlaybackForeground() does not cover this panel, so a fall-through " +
                "can reach the skip-prompt arm and re-swallow the press",
            branch.contains("if (bywUi.hasTasteRow) return true")
        )
    }

    @Test
    fun `the MPV BYW branch does the same, and keeps its park-then-swallow`() {
        val branch = section(
            source(MPV),
            "if (bywUi?.isVisible == true) {",
            "if (mpvScrubDirection != 0) {"
        )

        assertTrue(
            "a press arriving before the row has focus still parks and is swallowed",
            branch.contains("if (bywUi?.hasFocus() == false) {") &&
                branch.contains("bywUi?.focusFirst()") &&
                branch.contains("return true")
        )
        assertTrue(
            "the vertical press reaches the view tree when the thumbs are up",
            branch.contains("if (bywUi?.hasTasteRow == true) return super.dispatchKeyEvent(event)")
        )
    }

    // ── 3. the fit pass ─────────────────────────────────────────────────────

    @Test
    fun `a panel that fits keeps its level, and one that overflows steps down one`() {
        assertEquals(
            "a fitting panel at full size stays full",
            BYW_FIT_LEVEL_FULL,
            fitLevelFor(measuredHeightPx = 480, availableHeightPx = 492, currentLevel = 0)
        )
        assertEquals(
            "an overflowing one steps down exactly one level per layout pass",
            BYW_FIT_LEVEL_COMPACT_STRIP,
            fitLevelFor(measuredHeightPx = 600, availableHeightPx = 492, currentLevel = 0)
        )
        assertEquals(
            "and continues from wherever it already is",
            BYW_FIT_LEVEL_COMPACT_POSTERS,
            fitLevelFor(measuredHeightPx = 600, availableHeightPx = 492, currentLevel = 1)
        )
    }

    @Test
    fun `an overflowing total keeps walking, and equality is still the signal`() {
        // The pass only ever looks at the measured height, never at an assumed
        // saving: a level that did not move the total is still an overflow and
        // the next level is taken. (The clearance pass no longer grows the row's
        // margin, so a shrink always shows - but the rule does not depend on it.)
        assertEquals(
            "an unchanged overflowing total takes the next level",
            BYW_FIT_LEVEL_COMPACT_POSTERS,
            fitLevelFor(measuredHeightPx = 600, availableHeightPx = 492, currentLevel = 1)
        )
    }

    /**
     * The field report behind this pass's second field round (2026-10-30): the
     * panel's BOTTOM was still under the screen edge - no meta line, no PLAY /
     * DETAILS pills, no hint - while the featured strip (and with it the
     * clearlogo and the meta line above the row) had already been dropped by the
     * ladder, and the row ran past the panel's edges.
     *
     * Two seams, both pinned below: a shrunk panel measures the same clamped
     * height, so its bounds never change and the layout listener that drives the
     * ladder would never fire again; and the credits clearance bought vertical
     * room by pushing the row DOWN, which made the panel's height video + row +
     * hint no matter what the ladder shrank.
     */
    @Test
    fun `a level that is applied asks for the next measurement`() {
        val fit = section(
            source(BYW),
            "private fun fitCreditsPanel() {",
            "private fun applyFitLevel(level: Int) {"
        )
        assertTrue(
            "an over-full panel measures the same clamped height at every level, so its " +
                "bounds never change and its layout listener never fires again - without " +
                "this re-ask the ladder stopped after one step",
            fit.contains("OneShotPreDrawListener.add(panel) { fitCreditsPanel() }")
        )
    }

    @Test
    fun `the credits corner narrows the row instead of pushing it down`() {
        val clearance = section(
            source(BYW),
            "private fun keepRowClearOfNotch() {",
            "private fun fitCreditsPanel() {"
        )
        assertTrue(
            "the row ends before the video's left edge, so the corner never costs height",
            clearance.contains("params.marginEnd = inset") &&
                clearance.contains("if (header < creditsNotchPx) creditsInsetPx else 0")
        )
        assertTrue(
            "and the row's own top margin goes back to its XML value",
            clearance.contains("params.topMargin = base")
        )
        assertFalse(
            "never pushed down: that made the panel's total video + row + hint, which is " +
                "how the ladder ran to its floor and still clipped the pills",
            clearance.contains("params.topMargin = margin") ||
                clearance.contains("creditsNotchPx - header)")
        )
    }

    @Test
    fun `every card is reachable across the row`() {
        val build = buildBody(source(BYW))
        assertTrue(
            "the card boundary is named in both directions: the row's window can end before " +
                "the credits video, and a geometric search that stops at the window is what " +
                "left the picks past it unreachable",
            build.contains("prevDetails?.nextFocusRightId = play.id") &&
                build.contains("play.nextFocusLeftId = prevDetails?.id ?: View.NO_ID")
        )
        assertTrue(
            "and the row's right end is a no-op, the way DOWN is for every pill",
            build.contains("details.nextFocusRightId = details.id")
        )
    }

    @Test
    fun `the level never steps up within a raise, and stops at the floor`() {
        assertEquals(
            "a panel that fits AFTER a shrink keeps the shrink for this raise",
            BYW_FIT_LEVEL_COMPACT_POSTERS,
            fitLevelFor(measuredHeightPx = 400, availableHeightPx = 492, currentLevel = 2)
        )
        assertEquals(
            "and the deepest level is the strip hidden - never below it",
            BYW_FIT_LEVEL_STRIP_HIDDEN,
            fitLevelFor(measuredHeightPx = 900, availableHeightPx = 492, currentLevel = 3)
        )
        assertEquals(
            "no available height is not an overflow to act on",
            BYW_FIT_LEVEL_FULL,
            fitLevelFor(measuredHeightPx = 900, availableHeightPx = 0, currentLevel = 0)
        )
    }

    /**
     * The clipped-panel case - the one this pass exists for, and the one it used to
     * miss. The panel is a `WRAP_CONTENT` child of a clipping parent, so it is
     * measured with an `AT_MOST` height: an over-full panel measures EXACTLY the
     * available height and never a pixel more. Reading that equality as "it fits"
     * is how the Like / Dislike row added to the header could push the PLAY /
     * DETAILS pills and the hint under the clip with the ladder never running
     * (field 2026-10-29: "no way to reach the thumbs, and the play and details I
     * can't see - I don't know which selection I'm on").
     */
    @Test
    fun `a clipped panel measures exactly the available height, and equality is the overflow`() {
        assertEquals(
            "a panel clipped to the viewport reports the viewport height, not its own",
            BYW_FIT_LEVEL_COMPACT_STRIP,
            fitLevelFor(measuredHeightPx = 492, availableHeightPx = 492, currentLevel = 0)
        )
        assertEquals(
            "and it keeps walking while it is still clipped",
            BYW_FIT_LEVEL_COMPACT_POSTERS,
            fitLevelFor(measuredHeightPx = 492, availableHeightPx = 492, currentLevel = 1)
        )
        assertEquals(
            "at the floor it stops, so a panel that cannot be made to fit cannot loop",
            BYW_FIT_LEVEL_STRIP_HIDDEN,
            fitLevelFor(measuredHeightPx = 492, availableHeightPx = 492, currentLevel = 3)
        )
        assertEquals(
            "a panel that fits with even a pixel to spare is left at full size",
            BYW_FIT_LEVEL_FULL,
            fitLevelFor(measuredHeightPx = 491, availableHeightPx = 492, currentLevel = 0)
        )
    }

    @Test
    fun `the pass is credits-only and runs after the notch clearance`() {
        val src = source(BYW)
        assertTrue(
            "credits mode is the gate: the centered box (and the External player) " +
                "must not be shrunk",
            src.contains("if (creditsInsetPx <= 0 || !isVisible) return")
        )
        val listener = section(
            src,
            "val onLayout = View.OnLayoutChangeListener {",
            "panel.addOnLayoutChangeListener(onLayout)"
        )
        assertTrue(
            "the clearance pass runs first, so the fit pass measures what is on screen",
            listener.indexOf("keepRowClearOfNotch()") >= 0 &&
                listener.indexOf("fitCreditsPanel()") > listener.indexOf("keepRowClearOfNotch()")
        )
        assertTrue(
            "the available height is the parent's, less the panel's own margins",
            src.contains("(panel.parent as? View)?.height ?: 0") &&
                src.contains("params.topMargin - params.bottomMargin")
        )
    }

    // ── 4. reservation, and the strip's visibility rule ──────────────────────

    @Test
    fun `build() reserves the featured strip before any pill takes focus`() {
        val build = buildBody(source(BYW))
        assertTrue(
            "the strip must be inserted at build time: it used to arrive on first pill " +
                "focus, growing the panel at the moment a pill lit up",
            build.contains("picks.firstOrNull()?.let { feature(it) }")
        )
        assertTrue(
            "and before the metadata pass, so the first measure already includes it",
            build.indexOf("picks.firstOrNull()?.let { feature(it) }") <
                build.indexOf("Clear-logo + description pass")
        )
        assertFalse(
            "an empty pick list simply has nothing to feature - no fallback pick",
            build.contains("picks[0]")
        )
    }

    @Test
    fun `a new raise resets the strip to full size and puts it back on screen`() {
        val build = buildBody(source(BYW))
        assertTrue(
            "the fit level restarts at full for every raise",
            build.contains("fitLevel = BYW_FIT_LEVEL_FULL") &&
                build.contains("resetFeaturedSizing()")
        )
        // The end landmark is the NEXT function, not a doc line: the clearance
        // pass's own comment changed when it stopped pushing the row down, and a
        // landmark that lives in prose is a test that breaks on a rewrite of the
        // prose rather than on a change of behaviour.
        val reset = section(
            source(BYW),
            "private fun resetFeaturedSizing() {",
            "private fun clearRowClearance()"
        )
        assertTrue(
            "and a strip the last raise hid comes back",
            reset.contains("if (strip.visibility != View.VISIBLE) strip.visibility = View.VISIBLE")
        )
        assertTrue(
            "at the full backdrop height and the full description lines",
            reset.contains("dp(STRIP_BACKDROP_FULL_DP)") &&
                reset.contains("desc.maxLines = STRIP_DESC_FULL_LINES")
        )
    }

    @Test
    fun `filling the strip never changes its visibility`() {
        val feature = section(
            source(BYW),
            "private fun feature(pick: BywPick) {",
            "/** Clips the pick posters and the featured backdrop"
        )
        assertFalse(
            "hide-by-fit is terminal for the raise: a later fill must not resurrect it",
            feature.contains("strip.visibility") ||
                feature.contains("TAG_FEATURED_STRIP)?.visibility")
        )
    }

    @Test
    fun `the fit pass shrinks in place, never by rebuilding the cards`() {
        val fit = section(
            source(BYW),
            "private fun applyFitLevel(level: Int) {",
            "private fun resetFeaturedSizing() {"
        )
        assertFalse(
            "a rebuild would move focus off the pill the viewer is on",
            fit.contains("row.removeAllViews") || fit.contains("cards.clear()")
        )
        assertTrue(
            "the poster frame and its captions are mutated through the refs build() kept",
            fit.contains("refs.posterFrame") &&
                fit.contains("refs.nameView") &&
                fit.contains("refs.reasonView") &&
                fit.contains("refs.metaView")
        )
        assertTrue(
            "and the strip's seam is its tagged backdrop height and desc line count",
            fit.contains("TAG_FEATURED_BACKDROP") && fit.contains("maxLines = lines")
        )
    }

    // ── 5. nothing came back from the dead ──────────────────────────────────

    @Test
    fun `no countdown was reintroduced anywhere in this change`() {
        listOf(BYW, NATIVE, MPV).forEach { path ->
            val src = source(path)
            listOf("bywCountdown", "autoplayCountdown", "COUNTDOWN_MS").forEach { forbidden ->
                assertFalse("$path must not grow $forbidden", src.contains(forbidden))
            }
        }
    }

    // ── sources ─────────────────────────────────────────────────────────────

    private fun buildBody(src: String): String = section(
        src,
        "fun build(picks: List<BywPick>) {",
        "/** The imdb id PLAY / DETAILS hand back for a pick. */"
    )

    /** [src] from [from] (exclusive) to [to], asserting both landmarks exist. */
    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: $from", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark: $to", end >= 0)
        return rest.substring(0, end)
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val ROOT = "com/kennyb1201/kbstream/ui/player/"
        const val BYW = ROOT + "BecauseYouWatched.kt"
        const val NATIVE = ROOT + "NativePlayerActivity.kt"
        const val MPV = ROOT + "MpvPlayerActivity.kt"
    }
}
