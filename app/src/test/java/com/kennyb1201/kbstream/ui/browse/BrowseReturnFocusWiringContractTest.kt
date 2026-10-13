package com.kennyb1201.kbstream.ui.browse

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the browse pages actually hand their place back to themselves.
 *
 * [BrowseReturnFocusTest] pins the record; this pins that every browse page
 * raises one on the way into a title and reads it back on the way out, that the
 * pages that are a list of rails put the rail back BEFORE they ask for the card
 * (a card in a rail the scroll disposed has no focus node, and a request
 * against one is a silent miss - which is what left the page reopening at the
 * top), and that the key each page keys itself with keeps two pages apart.
 *
 * Read from the source: this is Compose wiring and D-pad focus, which need a
 * device and a remote to press.
 */
class BrowseReturnFocusWiringContractTest {

    /** Every browse page, with the key builder its screen key comes from. */
    private val screens = listOf(
        BrowseScreen(
            label = "the genre/keyword page",
            dir = "tag",
            file = "TagScreen.kt",
            keyBuilder = "tagBrowseScreenKey(",
            cardKey = "itemId = cardKey",
            recordMarker = "onBeforeOpenDetail(cardKey, itemIndex)",
            navigate = "viewModel.resolveAndNavigate("
        ),
        BrowseScreen(
            label = "the studio page",
            dir = "studio",
            file = "StudioScreen.kt",
            keyBuilder = "studioBrowseScreenKey(",
            cardKey = "itemId = cardKey",
            recordMarker = "onBeforeOpenDetail(cardKey, itemIndex)",
            navigate = "viewModel.resolveAndNavigate("
        ),
        BrowseScreen(
            label = "the collection page",
            dir = "collection",
            file = "CollectionScreen.kt",
            keyBuilder = "collectionBrowseScreenKey(",
            cardKey = "itemId = \"collection_part:\${part.id}\"",
            recordMarker = "BrowseReturnFocus.record(",
            navigate = "onNavigateDetail("
        ),
        BrowseScreen(
            label = "the decade page",
            dir = "decade",
            file = "DecadeScreen.kt",
            keyBuilder = "decadeBrowseScreenKey(",
            cardKey = "itemId = cardKey",
            recordMarker = "onBeforeOpenDetail(cardKey, itemIndex)",
            navigate = "viewModel.resolveAndNavigate("
        )
    )

    /** The mechanism itself, comments removed, then whitespace collapsed. */
    private val mechanism: String by lazy {
        normalized(stripComments(read("browse", "BrowseReturnFocus.kt")))
    }

    private fun screen(browseScreen: BrowseScreen): String =
        normalized(stripComments(read(browseScreen.dir, browseScreen.file)))

    @Test
    fun `every browse page records the card before it opens the detail page`() {
        screens.forEach { browseScreen ->
            val source = screen(browseScreen)
            val record = source.indexOf(browseScreen.recordMarker)
            assertTrue(
                "${browseScreen.label} has to raise the record on the way in",
                record >= 0
            )
            // Both calls are in the SAME click handler, which is a handful of
            // lines in every one of these files, so a window that reaches just
            // past the record is the handler - and a window is what keeps this
            // from reading a navigation call somewhere else in the file.
            val handler = source.substring(record, minOf(source.length, record + 400))
            assertTrue(
                "${browseScreen.label} records which card was pressed BEFORE it " +
                    "navigates - a record raised after the click would never be " +
                    "written, the screen is being left",
                handler.contains(browseScreen.navigate)
            )
            assertTrue(
                "${browseScreen.label}'s record names this page by its key, its " +
                    "card by the key the card's own rail keys it with, and where " +
                    "that card was",
                source.contains("BrowseReturnFocus.record(") &&
                    source.contains("screenKey = browseReturnKey") &&
                    source.contains(browseScreen.cardKey) &&
                    source.contains("position = index")
            )
            assertTrue(
                "${browseScreen.label} reads the record back the next time it " +
                    "composes",
                source.contains("BrowseReturnFocus.consume(browseReturnKey)")
            )
            assertTrue(
                "${browseScreen.label}'s key comes from the one place the keys " +
                    "are spelled out, off the route it was opened with",
                source.contains(browseScreen.keyBuilder)
            )
        }
    }

