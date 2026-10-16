package com.kennyb1201.kbstream.ui.actor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Actor page hands its place back to itself - and takes focus on a fresh
 * open.
 *
 * Two reported bugs, one mechanism. First, the page recorded nothing on the way
 * into a title, so backing out of Detail rebuilt both filmography rails at
 * scroll 0 with the viewer's place gone (see [BrowseReturnFocus], already used
 * by the Collection/Tag/Studio/Decade pages). Second, nothing requested focus on
 * entry, so the D-pad was dead on a fresh Actor page until the viewer happened
 * to do something that grabbed focus; the only requests were the long-press
 * menu's dismiss restores.
 *
 * This page then kept the FIRST shape of that mechanism while the browse pages
 * moved on: it spent a landing try every time its credits arrived, which is what
 * the Tag page's own field report pinned as the rail that builds slowly losing
 * its place to the rail that built first. The rule is now the shared one -
 * waiting for the recorded rail is free and bounded by time, a try is spent only
 * where a landing was really attempted, and the card is resolved by the key its
 * rail keys it with (see [browseReturnDecision]).
 *
 * Read from the source: this is Compose wiring and D-pad focus, which need a
 * device and a remote to press.
 */
class ActorReturnFocusContractTest {

    private val source: String by lazy { normalized(stripComments(read())) }

    @Test
    fun `the page keys itself by the actor it was opened for`() {
        assertTrue(
            "two actors' filmographies must not read each other's record",
            source.contains("private fun actorBrowseScreenKey(actorId: Int): String = \"actor:\$actorId\"") &&
                source.contains("val browseReturnKey = remember(actorId) { actorBrowseScreenKey(actorId) }")
        )
    }

    @Test
    fun `the page's per-actor return state is keyed by the actor`() {
        listOf(
            "var returnTarget by remember(actorId) {",
            "var returnAttempts by remember(actorId) {",
            "var returnWaitStartMs by remember(actorId) {",
            "var focusPlaced by remember(actorId) {"
        ).forEach { declaration ->
            assertTrue(
                "one composable serves every actor, so an unkeyed $declaration " +
                    "outlives the actor it was recorded for: the next actor's page " +
                    "inherits the previous one's return record AND its spent " +
                    "landing budget, and spends what is left of it looking for a " +
                    "card that is not on this page",
                source.contains(declaration)
            )
        }
    }

    @Test
    fun `a credit press records the card before it resolves`() {
        val record = source.indexOf("BrowseReturnFocus.record(target)")
        assertTrue("the rail has to raise the record on the way into a title", record >= 0)
        val navigate = source.indexOf("viewModel.resolveAndNavigate(", record)
        assertTrue(
            "and BEFORE the navigation - a record written after the click would " +
                "never be written, the screen is being left",
            navigate > record
        )
        assertTrue(
            "the record names the card's rail and index too, because a page of " +
                "rails has to reveal the rail before it can focus a card in it",
            source.contains(
                "val target = BrowseReturnTarget( " +
                    "screenKey = browseReturnKey, itemId = cardKey, " +
                    "position = index, railKey = railKey, railIndex = railIndex )"
            )
        )
    }

    @Test
    fun `the menu's Go to Details records the same place a card press does`() {
        assertTrue(
            "the open menu holds the place its title was pressed from",
            source.contains("menuReturnTarget?.let { BrowseReturnFocus.record(it) }")
        )
        val record = source.indexOf("menuReturnTarget?.let { BrowseReturnFocus.record(it) }")
        val clear = source.indexOf("menuCredit = null", record)
        val navigate = source.indexOf("viewModel.resolveAndNavigate(", record)
        assertTrue(
            "and it is recorded before the menu is torn down and navigates",
            clear > record && navigate > record
        )
    }

    @Test
    fun `the record is read back and the rail holding the card is revealed first`() {
        assertTrue(
            "the page reads the record on entry",
            source.contains("val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)")
        )
        assertTrue(
            "it is a page of rails: landing on position 20 means the rail that " +
                "holds it has to be on screen first",
            source.contains("browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)")
        )
        assertTrue(
            "and the outer list draws on the screen's state, so it can be scrolled",
            source.contains("state = browseListState,")
        )
        assertTrue(
            "while the rail itself resolves the recorded card by the KEY its own " +
                "rail keys it with, keeps the recorded position only as the " +
                "fallback for a card the record cannot name, and scrolls to that " +
                "resolved index",
            source.contains(
                "val returnIndex = returnTarget?.let { target -> " +
                    "credits.indexOfFirst { it.id.toString() == target.itemId } " +
                    ".takeIf { it >= 0 } " +
                    "?: target.position.takeIf { it in credits.indices } }"
            ) &&
                source.contains("LaunchedEffect(returnIndex)") &&
                source.contains("runCatching { rowState.scrollToItem(index) }") &&
                !source.contains("rowState.scrollToItem(target.position)")
        )
    }

