package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching lands on its HEAD card when the rail finishes loading.
 *
 * Reported: the rail loaded in stages - the local snapshot's "most recent" card
 * arrived first and took focus, then the enriched merge put a newer card in
 * front of it. The cards are keyed by identity, so the focus node followed the
 * card that MOVED: focus stayed on the card that used to be first, the rail kept
 * that card at its leading edge, and the actual first card sat off-screen to the
 * left until the viewer pressed Left.
 *
 * The landing is a Compose effect, a `LazyListState` scroll and a focus request
 * - nothing a unit test can press - so the RULE it applies is a plain function
 * (asserted here directly) and the wiring that applies it is asserted from the
 * source, the way the rail-up hook and the collection rail's landing are (see
 * HomeRailUpHookContractTest, CollectionRailFocusLandingContractTest).
 */
class ContinueWatchingHeadLandingContractTest {

    private val home by lazy { source(HOME_SCREEN) }

    /** The Continue Watching rail's own body, to the Upcoming rail after it. */
    private val rail by lazy {
        home.substringAfter("private fun BuiltinContinueWatchingRail(")
            .substringBefore("private fun BuiltinUpcomingRail(")
    }

    /** The landing effect's own body, from its key list to the Column it heads. */
    private val landing by lazy {
        rail.substringAfter("LaunchedEffect(headId, railHasFocus)")
            .substringBefore("Column(")
    }

    /** The LazyRow's own arguments. */
    private val row by lazy {
        rail.substringAfter("LazyRow(").substringBefore(") {")
    }

    // ------------------------------------------------------------- the rule --

    @Test
    fun `the rail lands on its head only while the viewer is in it and has not walked it`() {
        assertTrue(
            "the reported case: the viewer is in the rail, so a load that " +
                "re-orders it must put focus back on the first card",
            continueWatchingLandsOnHead(railHasFocus = true, viewerWalkedRail = false)
        )
        assertFalse(
            "a load that lands while focus is in the top bar or another rail " +
                "must not pull the viewer into this one",
            continueWatchingLandsOnHead(railHasFocus = false, viewerWalkedRail = false)
        )
        assertFalse(
            "a rail the viewer walked is theirs, whatever loads behind them",
            continueWatchingLandsOnHead(railHasFocus = true, viewerWalkedRail = true)
        )
        assertFalse(
            "and still theirs once focus has left it",
            continueWatchingLandsOnHead(railHasFocus = false, viewerWalkedRail = true)
        )
    }

    @Test
    fun `focus on any card but the head is the viewer walking the rail`() {
        assertEquals(
            "the head is card 0 in this rail and nowhere else",
            0,
            CONTINUE_WATCHING_HEAD_INDEX
        )
        assertFalse(
            "what Compose focuses when Home opens, and what the landing asks " +
                "for, is not the viewer walking the rail",
            continueWatchingWalkedByFocus(CONTINUE_WATCHING_HEAD_INDEX)
        )
        assertTrue(
            "one D-pad step along the row is",
            continueWatchingWalkedByFocus(CONTINUE_WATCHING_HEAD_INDEX + 1)
        )
        assertTrue(
            "so is being four cards in, the field test's case",
            continueWatchingWalkedByFocus(CONTINUE_WATCHING_HEAD_INDEX + 4)
        )
    }

    // ----------------------------------------------------------- the wiring --

    @Test
    fun `the landing runs on the head's identity, not on every list emission`() {
        assertTrue(
            "the head is what the effect is keyed on",
            rail.contains("val headId = cards.firstOrNull()?.id")
        )
        assertTrue(
            "keyed on the head AND on focus being in the rail: the second is " +
                "what corrects a load that landed while the viewer was elsewhere",
            rail.contains("LaunchedEffect(headId, railHasFocus)")
        )
        assertFalse(
            "keyed on the card list, every re-emission of the same cards would " +
                "re-run the landing",
            rail.contains("LaunchedEffect(cards")
        )
        assertTrue(
            "an empty rail has no head to land on",
            landing.contains("if (headId == null) return@LaunchedEffect")
        )
    }

    @Test
    fun `the landing asks the shared rule before it touches focus or the scroll`() {
        assertTrue(
            "focus and the scroll both hang off the one gate",
            landing.contains("continueWatchingLandsOnHead(railHasFocus, viewerWalkedRail)")
        )
        assertEquals(
            "one call site: no other rail in Home lands its own head",
            1,
            home.split("continueWatchingLandsOnHead(railHasFocus, viewerWalkedRail)").size - 1
        )
    }

