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

    // ----------------------------------------------------------- the wiring --

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
