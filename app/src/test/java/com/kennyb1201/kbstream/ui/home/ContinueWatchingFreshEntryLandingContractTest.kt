package com.kennyb1201.kbstream.ui.home

import androidx.compose.ui.input.key.Key
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home completes its initial landing on Continue Watching.
 *
 * Reported (field, 2026-10-15): "after home finishes loading up fully the focus
 * stays on the first genre browse chip because that's the first item that loads
 * and doesn't go to continue watching like it should." The chip row composes
 * first out of fast local data and takes focus, and the rail's own head-landing
 * is gated on `railHasFocus` - which is only true once focus is already inside
 * the rail - so a load arriving while focus sat on the chip could never move it.
 *
 * Reported again (field, 2026-10-22, Onn 4K Plus, 0.9.25): the landing existed
 * but lost its race, session to session. Continue Watching composes EMPTY until
 * its data emits, so the framework's default focus landed on whichever
 * focusable existed first - the chip row sometimes, the Upcoming rail's head or
 * a catalog poster the rest of the time - and the gate read that LOCATION as if
 * the viewer had chosen it, declining its own landing silently.
 *
 * The gate is the viewer's INTENT now: `viewerMovedFocusSinceEntry`, latched by
 * Home's root preview handler on the first deliberate D-pad press after entry.
 * Where focus rests is not an input at all, which is exactly what these tests
 * pin - the old proxy's parameters are gone from the rule.
 *
 * The rule is a plain function (asserted directly) and the wiring that applies
 * it - the latch, the one-shot, the key list, the shared scroll and retry, the
 * HOME_FOCUS report - is asserted from the source, the way
 * [ContinueWatchingHeadLandingContractTest] does for the landing that was
 * already there.
 *
 * Reported again (field, 2026-10-24, Onn, cold start): the landing LANDED and
 * the viewer still could not see it - focus was on the Continue Watching head
 * and the hero was showing that title, but the rail itself sat below the fold,
 * so the first D-pad press was doing the scroll by hand. Focus delivery was the
 * whole verdict, and nothing asked the rails column to bring the section into
 * view. So the fresh branch now brings the rail in through the column's own
 * BringIntoViewSpec (the same scroll traversal makes) and its verdict is focus
 * AND visibility ([landingCardOnScreen]); a landing that delivers focus onto a
 * card it never saw says exactly that on HOME_FOCUS.
 */
class ContinueWatchingFreshEntryLandingContractTest {

    private val home by lazy { source(HOME_SCREEN) }

    /** The Continue Watching rail's own body, to the Upcoming rail after it. */
    private val rail by lazy {
        home.substringAfter("private fun BuiltinContinueWatchingRail(")
            .substringBefore("private fun BuiltinUpcomingRail(")
    }

    /** The landing effect's own body, from its key list to the Column it heads. */
    private val landing by lazy {
        rail.substringAfter("LaunchedEffect(headId, railHasFocus, landOnHeadSignal")
            .substringBefore("Column(")
    }

    // ------------------------------------------------------------- the rule --

