package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library hands its place back to itself across a Detail page.
 *
 * Reported: open the Library, scroll to item 20, open it, press Back - and the
 * grid was at the top with the first filter chip focused. The screen forced
 * focus onto that chip on every entry (`LaunchedEffect(Unit)`) and re-created
 * its grid, so the viewer's place was lost. There is now a record: the poster
 * the page was opened from, its grid key and its position, read back on the way
 * out and landed with a verified retry (see [BrowseReturnFocus], the same
 * mechanism the Collection/Tag/Studio/Decade pages use).
 *
 * Read from the source: this is Compose wiring and D-pad focus, which need a
 * device and a remote to press.
 */
class LibraryReturnFocusContractTest {

    private val source: String by lazy {
        normalized(stripComments(read()))
    }

    @Test
    fun `the card press records the poster before it opens the detail page`() {
        val record = source.indexOf("BrowseReturnFocus.record(")
        assertTrue(
            "the page has to raise the record on the way into a title",
            record >= 0
        )
        assertTrue(
            "the record names this page by its key, the poster by the key the " +
                "grid keys it with, and where that poster was",
            source.contains(
                "BrowseReturnFocus.record( BrowseReturnTarget( " +
                    "screenKey = LIBRARY_BROWSE_SCREEN_KEY, " +
                    "itemId = cardKey, position = index ) )"
            )
        )
        val navigate = source.indexOf("onItemClick(item.mediaType, navigationId)", record)
        assertTrue(
            "and it is raised BEFORE the navigation - a record written after " +
                "the click would never be written, the screen is being left",
            navigate > record
        )
    }

    @Test
    fun `the record is read back on entry, before the selected chip is landed on`() {
        // One string, in order: the record is read, and the chip is asked for
        // only on the branch where there was no record. The other way round
        // nothing would steal the chip's landing, but a chip landed first would
        // steal the return's.
        assertTrue(
            "the page reads the record first and lands on the chip only on an " +
                "ordinary open",
            source.contains(
                "val recorded = BrowseReturnFocus.consume(LIBRARY_BROWSE_SCREEN_KEY) " +
                    "if (recorded != null) { returnTarget = recorded } else { " +
                    "landOnSelectedChip() }"
            )
        )
    }

    @Test
    fun `the entry landing is driven, not a single unobserved ask`() {
        // The report this sweep closed: the chip strip is a LazyRow and the
        // selected filter is persisted, so re-entering on WATCHLIST or LISTS
        // could ask a chip outside the row's composed window - one ask, answer
        // discarded, no retry, nothing else on the page re-asking.
        assertTrue(
            "the selected chip is scrolled into composed view before it is asked for",
            source.contains("val index = LibraryFilter.entries.indexOf(state.filter)") &&
                source.contains("runCatching { stripState.scrollToItem(index) }")
        )
        assertTrue(
            "and the ask itself is the driven landing, retried across frames",
            source.contains("landFocusOn(topFocusRequester)")
        )
        assertFalse(
            "the bare one-shot ask is what it replaced",
            source.contains("topFocusRequester.requestFocus()")
        )
    }

    @Test
    fun `the grid state and the record are owned by the screen, not the grid`() {
        assertTrue(
            "a Back out of Detail disposes the grid, so the state it scrolls " +
                "on return cannot live below the screen",
            source.contains("val gridState = remember(state.filter) { LazyGridState() }")
        )
        assertTrue(
            "and the grid draws on that state",
            source.contains("state = gridState,")
        )
        assertTrue(
            "the grid is keyed by the same key the record names the card with, " +
                "so a re-ordered grid returns on the poster still there",
            source.contains("key = { _, item -> LocalLibraryStore.dedupeKey(item) }") &&
                source.contains("val cardKey = LocalLibraryStore.dedupeKey(item)")
        )
    }

    @Test
    fun `the return scrolls the grid to the poster and lands focus with a retry`() {
        assertTrue(
            "the grid is scrolled to the recorded position, which is what puts " +
                "the card in the composition so it can be focused at all",
            source.contains("gridState.scrollToItem(index)")
        )
        assertTrue(
            "and the landing is retried, because a card that is not composed " +
                "yet has no focus node",
            source.contains("BROWSE_RETURN_FOCUS_STEPS") &&
                source.contains("BROWSE_RETURN_FOCUS_DELAY_MS") &&
                source.contains("landed = runCatching { returnFocusRequester.requestFocus() } .getOrDefault(false)")
        )
        assertTrue(
            "the record is found by the key the grid keys it with, with the " +
                "recorded position as the fallback",
            source.contains(
                "val index = items.indexOfFirst { LocalLibraryStore.dedupeKey(it) == target.itemId }" +
                    ".takeIf { it >= 0 } ?: target.position.takeIf { it in items.indices }"
            )
        )
    }

    @Test
    fun `only the card the record names carries the return requester`() {
        assertTrue(
            "exactly one card is the return item",
            source.contains(
                "val isReturnItem = returnTarget != null && " +
                    "index == returnTarget.position && cardKey == returnTarget.itemId"
            )
        )
        assertTrue(
            "and it is handed the focus through the screen's requester",
            source.contains("Modifier.focusRequester(returnFocusRequester)")
        )
    }

    @Test
    fun `a landing is read from the request's own answer, not whether the call threw`() {
        // Compose returns false from an unattached requester rather than
        // throwing, so a `.isSuccess` read is true for every call ever made.
        assertFalse(
            "the landing must not be read off a swallowed call",
            source.contains("isSuccess")
        )
    }

    private fun read(): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
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
