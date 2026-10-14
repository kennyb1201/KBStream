package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the detail page actually decides its vertical chip presses by column,
 * and that the column is handed to the one chain rather than to a per-row
 * handler.
 *
 * [DetailChipColumnsTest] pins the rule; this pins that each rail files its
 * chips under their column for it to find, that every vertical press - UP and
 * DOWN, between every pair of rails - goes through the shared handler, and that
 * the rails whose restorer memory caused the reported jump are no longer the
 * ones deciding.
 *
 * Read from the source: these are key handlers and Compose modifiers, which
 * need a device and a remote to press.
 */
class DetailChipColumnWiringContractTest {

    private val source: String by lazy {
        normalized(
            File(findMainSourceRoot(), DETAIL).readText()
        )
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

    @Test
    fun `each rail files its chips under their column`() {
        assertTrue(
            "the network chips must be reachable by column, or a press from " +
                "above or below cannot aim at the one in the same column",
            source.contains(
                "registerChipColumn( NETWORK_ROW_KEY, networkIndex, chipFocusRequester )"
            )
        )
        assertTrue(
            source.contains(
                "registerChipColumn( PRODUCTION_ROW_KEY, companyIndex, chipFocusRequester )"
            )
        )
        assertTrue(
            "the people cards too - and by column, not by their index in a " +
                "list that separators also occupy",
            source.contains("registerChipColumn( PEOPLE_ROW_KEY, column, cardFocusRequester )")
        )
        assertTrue(
            "the tag rail as well, so the chain can move INTO it from the " +
                "episode row and not only out of it",
            source.contains(
                "registerChipColumn( KEYWORDS_ROW_KEY, keywordIndex, chipFocusRequester )"
            )
        )
        assertTrue(
            "the genre chips in the fixed header too, so a press out of the tag " +
                "rail can be aimed at the chip in the column it came from",
            source.contains(
                "registerChipColumn( GENRE_ROW_KEY, genreIndex, chipFocusRequester )"
            )
        )
        assertTrue(
            "and the review cards, so the rail the chain now names is reachable " +
                "by column from either side",
            source.contains(
                "registerChipColumn( REVIEWS_ROW_KEY, reviewIndex, reviewFocusRequester )"
            )
        )
        assertTrue(
            "the column a card has comes from the people list itself",
            source.contains("val peopleColumns = remember(peopleItems) {")
        )
    }

    @Test
    fun `a press carries its column into the chain, not into a memory`() {
        assertTrue(
            "the network chip hands over its index, which is the column the " +
                "rails above and below are aimed by",
            source.contains(
                "detailRowVerticalFocus( DetailRow.NETWORK, networkIndex, detailRowPresence )"
            )
        )
        assertTrue(
            source.contains(
                "detailRowVerticalFocus( DetailRow.PRODUCTION, companyIndex, detailRowPresence )"
            )
        )
        assertTrue(
            source.contains(
                "detailRowVerticalFocus( DetailRow.PEOPLE, column ?: 0, detailRowPresence )"
            )
        )
        assertTrue(
            "and the column rule itself is still what decides the chip: the " +
                "production rail's restorer is what sent the press to a chip " +
                "several columns to the right",
            source.contains("fun focusChipColumn(rowKey: String, fromColumn: Int): Boolean")
        )
    }

    @Test
    fun `the rails no longer answer the vertical press themselves`() {
        val rowChain = source
            .substringAfter("item(key = \"networkrow\"")
            .substringBefore("{ networkIndex, n ->")
        assertTrue(
            "the row is still a focus group with a restorer",
            rowChain.contains("focusGroup() .focusRestorer()")
        )
        assertFalse(
            "no bespoke UP/DOWN block is left on the row: the shared handler " +
                "is the only thing it answers with",
            rowChain.contains("onPreviewKeyEvent {")
        )
        assertFalse(
            "the row-level UP handler is the thing that was replaced",
            source.contains("UP belongs to PEOPLE")
        )
        assertFalse(
            "nor may a rail still ask for a specific neighbour by name - the " +
                "chain decides that now",
            source.contains("focusChipColumn( PRODUCTION_ROW_KEY, networkIndex )") ||
                source.contains("focusChipColumn( NETWORK_ROW_KEY, companyIndex )") ||
                source.contains("focusChipColumn( PEOPLE_ROW_KEY, companyIndex )") ||
                source.contains("focusChipColumn( PEOPLE_ROW_KEY, networkIndex )")
        )
    }

    @Test
    fun `a rail the scroll disposed is revealed, never skipped and never stranded`() {
        assertTrue(
            "a rail that exists but was disposed is put back on screen and then " +
                "handed the column, anchored on the row the press came from",
            source.contains(
                "revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
            )
        )
        assertTrue(
            "a rail that exists and still cannot answer hands the key to the " +
                "NEXT row that way rather than to the top of the page - a " +
                "teleport from the bottom of a long page is a lost place",
            source.contains("else -> {") &&
                source.contains("val nextTarget = DetailFocusChain.nextRow(target, direction, presence)") &&
                source.contains("focusRow(nextTarget, fromColumn, target, direction, presence)")
        )
        assertFalse(
            "and the walk's end consumes the key where the viewer is instead " +
                "of jumping to the header",
            source.contains("else -> focusTopOfPage()")
        )
    }

    @Test
    fun `every vertical press between the rails goes through the one rule`() {
        // The definition, the chip lookup in the chain's focusRow, the reveal
        // helper's own single call, the present-but-unready retry the chain's
        // focusRow spends before it steps over a row that is there, and the
        // Media row's two asks for its OWN
        // card - its tab strip's DOWN, which is not a neighbour decision at
        // all: the rail it enters is the row it is already standing in, which
        // is why it asks directly instead of scrolling the row it is in to the
        // top (see focusFirstMediaCard). The second of those two asks is the
        // fallback to column 0 for the case the first cannot cover - the
        // remembered column's slot being the one card the scroll has not
        // composed yet - and not a second rail deciding its own neighbour, so
        // the count below is the whole of it: any further site is a change
        // someone has to make deliberately.
        assertEquals(
            6,
            Regex("focusChipColumn\\(").findAll(source).count()
        )
        assertTrue(
            "the Media row enters its own rail in the column the section " +
                "remembers, then falls back to the first card",
            source.contains("suspend fun focusFirstMediaCard() {") &&
                source.contains("if (focusChipColumn(MEDIA_ROW_KEY, lastMediaCardColumn)) return") &&
                source.contains("focusChipColumn(MEDIA_ROW_KEY, 0)")
        )
        assertTrue(
            source.contains("suspend fun revealAndFocusChipColumn(") &&
                source.contains("suspend fun focusTopOfPage(): Boolean")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