    @Test
    fun `the landing completes on a fresh entry until the viewer moves focus`() {
        assertTrue(
            "the reported case: fresh, nothing landed yet, the viewer has pressed " +
                "nothing - the landing is owed, no matter which rail the framework " +
                "defaulted focus to",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                viewerMovedFocusSinceEntry = false
            )
        )
        assertFalse(
            "a viewer who has started navigating is never relocated: focus went " +
                "where they put it",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                viewerMovedFocusSinceEntry = true
            )
        )
    }

    @Test
    fun `the rule has no focus-location input left`() {
        assertTrue(
            "the location proxy is what lost the race: the gate must not be able " +
                "to ask where focus is, so neither the chip row nor the rail's " +
                "own walk latch is a parameter",
            !home.contains("chipRowHasFocus") &&
                !home.contains("focusedBrowseChipRowIndex") &&
                !source(HOME_UP_NEXT).contains("chipRowHasFocus")
        )
    }

    @Test
    fun `a Detail return is the existing restore's, not this landing's`() {
        assertFalse(
            "a return has a restore target and the ViewModel's own restore owns " +
                "the landing; the fresh branch must never run",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = false,
                initialLandingDone = false,
                viewerMovedFocusSinceEntry = false
            )
        )
    }

    @Test
    fun `the same emission does not land twice`() {
        assertTrue(
            "the first pass lands, and marks itself done",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                viewerMovedFocusSinceEntry = false
            )
        )
        assertFalse(
            "a second identical emission is a load, not a landing: one shot per " +
                "composition is what keeps a later refresh from moving focus again",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = true,
                viewerMovedFocusSinceEntry = false
            )
        )
    }

    // --------------------------------------------------- the second verdict --

    /** The rails band under the hero: the viewport the check measures against. */
    private val viewportTop = 400f
    private val viewportBottom = 700f

    @Test
    fun `the reported case - focus on a card below the fold - is not a landing`() {
        assertFalse(
            "focus said yes, the viewer saw the hero: the head card sat entirely " +
                "under the rails viewport",
            landingCardOnScreen(
                cardTopPx = 720f,
                cardBottomPx = 840f,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
        assertFalse(
            "and a card poking a few pixels over the fold edge is the same " +
                "report one press later, so it is not one either",
            landingCardOnScreen(
                cardTopPx = 650f,
                cardBottomPx = 770f,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
    }

    @Test
    fun `a card resting in the rails band is a landing, on the line included`() {
        assertTrue(
            "the landing's own inset: the card is inside the band",
            landingCardOnScreen(
                cardTopPx = 420f,
                cardBottomPx = 540f,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
        assertTrue(
            "on the line exactly, with the float rounding the measured " +
                "geometry can leave behind",
            landingCardOnScreen(
                cardTopPx = viewportTop - 0.4f,
                cardBottomPx = viewportBottom + 0.4f,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
        assertFalse(
            "but not a half-pixel rule that lets a real clip through: 4px over " +
                "the top is a card the viewer has to press Up to see",
            landingCardOnScreen(
                cardTopPx = viewportTop - 4f,
                cardBottomPx = 540f,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
    }

    @Test
    fun `unmeasured bounds never read as visible`() {
        assertFalse(
            "a card that has not been laid out yet is not a card the viewer can " +
                "see, and the landing retries instead of claiming it",
            landingCardOnScreen(
                cardTopPx = Float.NaN,
                cardBottomPx = Float.NaN,
                viewportTopPx = viewportTop,
                viewportBottomPx = viewportBottom
            )
        )
        assertFalse(
            "nor is a column that has not reported its own bounds",
            landingCardOnScreen(
                cardTopPx = 420f,
                cardBottomPx = 540f,
                viewportTopPx = Float.NaN,
                viewportBottomPx = Float.NaN
            )
        )
        assertFalse(
            "and a viewport with no height contains nothing",
            landingCardOnScreen(
                cardTopPx = 500f,
                cardBottomPx = 540f,
                viewportTopPx = 500f,
                viewportBottomPx = 500f
            )
        )
    }

    // ----------------------------------------------------------- the wiring --

    @Test
    fun `the fresh-entry landing brings the rail in, through the column's own spec`() {
        assertTrue(
            "the rail holds a bring-into-view anchor and attaches it to its own " +
                "section, so the request moves the rail the landing chose rather " +
                "than the head card alone",
            rail.contains("val railAnchor = remember { BringIntoViewRequester() }") &&
                rail.contains(".bringIntoViewRequester(railAnchor)")
        )
        val loop = landing.substringAfter(
            "while (!landed && attempts < CONTINUE_WATCHING_LANDING_ATTEMPTS) {"
        )
        assertTrue(
            "asked on every fresh-entry attempt, because a request against an " +
                "unlaid node is a no-op rather than a failure",
            loop.contains("runCatching { railAnchor.bringIntoView() }")
        )
        assertTrue(
            "only from the fresh branch: a mid-session head change keeps its " +
                "focus-only landing, which is what keeps a load from yanking the " +
                "page under the viewer",
            loop.contains("if (freshEntryLanding) {") &&
                loop.indexOf("if (freshEntryLanding) {") <
                loop.indexOf("railAnchor.bringIntoView()")
        )
        assertEquals(
            "one call site - the branch cannot be entered twice",
            1,
            rail.split("railAnchor.bringIntoView()").size - 1
        )
    }

    @Test
    fun `the verdict is focus delivered AND the card on screen`() {
        assertTrue(
            "focus is still read from its own answer, through the shared verdict",
            landing.contains("landed = verdict.accepted(") &&
                landing.contains("if (landed) focusDelivered = true")
        )
        assertTrue(
            "and visibility is asked of the head card against the rails viewport",
            landing.contains("!landingCardOnScreen(") &&
                landing.contains("cardTopPx = headCardBounds.topPx") &&
                landing.contains("cardBottomPx = headCardBounds.bottomPx") &&
                landing.contains("viewportTopPx = railsViewportBounds.topPx")
        )
        assertTrue(
            "inside the retry loop, so the column's scroll can settle before the " +
                "landing is called",
            landing.indexOf("while (!landed && attempts < CONTINUE_WATCHING_LANDING_ATTEMPTS) {") <
                landing.indexOf("!landingCardOnScreen(")
        )
        assertTrue(
            "only the fresh entry is held to it: a head change and a rail re-entry " +
                "keep the focus-only verdict they had",
            landing.substringBefore("!landingCardOnScreen(").contains("freshEntryLanding &&")
        )
    }

    @Test
    fun `a focus landing with the card off screen says so, distinctly`() {
        assertTrue(
            "the field's next report must be able to tell a focus failure from a " +
                "scroll one",
            landing.contains("\" landed off-screen after \"") &&
                landing.contains("focusDelivered ->")
        )
        assertTrue(
            "and the give-up line still exists for a focus that never answered",
            landing.contains("\" gave up after \"")
        )
    }

    @Test
    fun `home supplies both measurements from layout, and nothing composes on them`() {
        assertTrue(
            "the column records its own window bounds",
            home.contains("val railsViewportBounds = remember { LandingBounds() }") &&
                home.contains("railsViewportBounds.record(")
        )
        assertTrue(
            "and hands the rail the holder rather than a copy of the numbers",
            rail.contains("railsViewportBounds: LandingBounds,") &&
                home.contains("railsViewportBounds = railsViewportBounds,")
        )
        assertTrue(
            "the head card is the only card measured",
            rail.contains("if (index == CONTINUE_WATCHING_HEAD_INDEX) {") &&
                rail.contains("headCardBounds.record(")
        )
        assertFalse(
            "a holder, not state: writing a state on every scroll frame would " +
                "recompose the rail for a number nothing composes on",
            rail.contains("val headCardBounds by remember { mutableStateOf") ||
                home.contains("val railsViewportBounds by remember { mutableStateOf")
        )
    }

    @Test
    fun `the fresh branch runs before the gate and reuses the landing verbatim`() {
        assertTrue(
            "the branch is a call to the shared rule with the three inputs",
            landing.contains("continueWatchingLandsOnFreshEntry(") &&
                landing.contains("isFreshEntry = isFreshEntry,") &&
                landing.contains("initialLandingDone = initialLandingDone,") &&
                landing.contains("viewerMovedFocusSinceEntry = viewerMovedFocusSinceEntry")
        )
        val fresh = landing.indexOf("continueWatchingLandsOnFreshEntry(")
        val gate = landing.indexOf("continueWatchingLandsOnHead(railHasFocus, viewerWalkedRail)")
        assertTrue(
            "evaluated before the existing gate, whose intent is unchanged",
            fresh >= 0 && gate > fresh
        )
        assertTrue(
            "and it marks itself done, which is the one-shot",
            landing.contains("initialLandingDone = true")
        )
        assertEquals(
            "the scroll and the retry loop are the rail's single landing path, " +
                "not a second copy: the fresh branch falls into them",
            1,
            rail.split("scrollToItem(").size - 1
        )
        assertEquals(
            "one focus ask, one retry budget - reused, not duplicated",
            1,
            rail.split("headRequester.requestFocus()").size - 1
        )
        assertTrue(
            "the succeeding correction still returns without touching focus when " +
                "neither branch applies",
            landing.contains("return@LaunchedEffect")
        )
    }

    @Test
    fun `the one-shot is remembered, starts false and is not reset by the remove signal`() {
        assertTrue(
            "one flag for the life of the composition",
            rail.contains("var initialLandingDone by remember { mutableStateOf(false) }")
        )
        assertFalse(
            "the remove signal's landing is not this landing: resetting the " +
                "one-shot with it would let a fresh entry re-land after every " +
                "delete, moving focus the viewer did not ask to move",
            landing.contains("initialLandingDone = false")
        )
        assertTrue(
            "the remove signal's own latch still clears, as it did",
            landing.contains("viewerWalkedRail = false")
        )
    }

    @Test
    fun `the existing landing keys are untouched, and the branch reads the latch from the body`() {
        assertTrue(
            "the rail's effect still re-runs on the head, focus being in the rail " +
                "and the remove signal - the fresh branch lives inside it rather " +
                "than adding a key of its own",
            rail.contains("LaunchedEffect(headId, railHasFocus, landOnHeadSignal)") &&
                !rail.contains("LaunchedEffect(headId, railHasFocus, landOnHeadSignal,")
        )
        assertTrue(
            "and the fresh branch passes the latch through from the rail's own " +
                "argument, so what it reads is the current composition's value",
            rail.contains("viewerMovedFocusSinceEntry: Boolean = false") &&
                landing.contains(
                    "viewerMovedFocusSinceEntry = viewerMovedFocusSinceEntry"
                )
        )
        assertTrue(
            "an empty rail still returns before anything else",
            landing.contains("if (headId == null) return@LaunchedEffect")
        )
    }

    @Test
    fun `the rail takes both facts as arguments rather than reaching for them`() {
        assertTrue(
            "the fresh-entry fact, defaulting to no landing",
            rail.contains("isFreshEntry: Boolean = false")
        )
        assertTrue(
            "and the viewer's intent, likewise",
            rail.contains("viewerMovedFocusSinceEntry: Boolean = false")
        )
    }

    // ------------------------------------------------------------ the latch --

    @Test
    fun `the first deliberate D-pad press after entry latches the viewer's intent`() {
        assertTrue(
            "the latch itself, false on entry because it is a new composition",
            home.contains("var viewerMovedFocusSinceEntry by remember {") &&
                home.contains("mutableStateOf(false)")
        )
        assertTrue(
            "observed at the ROOT, on a preview handler: every D-pad press reaches " +
                "it before the focused node, whether that is a card, a chip or the " +
                "top bar",
            home.contains(".onPreviewKeyEvent { event ->") &&
                home.contains("event.type == KeyEventType.KeyDown &&") &&
                home.contains("isHomeFocusMoveKey(event.key)")
        )
        assertTrue(
            "and it only observes - the press still has to reach the focus system",
            home.contains("viewerMovedFocusSinceEntry = true\n                }\n                false")
        )
    }

    @Test
    fun `the latch counts the D-pad and nothing else`() {
        assertTrue(
            "all four directions and Select, which chooses what is under focus",
            isHomeFocusMoveKey(Key.DirectionUp) &&
                isHomeFocusMoveKey(Key.DirectionDown) &&
                isHomeFocusMoveKey(Key.DirectionLeft) &&
                isHomeFocusMoveKey(Key.DirectionRight) &&
                isHomeFocusMoveKey(Key.DirectionCenter)
        )
        assertFalse(
            "Back is not a navigation step, and dismissing an overlay moves no focus",
            isHomeFocusMoveKey(Key.Back)
        )
    }

    // --------------------------------------------------------- the reporting --

    @Test
    fun `both decisions are reported on HOME_FOCUS, once each`() {
        assertTrue(
            "the landing names the head it chose",
            landing.contains("\"Fresh-entry landing on Continue Watching head \"")
        )
        assertTrue(
            "and the suppression names the latch that caused it",
            landing.contains("\"Fresh-entry landing declined: viewer moved focus first; \"") &&
                landing.contains("loggedFreshEntryDecline = true")
        )
        assertEquals(
            "the decline is said once per composition, not once per load",
            1,
            landing.split("loggedFreshEntryDecline = true").size - 1
        )
        assertEquals(
            "and the landing line is emitted from one place, after the landing",
            1,
            rail.split("Fresh-entry landing on Continue Watching head").size - 1
        )
    }

    // ------------------------------------------------------ home supplies them --

    @Test
    fun `home reads the fresh entry from the ViewModel's own return signal`() {
        val viewModel = source(HOME_VIEW_MODEL)
        assertTrue(
            "the signal lives in the ViewModel, the only thing that outlives " +
                "the screen: Home is a route, so leaving it disposes everything " +
                "remembered inside",
            viewModel.contains("private var homeEntered = false") &&
                viewModel.contains("fun recordHomeEntry(): Boolean {")
        )
        assertFalse(
            "and it is NOT the remembered card. `no card remembered` and `a " +
                "fresh entry` are different facts: a return from Detail on a " +
                "card that could not name itself left a null key, which read " +
                "as an arrival and let the landing below move focus off the " +
                "place the viewer had come back to",
            home.contains("isFreshEntry = viewModel.lastFocusedRailItemKey == null")
        )
        assertTrue(
            "asked once per composition and held, so a recomposition cannot " +
                "change the answer mid-visit",
            home.contains("val isFreshEntry = remember { viewModel.recordHomeEntry() }")
        )
        assertTrue(
            "and the answer is handed to the rail, not a second reading of " +
                "anything inside it",
            rail.contains("isFreshEntry = isFreshEntry,")
        )
    }

    @Test
    fun `the browse row no longer reports focus, because nothing reads it`() {
        val slots = source(KB_HOME_SLOTS)
        assertFalse(
            "the proxy's plumbing is gone with it",
            slots.contains("onRowFocusChanged") &&
                home.contains("onRowFocusChanged")
        )
    }

    // ------------------------------------------------------------- test plumbing --

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val HOME_UP_NEXT = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val KB_HOME_SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
