package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Media row is wired into the detail page the way its rules say.
 *
 * [DetailMediaRowTest] pins the rule; this pins that the page asks it - the
 * section is painted between the collection rail and More Like This, the row is
 * ONE entry of the vertical chain however many rails it holds inside it, the tab
 * strip walks the pages itself while the cards hand DOWN to the chain, and the
 * two lists it draws are the ones the detail call already carries.
 *
 * Read from the source: these are Compose modifiers and a key handler, which
 * need a device and a remote to press.
 */
class DetailMediaRowWiringContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val screen: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The chain itself, comments removed, then whitespace collapsed. */
    private val chain: String by lazy { normalized(stripComments(read(CHAIN_FILE))) }

    @Test
    fun `the section is painted between the collection and More Like This`() {
        val collection = screen.indexOf("if (hasCollectionRow) {")
        val media = screen.indexOf("if (hasMediaRow) {")
        val recs = screen.indexOf("if (hasMoreLikeThisRow) {")

        assertTrue("the collection rail is painted on this page", collection >= 0)
        assertTrue("so is the Media row", media >= 0)
        assertTrue("and so is More Like This", recs >= 0)
        assertTrue(
            "the Media row renders BESIDE the two rails it sits between in " +
                "the chain, not after them: UP out of its tab strip is the " +
                "collection above it and DOWN off its cards is the " +
                "recommendations below, which is only true of this order",
            collection < media && media < recs
        )
        assertTrue(
            "and it carries a section header like every other row",
            screen.contains(
                "item(key = \"mediaheader\", contentType = \"section-header\") { " +
                    "KBSectionHeader( title = \"MEDIA\","
            )
        )
    }

    @Test
    fun `the row is ONE entry of the vertical chain`() {
        assertTrue(
            "the chain declares it, between the collection rail and the " +
                "recommendations, because that is the order they are painted in",
            chain.contains("DetailRow.MEDIA -> presence.hasMediaRow")
        )
        assertTrue(
            "and its presence is the same expression that renders the row: " +
                "the strip having a page to offer",
            screen.contains("hasMediaRow = mediaTabs.isNotEmpty()")
        )
        assertTrue(
            "so UP and DOWN out of the row resolve to the collection rail and " +
                "More Like This, with no idea that two rails live inside it",
            screen.contains("DetailRow.COLLECTION, DetailRow.MEDIA, DetailRow.MORE_LIKE_THIS -> {")
        )
        assertTrue(
            "and the chain's name for the row meets the list's key in one place",
            screen.contains("DetailRow.MEDIA -> MEDIA_ROW_KEY")
        )
    }

    @Test
    fun `the tab strip walks its own pages and keeps its LEFT and RIGHT`() {
        assertTrue(
            "LEFT/RIGHT move the active page one over, through the rule object " +
                "rather than a branch written out here",
            screen.contains(
                "onActiveIndex(DetailMediaRow.switched(activeIndex(), step, tabs.size))"
            )
        )
        assertTrue(
            "and they are consumed: with the strip as one focus node there is " +
                "no second tab for the default search to find, so a LEFT that " +
                "fell through would walk the focus out of the row",
            screen.contains("keyEvent.type != KeyEventType.KeyDown -> false") &&
                screen.contains("step != 0 -> { onActiveIndex(")
        )
        assertTrue(
            "the strip is that one node, focusable and holding the handler",
            screen.contains(".onPreviewKeyEvent(onKey) .focusable()")
        )
        assertTrue(
            "its DOWN is the row's own - the cards under it, not the row below",
            screen.contains("keyEvent.key == Key.DirectionDown -> { onFocusContent() true }")
        )
        assertTrue(
            "and its UP is the chain's: the collection rail above, read with " +
                "the row's own directions because a DOWN of its own is spoken for",
            screen.contains(
                "detailRowVerticalFocus( DetailRow.MEDIA, 0, presence, " +
                    "setOf(DetailFocusDirection.UP) )"
            )
        )
    }

    @Test
    fun `a card hands DOWN to the chain and UP back to the strip`() {
        assertTrue(
            "DOWN is the chain's next row, keeping the card's column like " +
                "every other card rail on the page",
            screen.contains(
                "detailRowVerticalFocus( DetailRow.MEDIA, index, presence, " +
                    "setOf(DetailFocusDirection.DOWN) )"
            )
        )
        assertTrue(
            "and UP is about the strip, which is why it is answered by the " +
                "card's own wrapper rather than by the chain",
            screen.contains(
                "if (keyEvent.type == KeyEventType.KeyDown && " +
                    "keyEvent.key == Key.DirectionUp) { onFocusStrip() true }"
            )
        )
        assertTrue(
            "the wrapper is what the cards carry",
            screen.contains(".onPreviewKeyEvent( mediaCardFocus(")
        )
        assertTrue(
            "and every card is filed under its column, which is what a press " +
                "into the row from either side is aimed by",
            screen.contains("registerChipColumn( MEDIA_ROW_KEY, cardIndex, requester )") &&
                screen.contains("focusChipColumn(MEDIA_ROW_KEY, 0)")
        )
    }

    @Test
    fun `each page gets its own column map`() {
        assertTrue(
            "a card of the page the viewer just switched away from is " +
                "disposed, and its requester left in the row's slots is the " +
                "\"rail that exists but cannot answer\" the handoff would have " +
                "to guess about - so the row parks a fresh map per page",
            screen.contains("chipColumnRequesters[MEDIA_ROW_KEY] = mediaRowColumns") &&
                screen.contains("val mediaRowColumns = remember(mediaTab) {")
        )
    }

    @Test
    fun `the two pages are the payload the detail call already carries`() {
        assertTrue(
            "the videos are the same list the header's trailer button picks " +
                "its ONE entry out of",
            screen.contains("tmdbDetail?.videos?.results.orEmpty()")
        )
        assertTrue(
            "and the backdrops are the `images` part the same " +
                "append_to_response asks for, so the extra rail costs no " +
                "second call per detail open",
            screen.contains("tmdbDetail?.images?.backdrops.orEmpty()")
        )
        assertTrue(
            "the pages are derived from those two lists, so a page with " +
                "nothing on it is never offered",
            screen.contains("DetailMediaRow.tabs(mediaVideos.size, mediaBackdrops.size)")
        )
    }

    @Test
    fun `a trailer card plays and a backdrop card opens the viewer`() {
        assertTrue(
            "a video card resolves through the same launcher the header " +
                "button does, with the card's own video",
            screen.contains("playTrailer(context, video)")
        )
        assertTrue(
            "and the header keeps its one pick",
            screen.contains("fun playTrailer(context: Context) { playTrailer(context, trailerVideo) }")
        )
        assertTrue(
            "a still has nowhere to go, so the press is answered with the " +
                "thing itself",
            screen.contains("selectedBackdrop = card.fullImageUrl") &&
                screen.contains("selectedBackdrop?.let { backdrop -> BackdropOverlay(")
        )
    }

    @Test
    fun `the row owns no vertical logic of its own beyond the strip`() {
        assertFalse(
            "the row is a row of the page's one chain: it must not decide for " +
                "itself which row is above or below it",
            screen.contains("DetailFocusChain.nextRow(DetailRow.MEDIA")
        )
        assertEquals(
            "and the chain's own direction reading stays in the one handler",
            1,
            Regex("-> DetailFocusDirection\\.UP").findAll(screen).count()
        )
        assertEquals(
            1,
            Regex("-> DetailFocusDirection\\.DOWN").findAll(screen).count()
        )
    }

    private fun read(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

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
        const val DETAIL_FILE = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val CHAIN_FILE = "com/kennyb1201/kbstream/ui/detail/DetailFocusChain.kt"
    }
}
