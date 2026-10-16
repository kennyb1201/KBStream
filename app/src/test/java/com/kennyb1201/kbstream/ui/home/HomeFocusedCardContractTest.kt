package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home restores focus to where the viewer actually IS.
 *
 * Two reported bugs, one mechanism. First, `lastPosterFocusRequester` - the
 * restore target for the top bar's dismiss and for the hold-Up shortcut - was
 * written only where a target is CAPTURED (a long press, a short-press Up on
 * the top rail), so plain D-pad traversal never updated it: holding Up five
 * rails down and dismissing came back to the last card that had been
 * long-pressed, and on a session that had not long-pressed anything the var was
 * still null, so the dismiss was a silent no-op while the bar's chip was
 * disposed by AnimatedVisibility - focus fell to nothing and the D-pad was
 * dead. There is now a second var, kept current by every rail's own focus
 * callback, that those two consumers read.
 *
 * Second, the Continue Watching menu's "Mark as Watched" and "Remove" both
 * deleted the row the stored requester pointed at and then asked it for focus:
 * a detached requester answers false, the boolean was discarded, and the D-pad
 * was stranded - worse when the rail emptied. They now land the rail on its new
 * head, with the verified request the rail's own landing already uses.
 *
 * Read from the source: this is Compose focus wiring, which needs a device and
 * a remote to press.
 */
class HomeFocusedCardContractTest {

    private val home = normalized(stripComments(source(HOME)))
    private val slots = normalized(stripComments(source(SLOTS)))

    @Test
    fun `a focused-card target exists apart from the long-press one`() {
        assertTrue(
            "the card D-pad focus is on is tracked in its own var",
            home.contains("var focusedCardRequester by remember { mutableStateOf<FocusRequester?>(null) }")
        )
        assertTrue(
            "and the long-press capture target is still there for the menus",
            home.contains("var lastPosterFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }")
        )
    }

    @Test
    fun `the top bar's dismiss and the hold-Up restore read where focus is`() {
        assertTrue(
            "the dismiss prefers the focused card and falls back to the captured " +
                "target, for a rail kind that reports no focused card at all",
            home.contains(
                "fun dismissTopBar() { showTopBar = false " +
                    "restoreFocus(focusedCardRequester ?: lastPosterFocusRequester) }"
            )
        )
        assertTrue(
            "and a bare `?.requestFocus()` is not how it gets there: the bar is " +
                "inside an AnimatedVisibility, so the ask can race the frame that " +
                "re-attaches the card - a request that answers false (never " +
                "throws) was discarded by runCatching, the bar closed onto no " +
                "card, and the D-pad went dead",
            !home.contains("(focusedCardRequester ?: lastPosterFocusRequester)?.requestFocus()") &&
                home.contains("fun restoreFocus(requester: FocusRequester?) {") &&
                home.contains("landed = runCatching { requester.requestFocus() }") &&
                home.contains("getOrDefault(false)") &&
                home.contains("while (!landed && attempts < HOME_FOCUS_RETRY_FRAMES) {")
        )
        assertTrue(
            "and every other hand-back goes through it too, so no path can " +
                "silently drop the focus it is responsible for",
            home.contains("fun dismissContinueWatchingMenu() { continueWatchingMenu = null " +
                "restoreFocus(lastPosterFocusRequester) }") &&
                home.contains("fun dismissPosterMenu() { posterMenu = null " +
                    "restoreFocus(lastPosterFocusRequester) }")
        )
        assertTrue(
            "and the hold-Up shortcut captures the same pair at press time",
            home.contains(
                "restoreTargetAtPress = { focusedCardRequester ?: lastPosterFocusRequester }"
            )
        )
    }

    @Test
    fun `every rail kind keeps the focused-card target current`() {
        // The card's own focus block, sliced out of the chain: it hands its
        // requester to the focused-card target AND to the return-from-Detail
        // restore, and swaps the hero. (It used to also write a local
        // `lastFocusedItemKey` that nothing ever read - a dead state write that
        // invalidated the whole HomeScreen composition on every D-pad move; the
        // remembered key is `viewModel.lastFocusedRailItemKey` below.)
        val catalogFocusBlock = home.substringAfter(".onFocusChanged { focusState ->")
            .substringBefore(".then(")
        assertTrue(
            "a catalog card reports itself on focus, not only on long press",
            home.contains("if (focusState.isFocused) {") &&
                catalogFocusBlock.contains("focusedCardRequester = requester") &&
                catalogFocusBlock.contains("selectHero(meta)")
        )
        assertTrue(
            "and the card's key reaches the return-from-Detail restore, so the " +
                "focus the viewer left Home with is one it can land on again",
            catalogFocusBlock.contains("viewModel.lastFocusedRailItemKey") &&
                catalogFocusBlock.contains("railFocusRequesters[cardKey] = requester")
        )
        assertTrue(
            "the Continue Watching rail reports the card its focus lands on",
            home.contains("onFocusedCardRequester(requester)") &&
                home.contains("onFocusedCardRequester = { requester -> focusedCardRequester = requester }")
        )
        assertTrue(
            "and the rails whose requester is made inside the row - the KB " +
                "collection tiles and the browse tiles - hand it up too",
            slots.contains("onFocusedCardRequester?.invoke(requester)") &&
                slots.contains("onFocusedCardRequester: ((FocusRequester) -> Unit)? = null")
        )
    }

    @Test
    fun `a Continue Watching action lands focus on the rail's new head`() {
        assertTrue(
            "the signal the rail's landing watches is bumped by the screen",
            home.contains("var continueWatchingLandOnHeadSignal by remember { mutableStateOf(0) }") &&
                home.contains("continueWatchingLandOnHeadSignal++")
        )
        assertFalse(
            "and neither action asks the deleted card for focus any more: its " +
                "node is the one going away, so the request was guaranteed to " +
                "fail and the boolean was thrown away",
            home.contains("viewModel.markContinueWatchingAsWatched( selectedItem ) " +
                "lastPosterFocusRequester?.requestFocus()") ||
                home.contains("viewModel.removeFromContinueWatching( selectedItem ) " +
                    "lastPosterFocusRequester?.requestFocus()")
        )
        assertTrue(
            "the rail takes the signal and forces a landing on the head, " +
                "clearing the walk latch - the place the viewer had chosen is gone",
            home.contains("landOnHeadSignal: Int = 0,") &&
                home.contains("var answeredLandingSignal by remember { mutableStateOf(landOnHeadSignal) }") &&
                home.contains("if (landOnHeadSignal != answeredLandingSignal) {") &&
                home.contains("viewerWalkedRail = false")
        )
        assertTrue(
            "and that landing is the verified one: scrolled to the head, then " +
                "retried until the request's own answer says it landed",
            home.contains("railState.scrollToItem(CONTINUE_WATCHING_HEAD_INDEX)") &&
                home.contains(
                    "landed = runCatching { headRequester.requestFocus() }.getOrDefault(false)"
                )
        )
    }

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

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
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