    @Test
    fun `the record is checked before the page's own first card is focused`() {
        listOf("tag" to "TagScreen.kt", "studio" to "StudioScreen.kt", "decade" to "DecadeScreen.kt")
            .forEach { (dir, file) ->
                val source = normalized(stripComments(read(dir, file)))
                val effect = source
                    .substringAfter("LaunchedEffect(sections, isLoading) {")
                    .substringBefore("Box(")
                val read = effect.indexOf(
                    "val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)"
                )
                val restore = effect.indexOf("focusPlaced = restoreBrowseReturn(recorded)")
                val first = effect.indexOf("focusFirstCard()")
                assertTrue(
                    "$file reads the record first, then the page's own first card - " +
                        "the other way round the landing would be stolen by it",
                    read in 0 until restore && restore < first
                )
                assertTrue(
                    "and the landing is what marks the page focused, so a return " +
                        "that has not landed yet is retried when the next rail arrives",
                    effect.contains("returnTarget = recorded") &&
                        effect.contains("returnAttempts++") &&
                        effect.contains("recorded != null && returnAttempts < BROWSE_RETURN_FOCUS_STEPS")
                )
                assertTrue(
                    "a new load drops a record left by the list before it - a " +
                        "position in the old list means nothing in the new one",
                    effect.contains("returnTarget = null") &&
                        effect.contains("returnAttempts = 0")
                )
            }
    }

    @Test
    fun `the record is held until the page can honour it`() {
        listOf("tag" to "TagScreen.kt", "studio" to "StudioScreen.kt", "decade" to "DecadeScreen.kt")
            .forEach { (dir, file) ->
                val source = normalized(stripComments(read(dir, file)))
                assertTrue(
                    "$file asks the rail whether it is there at all, and answers " +
                        "false rather than consuming the place it cannot put back",
                    source.contains("if (sections.none { it.title == railKey }) return false")
                )
            }
        val source = normalized(stripComments(read("collection", "CollectionScreen.kt")))
        assertTrue(
            "the collection page holds its record before it checks the films: a " +
                "record read and then dropped because they are not there yet is " +
                "the place lost",
            source.indexOf("if (returnTarget == null) returnTarget = recorded") in
                0 until source.indexOf("if (parts.isEmpty()) return@LaunchedEffect")
        )
    }

    @Test
    fun `the page puts the rail back before it asks for the card`() {
        listOf("tag" to "TagScreen.kt", "studio" to "StudioScreen.kt", "decade" to "DecadeScreen.kt")
            .forEach { (dir, file) ->
                val source = normalized(stripComments(read(dir, file)))
                assertTrue(
                    "$file is a list of rails: landing on position 20 means the " +
                        "rail that holds it has to be on screen first",
                    source.contains(
                        "browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)"
                    )
                )
                val restore = source
                    .substringAfter("suspend fun restoreBrowseReturn(")
                    .substringBefore("LaunchedEffect(")
                val revealed = restore.indexOf("revealBrowseReturnRail(")
                val asked = restore.indexOf("returnFocusRequester.requestFocus()")
                assertTrue(
                    "$file asks for the card only once the rail is back on screen",
                    revealed in 0 until asked
                )
            }
        val reveal = mechanism
            .substringAfter("suspend fun LazyListState.revealBrowseReturnRail(")
        val scroll = reveal.indexOf("scrollToItem(index)")
        val composed = reveal.indexOf("visibleItemsInfo.any { it.key == railKey }")
        val giveUp = reveal.indexOf("return false")
        assertTrue("the rail is scrolled back in", scroll >= 0)
        assertTrue(
            "and has to be back among the COMPOSED items before the page asks " +
                "for a card in it: a request against a card that is not composed " +
                "fails silently, and that silent failure is the stranding",
            composed in (scroll + 1) until giveUp
        )
        assertTrue(
            "the walk around the recorded index covers the rails that moved while " +
                "the viewer was away",
            reveal.contains("for (delta in 1..") && reveal.contains("hint - delta")
        )
    }

