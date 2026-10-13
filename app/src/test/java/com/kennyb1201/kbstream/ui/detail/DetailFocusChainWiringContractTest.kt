package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the detail page actually decides its vertical presses through the one
 * chain.
 *
 * [DetailFocusChainTest] pins the rule; this pins that the rule is what the
 * rails ask on UP and DOWN, that every rail asks it the same way (no row left
 * carrying its own logic - that is the bug the chain exists to end), that a
 * rail the scroll disposed is still revealed rather than skipped, and that the
 * row order lives in exactly one place.
 *
 * Read from the source: these are key handlers and Compose modifiers, which
 * need a device and a remote to press.
 */
class DetailFocusChainWiringContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The chain itself, comments removed, then whitespace collapsed. */
    private val chain: String by lazy { normalized(stripComments(read(CHAIN_FILE))) }

    @Test
    fun `every rail answers its vertical presses through the one handler`() {
        assertEquals(
            "seven rails carry a vertical handler - the tag rail, the episode " +
                "row, the people cards, the network chips, the production " +
                "chips, the collection posters and the More Like This posters - " +
                "and all seven go through the same one, so no row can disagree " +
                "with another about what is above or below it",
            7,
            Regex("onPreviewKeyEvent\\( detailRowVerticalFocus\\(").findAll(detail).count()
        )
    }

    @Test
    fun `each rail names itself and keeps its column`() {
        assertTrue(
            detail.contains(
                "detailRowVerticalFocus( DetailRow.KEYWORDS, 0, detailRowPresence )"
            )
        )
        assertTrue(
            "the people card hands over its COLUMN, which is what the chips " +
                "below are aimed by",
            detail.contains(
                "detailRowVerticalFocus( DetailRow.PEOPLE, column ?: 0, detailRowPresence )"
            )
        )
        assertTrue(
            detail.contains(
                "detailRowVerticalFocus( DetailRow.NETWORK, networkIndex, detailRowPresence )"
            )
        )
        assertTrue(
            detail.contains(
                "detailRowVerticalFocus( DetailRow.PRODUCTION, companyIndex, detailRowPresence )"
            )
        )
        assertTrue(
            "the episode row's UP belongs to the episode card, which owns the " +
                "season chip, so the row answers DOWN only",
            detail.contains(
                "detailRowVerticalFocus( DetailRow.EPISODES, 0, detailRowPresence, " +
                    "setOf(DetailFocusDirection.DOWN) )"
            )
        )
        assertTrue(
            "the collection posters keep their column too, which is what a " +
                "press out of the rows above and below is aimed by",
            detail.contains(
                "detailRowVerticalFocus( DetailRow.COLLECTION, partIndex, detailRowPresence )"
            )
        )
        assertTrue(
            detail.contains(
                "detailRowVerticalFocus( DetailRow.MORE_LIKE_THIS, recIndex, " +
                    "detailRowPresence )"
            )
        )
    }

    @Test
    fun `no rail carries its own vertical branch any more`() {
        assertEquals(
            "a press becomes a chain direction in ONE place - the handler every " +
                "row shares - and UP is read there and nowhere else",
            1,
            Regex("-> DetailFocusDirection\\.UP").findAll(detail).count()
        )
        assertEquals(
            1,
            Regex("-> DetailFocusDirection\\.DOWN").findAll(detail).count()
        )
        assertFalse(
            "the network rail's own UP arms are what the chain replaced",
            detail.contains("focusChipColumn( PEOPLE_ROW_KEY, networkIndex )")
        )
        assertFalse(
            detail.contains("focusChipColumn( PRODUCTION_ROW_KEY, networkIndex )")
        )
        assertFalse(
            detail.contains("focusChipColumn( NETWORK_ROW_KEY, companyIndex )")
        )
        assertFalse(
            detail.contains("focusChipColumn( PEOPLE_ROW_KEY, companyIndex )")
        )
    }

    @Test
    fun `the shared handler consumes a press the chain can act on and delegates it`() {
        assertTrue(
            "the decision and the move both read the chain",
            detail.contains("DetailFocusChain.nextRow(row, direction, presence) == null") &&
                detail.contains(
                    "scope.launch { moveFocusVertical(row, direction, column, presence) } true"
                )
        )
        assertTrue(
            "a press this row's own set does not cover is not consumed: the " +
                "episode row's UP is the case",
            detail.contains("direction !in directions")
        )
        assertTrue(
            "and the bottom of the page is consumed too, landing on the header " +
                "PLAY rather than being handed to the default search with only " +
                "the bottom spacer below it",
            detail.contains("scope.launch { focusTopOfPage() } true")
        )
    }

    @Test
    fun `the move reads the chain and lands through focusRow`() {
        assertTrue(
            detail.contains(
                "val target = DetailFocusChain.nextRow(from, direction, presence) ?: return false"
            )
        )
        assertTrue(detail.contains("return focusRow(target, fromColumn, from)"))
    }

    @Test
    fun `a rail the scroll disposed is revealed, then the header is the last resort`() {
        val columnArm = detail.indexOf("focusChipColumn(targetKey, fromColumn)")
        val revealArm = detail.indexOf(
            "revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
        )
        val fallback = detail.indexOf(
            "anchorKey != null && revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
        )
        assertTrue("the direct ask comes first", columnArm >= 0)
        assertTrue("a rail that exists but is disposed is put back", revealArm >= 0)
        assertTrue(
            "and only a rail that cannot answer at all falls to the header, " +
                "which is above the list and so always composed",
            fallback >= 0 && detail.contains("else -> focusTopOfPage()")
        )
        assertTrue(columnArm < revealArm)
    }

    @Test
    fun `the presence the chain reads is the same expression that renders each row`() {
        assertTrue(
            "the tag rail exists exactly when it renders",
            detail.contains("hasKeywordsRow = keywords.isNotEmpty()")
        )
        assertTrue(
            "the episode section exists exactly when it renders",
            detail.contains("hasEpisodesRow = normalizedType == \"series\" && seasons.isNotEmpty()")
        )
        assertTrue(detail.contains("hasPeopleRow = hasPeopleRow"))
        assertTrue(
            "a series' network rail - the condition that renders it",
            detail.contains(
                "hasNetworkRow = normalizedType == \"series\" && networks.isNotEmpty()"
            )
        )
        assertTrue(
            "the production rail, by the same expression the row renders on",
            detail.contains("hasProductionRow = companies.isNotEmpty()") &&
                detail.contains("if (hasProductionRow) {")
        )
        assertTrue(
            "and the people and network render guards read the same names",
            detail.contains("if (hasPeopleRow) {") && detail.contains("if (hasNetworkRow) {")
        )
        assertTrue(
            "the collection rail exists exactly when it renders",
            detail.contains("hasCollectionRow = collectionParts.isNotEmpty()") &&
                detail.contains("if (hasCollectionRow) {")
        )
        assertTrue(
            "and More Like This by the expression that renders it",
            detail.contains("hasMoreLikeThisRow = recommendations.isNotEmpty()") &&
                detail.contains("if (hasMoreLikeThisRow) {")
        )
    }

    @Test
    fun `the row order lives in exactly one place and mirrors the screen`() {
        val body = chain
            .substringAfter("enum class DetailRow {")
            .substringBefore("}")
        var lastIndex = -1
        listOf(
            "HEADER",
            "KEYWORDS",
            "EPISODES",
            "PEOPLE",
            "NETWORK",
            "PRODUCTION",
            "COLLECTION",
            "MORE_LIKE_THIS"
        ).forEach { row ->
            val at = body.indexOf(row)
            assertTrue(
                "the chain must declare $row, in the order it is painted - " +
                    "people above networks above production companies, and the " +
                    "collection rail above More Like This",
                at > lastIndex
            )
            lastIndex = at
        }
        assertTrue(
            "and the walk is over that one list",
            chain.contains("val rows = DetailRow.entries") &&
                chain.contains("while (index in rows.indices)")
        )
    }

    private fun read(fileName: String): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/detail/$fileName"
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

    private companion object {
        const val DETAIL_FILE = "DetailScreen.kt"
        const val CHAIN_FILE = "DetailFocusChain.kt"
    }
}
