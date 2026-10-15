package com.kennyb1201.kbstream.ui.home

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
 * the rail - so a load arriving while focus sat on the chip could never move
 * it. The viewer scrolled up to reach the rail they came for.
 *
 * The fix completes the landing without weakening the old gate: on a FRESH entry
 * (no Detail-return restore target), with CW data now present and focus still on
 * the chip row, the rail lands on its head. Focus on the chip row is the
 * framework's default rather than a choice, so moving is finishing the landing;
 * focus anywhere else is the viewer having navigated, and is left alone.
 *
 * The rule is a plain function (asserted directly) and the wiring that applies
 * it - the one-shot, the key list, the shared scroll and retry - is asserted
 * from the source, the way [ContinueWatchingHeadLandingContractTest] does for
 * the landing that was already there.
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
    fun `the landing completes only on a fresh entry with focus still on the chips`() {
        assertTrue(
            "the reported case: nothing rests on the rail, the data is here and " +
                "focus is still where entry left it - the landing is owed",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                chipRowHasFocus = true,
                viewerWalkedRail = false
            )
        )
        assertFalse(
            "focus anywhere else (another rail, the top bar) is the viewer having " +
                "navigated, and the old gate's intent holds: leave it alone",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                chipRowHasFocus = false,
                viewerWalkedRail = false
            )
        )
        assertFalse(
            "the viewer entered the rail and left it: never steal back",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = false,
                chipRowHasFocus = true,
                viewerWalkedRail = true
            )
        )
    }

    @Test
    fun `a Detail return is the existing restore's, not this landing's`() {
        assertFalse(
            "a restore target means the ViewModel seeded focus and its own " +
                "restore owns the landing; the fresh branch must never run",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = false,
                initialLandingDone = false,
                chipRowHasFocus = true,
                viewerWalkedRail = false
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
                chipRowHasFocus = true,
                viewerWalkedRail = false
            )
        )
        assertFalse(
            "a second identical emission is a load, not a landing: one shot per " +
                "composition is what keeps a later refresh from moving focus again",
            continueWatchingLandsOnFreshEntry(
                isFreshEntry = true,
                initialLandingDone = true,
                chipRowHasFocus = true,
                viewerWalkedRail = false
            )
        )
    }

    // ----------------------------------------------------------- the wiring --

    @Test
    fun `the fresh branch runs before the gate and reuses the landing verbatim`() {
        assertTrue(
            "the branch is a call to the shared rule with all four inputs",
            landing.contains("continueWatchingLandsOnFreshEntry(") &&
                landing.contains("isFreshEntry = isFreshEntry,") &&
                landing.contains("initialLandingDone = initialLandingDone,") &&
                landing.contains("chipRowHasFocus = chipRowHasFocus,") &&
                landing.contains("viewerWalkedRail = viewerWalkedRail")
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
            "it still returns without touching focus when neither branch applies",
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
    fun `the existing landing keys are untouched, and the branch reads the chip flag from the body`() {
        assertTrue(
            "the rail's effect still re-runs on the head, focus being in the rail " +
                "and the remove signal - the fresh branch lives inside it rather " +
                "than adding a key of its own",
            rail.contains("LaunchedEffect(headId, railHasFocus, landOnHeadSignal)") &&
                !rail.contains("LaunchedEffect(headId, railHasFocus, landOnHeadSignal,")
        )
        assertTrue(
            "and the fresh branch passes the flag through from the rail's own " +
                "argument, so what it reads is the current composition's value",
            rail.contains("chipRowHasFocus: Boolean = false") &&
                landing.contains("chipRowHasFocus = chipRowHasFocus,")
        )
        assertTrue(
            "an empty rail still returns before anything else",
            landing.contains("if (headId == null) return@LaunchedEffect")
        )
    }

    @Test
    fun `the rail takes the two facts as arguments rather than reaching for them`() {
        assertTrue(
            "the fresh-entry fact, defaulting to no landing",
            rail.contains("isFreshEntry: Boolean = false")
        )
        assertTrue(
            "and the chip row's focus, likewise",
            rail.contains("chipRowHasFocus: Boolean = false")
        )
    }

    // ------------------------------------------------------ home supplies them --

    @Test
    fun `home reads the fresh entry from the ViewModel's restore target`() {
        assertTrue(
            "non-null exactly when a Detail return has a restore target, so a " +
                "null one is the fresh entry (see the ViewModel's own contract)",
            home.contains("isFreshEntry = viewModel.lastFocusedRailItemKey == null")
        )
        assertTrue(
            "and hands the literal null-comparison's result to the rail, not a " +
                "second reading of the property inside it",
            rail.contains("isFreshEntry = isFreshEntry,")
        )
    }

    @Test
    fun `home knows the chip row is where focus rests by default`() {
        assertTrue(
            "the state Home holds",
            home.contains("var focusedBrowseChipRowIndex by remember {")
        )
        assertTrue(
            "it names the row rather than being a boolean, so the row losing " +
                "focus cannot clear the row that just took it",
            home.contains("focusedBrowseChipRowIndex = when {") &&
                home.contains("focusedBrowseChipRowIndex == entryIndex ->")
        )
        assertTrue(
            "and the rail is told whether any chip row holds focus",
            home.contains("chipRowHasFocus = focusedBrowseChipRowIndex != null")
        )
    }

    @Test
    fun `the browse chip row reports its own focus`() {
        val slots = source(KB_HOME_SLOTS)
        assertTrue(
            "the rail takes the row's focus callback",
            slots.contains("onRowFocusChanged: ((Boolean) -> Unit)? = null")
        )
        assertTrue(
            "and observes focus on the ROW: hasFocus is \"this node or any child " +
                "of it\", which is what \"the chip row holds focus\" means",
            slots.contains("onRowFocusChanged?.invoke(it.hasFocus)") &&
                slots.contains("modifier = Modifier.onFocusChanged {")
        )
        assertTrue(
            "Home wires it to the state above",
            home.contains("onRowFocusChanged = { hasFocus ->") &&
                home.contains("focusedBrowseChipRowIndex = when {")
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
        const val KB_HOME_SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