    @Test
    fun `the landing is retried, and read from the request's own answer`() {
        assertTrue(
            "a card that is not composed yet has no focus node, so the landing " +
                "is attempted inside a budget",
            source.contains("BROWSE_RETURN_FOCUS_STEPS") &&
                source.contains("BROWSE_RETURN_FOCUS_DELAY_MS") &&
                source.contains("landed = runCatching { returnFocusRequester.requestFocus() } .getOrDefault(false)")
        )
        assertFalse(
            "and it is the answer requestFocus gives, never a swallowed call",
            source.contains("isSuccess")
        )
        assertTrue(
            "and the requester rides exactly the card the resolved index names, " +
                "so a rail that has re-ordered under the viewer hands focus to " +
                "the card that is still there - testing the position as well as " +
                "the key meant that once it moved NOTHING matched, the requester " +
                "attached to nothing and the page fell through to its first card",
            source.contains("val isReturnItem = index == returnIndex") &&
                source.contains("extraFocusRequester = returnFocusRequester.takeIf { isReturnItem }")
        )
    }

    @Test
    fun `waiting for the recorded rail is free, so the other rail cannot exhaust the budget`() {
        assertTrue(
            "which rail may be waited for is the shared rule's call, not this " +
                "page's",
            source.contains("browseReturnDecision(") &&
                source.contains("arrivedRailKeys = arrivedRailKeys,") &&
                source.contains("attemptsSpent = returnAttempts,") &&
                source.contains("waitStartMs = returnWaitStartMs,")
        )
        assertTrue(
            "and the two rails this page paints ARE that arrived key space, so a " +
                "record for the filmography rail whose credits are still on the " +
                "way is waited for instead of falling through to the first TV card",
            source.contains(
                "val arrivedRailKeys = buildList { " +
                    "if (movies.isNotEmpty()) add(ACTOR_MOVIES_RAIL) " +
                    "if (tvShows.isNotEmpty()) add(ACTOR_TV_RAIL) }.toSet()"
            ) && source.contains("if (railKey !in arrivedRailKeys) return false")
        )
        assertTrue(
            "and the record is HELD through that wait - a record read and then " +
                "dropped because its rail is not on screen yet is the place lost",
            source.contains("if (recorded != null) returnTarget = recorded")
        )
        assertFalse(
            "the wait is not a landing try either: a page that has already waited " +
                "holds the record and spends nothing, rather than charging itself " +
                "an attempt for an arrival it did not control",
            source.contains("BrowseReturnDecision.WAIT_FOR_RAIL -> returnAttempts++")
        )
        val hold = source.indexOf("BrowseReturnDecision.WAIT_FOR_RAIL")
        val landing = source.indexOf("BrowseReturnDecision.TRY_LANDING")
        val spent = source.indexOf("returnAttempts++")
        assertTrue(
            "the one landing try is spent in the landing branch, reached only " +
                "with an arrived rail - spending it where the record was read is " +
                "what ran this page's budget out on the rail that built first",
            hold in 0 until landing && spent > landing
        )
        assertTrue(
            "and a new load drops a record left by the list before it: a " +
                "position in the old list means nothing in the new one",
            source.contains("var returnWaitStartMs by remember(actorId) { mutableStateOf(0L) }") &&
                source.contains("if (isLoading) {") &&
                source.contains("returnTarget = null") &&
                source.contains("returnAttempts = 0") &&
                source.contains("returnWaitStartMs = 0L")
        )
    }

    @Test
    fun `a fresh open lands focus on the first card, not nothing`() {
        assertTrue(
            "the page waits for the credits to land, then places focus",
            source.contains("LaunchedEffect(movies, tvShows, isLoading)") &&
                source.contains("focusFirstCard()")
        )
        assertTrue(
            "the landing is retried, because the rail may not have composed its " +
                "first card yet - the same race as a return",
            source.contains("landed = runCatching { firstCreditFocusRequester.requestFocus() } .getOrDefault(false)")
        )
        assertTrue(
            "and the entry focus is marked placed off the landing's OWN answer, " +
                "so a miss is retried when the next rail arrives rather than " +
                "leaving the page with nothing focused",
            source.contains("focusPlaced = focusFirstCard()") &&
                source.contains("suspend fun focusFirstCard(): Boolean")
        )
        assertTrue(
            "the first card of the first rail the page draws takes that requester",
            source.contains("val isFirstItem = isFirstRail && index == 0") &&
                source.contains("firstCreditFocusRequester.takeIf { isFirstItem }")
        )
        assertTrue(
            "an entry focus that could not land is retried when the next rail " +
                "arrives, while a placed one is left alone",
            source.contains("var focusPlaced by remember(actorId) { mutableStateOf(false) }") &&
                source.contains("if (!focusPlaced && (movies.isNotEmpty() || tvShows.isNotEmpty()))")
        )
    }

    private fun read(): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt"
        ).readText()

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
}
