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
            "while the rail itself scrolls its row to the recorded card",
            source.contains("runCatching { rowState.scrollToItem(target.position) }")
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
            "only the card the record names carries the return requester",
            source.contains(
                "val isReturnItem = returnTarget != null && " +
                    "index == returnTarget.position && cardKey == returnTarget.itemId"
            ) && source.contains(
                "extraFocusRequester = returnFocusRequester.takeIf { isReturnItem }"
            )
        )
    }

    @Test
    fun `a fresh open lands focus on the first card, not nothing`() {
        assertTrue(
            "the page waits for the credits to land, then places focus",
            source.contains("LaunchedEffect(movies, tvShows)") &&
                source.contains("focusFirstCard()")
        )
        assertTrue(
            "the landing is retried, because the rail may not have composed its " +
                "first card yet - the same race as a return",
            source.contains("landed = runCatching { firstCreditFocusRequester.requestFocus() } .getOrDefault(false)")
        )
        assertTrue(
            "the first card of the first rail the page draws takes that requester",
            source.contains("val isFirstItem = isFirstRail && index == 0") &&
                source.contains("firstCreditFocusRequester.takeIf { isFirstItem }")
        )
        assertTrue(
            "an entry focus that could not land is retried when the next rail " +
                "arrives, while a placed one is left alone",
            source.contains("var focusPlaced by remember { mutableStateOf(false) }") &&
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
