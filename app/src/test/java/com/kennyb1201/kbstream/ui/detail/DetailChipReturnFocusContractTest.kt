package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Back from a chip's drill-down lands.
 *
 * Reported problem: pressing an actor, network, production, genre or keyword
 * chip and then backing out put the focus on the PLAY button instead of on the
 * chip that had been pressed. The detail page is rebuilt from scratch on the
 * way back - its focus and the restorer that remembered the pressed chip are
 * gone with the composition - so a chip has to write a [DetailReturnTarget]
 * on the way out (see [DetailReturnFocus]) before it navigates, and the rail
 * that target names has to be able to put the chip back: a `LazyRow` given no
 * `state` composes only what is on screen, so a chip the viewer had scrolled
 * past has no requester to take the focus and the restore has nothing to aim
 * at. Both halves are pinned here, keyed by the rail each press belongs to.
 *
 * Read from the source: these are Compose modifiers and click handlers, which
 * need a device and a remote to press.
 */
class DetailChipReturnFocusContractTest {

    /** The detail source, comments removed, then whitespace collapsed. */
    private val source: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /**
     * A chip that records where it came from, in the same press as the
     * navigation it opens: the record has to be written BEFORE the screen
     * changes, or the reopen has nothing to consume.
     */
    private fun recordsBeforeNavigating(record: String, navigation: String) {
        assertTrue(
            "the press must record its rail, chip and position before it navigates: $record",
            source.contains(record + " " + navigation)
        )
    }

    /** The rail a record names is the rail its `LazyRow` is given the state of. */
    private fun railIsGivenItsState(row: String, state: String) {
        assertTrue(
            "the rail holding the chip must compose with the state its restore scrolls: " +
                "$row needs state = $state",
            source.contains(row + " LazyRow( state = $state,")
        )
    }

    @Test
    fun `every chip records its rail before it opens the drill-down`() {
        recordsBeforeNavigating(
            "rememberReturnTarget( GENRE_ROW_KEY, chipKey, genreIndex )",
            "onNavigateTag("
        )
        recordsBeforeNavigating(
            "rememberReturnTarget( KEYWORDS_ROW_KEY, chipKey, keywordIndex )",
            "onNavigateTag("
        )
        recordsBeforeNavigating(
            "rememberReturnTarget( PEOPLE_ROW_KEY, cardKey, personIndex )",
            "onNavigateActor("
        )
        recordsBeforeNavigating(
            "rememberReturnTarget( NETWORK_ROW_KEY, chipKey, networkIndex )",
            "onNavigateStudio("
        )
        recordsBeforeNavigating(
            "rememberReturnTarget( PRODUCTION_ROW_KEY, chipKey, companyIndex )",
            "onNavigateStudio("
        )
    }

    @Test
    fun `every rail a chip belongs to is given the state its restore scrolls`() {
        railIsGivenItsState("if (tmdbGenres.isNotEmpty()) {", "genreRowState")
        railIsGivenItsState(
            "item(key = \"keywordsrow\", contentType = \"section-row\") {",
            "keywordsRowState"
        )
        railIsGivenItsState(
            "item(key = \"peoplerow\", contentType = \"section-row\") {",
            "peopleRowState"
        )
        railIsGivenItsState(
            "item(key = \"networkrow\", contentType = \"section-row\") {",
            "networkRowState"
        )
        railIsGivenItsState(
            "item(key = \"productionrow\", contentType = \"section-row\") {",
            "productionRowState"
        )
        railIsGivenItsState(
            "item(key = \"collectionrow\", contentType = \"section-row\") {",
            "collectionRowState"
        )
        railIsGivenItsState(
            "item(key = \"recsrow\", contentType = \"section-row\") {",
            "recsRowState"
        )
    }

    @Test
    fun `the return resolves every chip rail it can be raised for`() {
        listOf(
            "GENRE_ROW_KEY -> genreRowState",
            "KEYWORDS_ROW_KEY -> keywordsRowState",
            "PEOPLE_ROW_KEY -> peopleRowState",
            "NETWORK_ROW_KEY -> networkRowState",
            "PRODUCTION_ROW_KEY -> productionRowState"
        ).forEach { mapping ->
            assertTrue(
                "a record raised for a chip rail must resolve to that rail's state, " +
                    "or the reopen returns early and the focus never comes back: $mapping",
                source.contains(mapping)
            )
        }
    }

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

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