    @Test
    fun `every browse page's rail comes back to the recorded position`() {
        listOf("tag" to "TagScreen.kt", "studio" to "StudioScreen.kt", "decade" to "DecadeScreen.kt")
            .forEach { (dir, file) ->
                val source = normalized(stripComments(read(dir, file)))
                assertTrue(
                    "$file finds the recorded card by the KEY its own rail keys it " +
                        "with, and keeps the recorded position only as the fallback " +
                        "for a card the record cannot name",
                    source.contains(".indexOfFirst {") &&
                        source.contains("== target.itemId }") &&
                        source.contains("?: target.position.takeIf { it in section.items.indices }")
                )
                assertTrue(
                    "$file scrolls to the MATCHED index - not to the recorded " +
                        "position, which the card may have moved off. Testing the " +
                        "position as well as the key meant that once a rail " +
                        "re-ordered NO item matched, the requester attached to " +
                        "nothing, its retry missed the budget, and the page fell " +
                        "through to the broken first-card path",
                    source.contains("LaunchedEffect(returnIndex) {") &&
                        source.contains("rowState.scrollToItem(index)") &&
                        !source.contains("rowState.scrollToItem(target.position)")
                )
                assertTrue(
                    "$file marks the card at that one resolved index, so the focus " +
                        "requester is attached to exactly one card",
                    source.contains("val isReturnItem = itemIndex == returnIndex")
                )
                assertTrue(
                    "$file hands the focus back through that card's own requester",
                    source.contains("Modifier.focusRequester(returnFocusRequester)")
                )
                assertTrue(
                    "$file passes the record down to exactly the rail it names, so " +
                        "a rail the record is not about never takes the focus",
                    source.contains(
                        "returnTarget = returnTarget?.takeIf { it.railKey == section.title }"
                    )
                )
            }
    }

    @Test
    fun `the collection page puts its single rail back`() {
        val source = normalized(stripComments(read("collection", "CollectionScreen.kt")))
        assertTrue(
            "a collection is one rail that is already on screen: the record it " +
                "raises carries no rail to reveal",
            source.contains("collectionBrowseScreenKey(collectionId)") &&
                !source.contains("railKey =")
        )
        assertTrue(
            "it still scrolls that rail to the film that was pressed and focuses it",
            source.contains("rowState.scrollToItem(index)") &&
                source.contains("Modifier.focusRequester( returnFocusRequester )")
        )
        assertTrue(
            "and the film is found by the key the rail keys it with, with the " +
                "recorded position as the fallback",
            source.contains("\"collection_part:\${it.id}\" == recorded.itemId")
        )
    }

    @Test
    fun `a landing is read from the request's own answer, not from whether the call threw`() {
        // Compose returns false from an unattached requester rather than
        // throwing, so `runCatching { ... }.isSuccess` is true for every call
        // ever made and the fall-through it guards is unreachable.
        screens.forEach { browseScreen ->
            // The pages' own doc comments name this trap deliberately (it is why
            // the landing reads the boolean), so the check is made against their
            // CODE: the block comments' `*` lines are dropped before the search.
            val code = read(browseScreen.dir, browseScreen.file)
                .lineSequence()
                .filterNot { it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertFalse(
                "${browseScreen.label} must not read the landing off a swallowed call",
                code.contains("isSuccess")
            )
        }
        assertTrue(
            "the landing is the answer requestFocus gives",
            mechanism.contains("BROWSE_RETURN_FOCUS_STEPS") &&
                mechanism.contains("BROWSE_RETURN_FOCUS_DELAY_MS")
        )
    }

    @Test
    fun `the browse record is its own record, not the detail page's`() {
        assertTrue(
            "a browse page and a detail page can both be waiting for a Back at " +
                "the same time, and a shared slot would let one consume the " +
                "other's place",
            mechanism.contains("object BrowseReturnFocus {") &&
                !mechanism.contains("DetailReturnFocus.") &&
                !mechanism.contains("object DetailReturnFocus")
        )
        assertTrue(
            "one record, read once, and aged out if the Back it was waiting for " +
                "never comes",
            mechanism.contains("private var pending: BrowseReturnTarget? = null") &&
                mechanism.contains("VALID_FOR_MS = 30 * 60 * 1000L") &&
                mechanism.contains("pending = null") &&
                mechanism.contains("fun record(target: BrowseReturnTarget)")
        )
        assertTrue(
            "a record for another page is left where it is: a browse page can " +
                "open another one on the way, and that page must not swallow it",
            mechanism.contains("if (recorded.screenKey != screenKey) return null")
        )
    }

    @Test
    fun `the place a browse page keeps is the card, its rail and where both stood`() {
        assertTrue(
            mechanism.contains("val screenKey: String") &&
                mechanism.contains("val itemId: String") &&
                mechanism.contains("val position: Int") &&
                mechanism.contains("val railKey: String? = null") &&
                mechanism.contains("val railIndex: Int = 0")
        )
    }

    private fun read(dir: String, fileName: String): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/$dir/$fileName"
        ).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private data class BrowseScreen(
        val label: String,
        val dir: String,
        val file: String,
        val keyBuilder: String,
        /** How the record in this screen names the pressed card. */
        val cardKey: String,
        val recordMarker: String,
        val navigate: String
    )
}