    @Test
    fun `the row is put back on its head before the focus is asked for`() {
        val scroll = landing.indexOf("railState.scrollToItem(CONTINUE_WATCHING_HEAD_INDEX)")
        val ask = landing.indexOf("headRequester.requestFocus()")
        assertTrue("the rail scrolls its head into view", scroll >= 0)
        assertTrue("and then asks for focus on it", ask >= 0)
        assertTrue(
            "in that order: a request against a card that is not laid out is a " +
                "silent miss",
            scroll < ask
        )
        assertEquals(
            "this rail's scroll is the only scrollToItem in Home",
            1,
            home.split("scrollToItem(").size - 1
        )
    }

    @Test
    fun `the focus ask reports its own failure, so it is retried rather than lost`() {
        assertTrue(
            "requestFocus() misses by RETURNING false, so the loop reads the " +
                "value - runCatching alone would look like success on frame one",
            landing.contains(
                "landed = runCatching { headRequester.requestFocus() }.getOrDefault(false)"
            )
        )
        assertTrue(
            "bounded, so a rail that never attaches its head cannot spin",
            landing.contains(
                "while (!landed && attempts < CONTINUE_WATCHING_LANDING_ATTEMPTS)"
            )
        )
        assertTrue(
            "a frame per try, so the card can be composed between them",
            landing.contains("awaitFrame()")
        )
        assertTrue(CONTINUE_WATCHING_LANDING_ATTEMPTS > 1)
    }

    @Test
    fun `the rail knows whether focus is inside it`() {
        assertTrue(
            "the rail holds its own scroll position now",
            row.contains("state = railState") &&
                rail.contains("val railState = rememberLazyListState()")
        )
        assertTrue(
            "and observes focus on the ROW: hasFocus is \"this node or any " +
                "child of it\", which is what a rail's focus means",
            row.contains("railHasFocus = it.hasFocus") &&
                row.contains("Modifier.onFocusChanged")
        )
        assertTrue(
            "the flag is the rail's own state, starting false",
            rail.contains("var railHasFocus by remember { mutableStateOf(false) }")
        )
        assertTrue(
            "and the walk flag starts false for a fresh rail",
            rail.contains("var viewerWalkedRail by remember { mutableStateOf(false) }")
        )
    }

    @Test
    fun `the head card is focusable through the rail's own requester`() {
        assertTrue(
            "the rail owns the head's handle - a card's requester lives inside " +
                "the items block, where the landing cannot reach it",
            rail.contains("val headRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "the list is indexed so a card knows its own position",
            rail.contains("itemsIndexed(")
        )
        assertTrue(
            "position 0 is the head",
            rail.contains("if (index == CONTINUE_WATCHING_HEAD_INDEX) {")
        )
        assertTrue(
            "every other card keeps a requester of its own",
            rail.contains("val ownRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "and the card is still wired to whichever one it was given",
            rail.contains("focusRequester = requester")
        )
        assertTrue(
            "the long-press restore keeps pointing at the card's own requester",
            rail.contains("onCardFocusRequester(requester)")
        )
    }

    @Test
    fun `the card's identity key is untouched, which is what makes the landing necessary`() {
        assertTrue(
            "keyed by card identity: that is why focus follows the card that " +
                "moved rather than staying on position 0",
            rail.contains("key = { _, item -> item.id }")
        )
    }

    @Test
    fun `a card's own focus marks the rail walked, and the hero callback still fires`() {
        assertTrue(
            "the walk is decided from the card's index",
            rail.contains("if (continueWatchingWalkedByFocus(index)) {")
        )
        assertTrue(
            "and it is the viewer's own place, so the landing's focus on the " +
                "head is not mistaken for one",
            rail.contains("viewerWalkedRail = true")
        )
        assertTrue(
            "the hero still follows every card focus",
            rail.contains("onSelectHero(hero, item)")
        )
        assertEquals(
            "one hero callback per card: the wrapper is not a second call site",
            1,
            rail.split("onSelectHero(hero, item)").size - 1
        )
    }

    // ------------------------------------------------------ nothing else moved --

    @Test
    fun `the Upcoming rail and the card's own UI are untouched`() {
        val upcoming = home.substringAfter("private fun BuiltinUpcomingRail(")
            .substringBefore("private fun SectionTitle(")
        assertFalse(
            "the Upcoming rail gets no landing of its own",
            upcoming.contains("scrollToItem") ||
                upcoming.contains("continueWatchingLandsOnHead") ||
                upcoming.contains("headRequester")
        )
        val card = home.substringAfter("private fun CompactUpNextCard(")
            .substringBefore("fun HomeScreen(")
        assertFalse(
            "the CW card draws what it drew: the landing is the rail's to do, " +
                "so the card takes no part in it",
            card.contains("scrollToItem") ||
                card.contains("headRequester") ||
                card.contains("viewerWalkedRail")
        )
        assertTrue(
            "and the card still renders with no focus of its own to restore",
            card.contains("onFocusChanged") && card.contains("focused = it.isFocused")
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
    }
}
