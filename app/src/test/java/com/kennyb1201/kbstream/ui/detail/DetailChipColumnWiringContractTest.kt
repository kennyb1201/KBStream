package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the detail page actually decides its vertical chip presses by column.
 *
 * [DetailChipColumnsTest] pins the rule; this pins that the rule is what the
 * rails ask on UP and DOWN, that each rail files its chips under their column
 * for it to find, and that the rails whose restorer memory caused the reported
 * jump are no longer the ones deciding.
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
            source.contains("registerChipColumn( NETWORK_ROW_KEY, networkIndex, chipFocusRequester )")
        )
        assertTrue(
            source.contains("registerChipColumn( PRODUCTION_ROW_KEY, companyIndex, chipFocusRequester )")
        )
        assertTrue(
            "the people cards too - and by column, not by their index in a " +
                "list that separators also occupy",
            source.contains("registerChipColumn( PEOPLE_ROW_KEY, column, cardFocusRequester )")
        )
        assertTrue(
            "the column a card has comes from the people list itself",
            source.contains("val peopleColumns = remember(peopleItems) {")
        )
    }

    @Test
    fun `a press on a network chip moves to the same column, not to a memory`() {
        assertTrue(
            "DOWN must aim at the production chip in this column: the " +
                "production rail's restorer is what sent the press to a chip " +
                "several columns to the right",
            source.contains("focusChipColumn( PRODUCTION_ROW_KEY, networkIndex )")
        )
        assertTrue(
            "UP must aim at the people card in this column",
            source.contains("focusChipColumn( PEOPLE_ROW_KEY, networkIndex )")
        )
        assertTrue(
            "...and when there is no cast and no writer at all, the rail " +
                "above is not composed, so the old guard against landing on " +
                "the episodes row two rows up must still be there",
            source.contains("movieDetailsFocusRequester .requestFocus()")
        )
        // The row's own modifier chain, up to the point its chips are built.
        // An ancestor's key handler runs BEFORE the chip's, so a handler left
        // here would win and put the viewer back on the restorer's chip.
        val rowChain = source
            .substringAfter("item(key = \"networkrow\"")
            .substringBefore("{ networkIndex, n ->")
        assertTrue(
            "the row is still a focus group with a restorer",
            rowChain.contains("focusGroup() .focusRestorer()")
        )
        assertFalse(
            "the ROW must no longer answer the vertical press itself",
            rowChain.contains("onPreviewKeyEvent")
        )
        assertFalse(
            "the row-level UP handler is the thing that was replaced",
            source.contains("UP belongs to PEOPLE")
        )
    }

    @Test
    fun `the rails above and below keep their columns too`() {
        assertTrue(
            "UP from a production chip: the networks on a series, the people " +
                "cards on a movie, which has no network rail",
            source.contains("focusChipColumn( NETWORK_ROW_KEY, companyIndex )") &&
                source.contains("focusChipColumn( PEOPLE_ROW_KEY, companyIndex )")
        )
        assertTrue(
            "DOWN from a people card: the networks on a series, the " +
                "production companies on a movie",
            source.contains("focusChipColumn( NETWORK_ROW_KEY, column )") &&
                source.contains("focusChipColumn( PRODUCTION_ROW_KEY, column )")
        )
    }

    @Test
    fun `only the one helper decides these presses`() {
        // Six call sites, plus the definition: every vertical press between
        // these rails goes through the column rule, so a seventh added later
        // that does not is a change someone has to make deliberately.
        assertEquals(
            7,
            Regex("focusChipColumn\\(").findAll(source).count()
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
