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
 * section is painted LAST, under the collection, More Like This and the review
 * rail, the row is ONE entry of the
 * vertical chain however many rails it holds inside it, the tab strip walks the
 * pages itself while the cards hand DOWN to the chain, and the two lists it
 * draws are the ones the detail call already carries.
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
    fun `the section is painted last, under the reviews`() {
        val collection = screen.indexOf("if (hasCollectionRow) {")
        val media = screen.indexOf("if (hasMediaRow) {")
        val recs = screen.indexOf("if (hasMoreLikeThisRow) {")
        val reviews = screen.indexOf("if (reviews.isNotEmpty()) {")

        assertTrue("the collection rail is painted on this page", collection >= 0)
        assertTrue("so is the Media row", media >= 0)
        assertTrue("and so is More Like This", recs >= 0)
        assertTrue("and the review rail", reviews >= 0)
        assertTrue(
            "the Media row renders AFTER every rail painted above it - the " +
                "collection, More Like This and the reviews - which is what " +
                "makes UP out of its tab strip the review rail and DOWN off " +
                "its cards the bottom of the page: the order the chain walks",
            collection < recs && recs < reviews && reviews < media
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
            "the chain declares it, below the collection rail and the " +
                "recommendations, because that is the order they are painted in",
            chain.contains("DetailRow.MEDIA -> presence.hasMediaRow")
        )
        assertTrue(
            "and its presence is the same expression that renders the row: " +
                "the strip having a page to offer",
            screen.contains("hasMediaRow = mediaTabs.isNotEmpty()")
        )
        assertTrue(
            "so UP out of the row resolves to the review rail above it and " +
                "DOWN to nothing at all, with no idea that two rails live " +
                "inside it",
            screen.contains("DetailRow.COLLECTION, DetailRow.MORE_LIKE_THIS, DetailRow.MEDIA -> {")
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
            "and its UP is the chain's: the More Like This rail above, read with " +
                "the row's own directions because a DOWN of its own is spoken for" +
                " - and in the COLUMN the section remembers, so a press out of " +
                "the row keeps the column it arrived on",
            screen.contains(
                "detailRowVerticalFocus( DetailRow.MEDIA, lastMediaCardColumn, presence, " +
                    "setOf(DetailFocusDirection.UP) )"
            )
        )
    }

    @Test
    fun `a card hands DOWN to the chain and UP back to the strip`() {
        assertTrue(
            "DOWN is the chain's press out of the row - the bottom of the page, " +
                "which lands on the header - keeping the card's column like every " +
                "other card rail on the page",
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
                    "keyEvent.key == Key.DirectionUp) { lastMediaCardColumn = index " +
                    "onFocusStrip() true }"
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
                screen.contains("focusChipColumn(MEDIA_ROW_KEY, lastMediaCardColumn)")
        )
    }

    @Test
    fun `a press into the row from above lands on the strip, not on a card`() {
        val move = screen
            .substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("suspend fun detailRowVerticalFocus(")
        assertTrue(
            "DOWN into the Media row means the row's tab strip: landing on a " +
                "card bypasses the tab-switcher, which is what left the viewer " +
                "pressing UP to reach the tabs they had just skipped",
            move.contains("if (target == DetailRow.MEDIA &&") &&
                move.contains("direction == DetailFocusDirection.DOWN) {") &&
                move.contains("return focusMediaStrip(from)")
        )
        assertTrue(
            "and the column the press arrived with is kept for the way back, " +
                "so the strip can hand it back down or up",
            move.contains("lastMediaCardColumn = fromColumn")
        )
    }

    @Test
    fun `the strip's focus language is a filled bar, not a card ring`() {
        val strip = screen
            .substringAfter("private fun MediaTabStrip(")
            .substringBefore("private fun MediaThumbCard(")
        assertTrue(
            "the whole strip is tinted while the D-pad is on it - a FILLED " +
                "BAR, which is a selector",
            strip.contains("val barTint =") &&
                strip.contains("if (isFocused) KBAccent.copy(alpha = 0.22f) else Color.Transparent") &&
                strip.contains(".clip(KBShapePill) .background(barTint)")
        )
        assertTrue(
            "and the selected page goes solid accent inside it, so the " +
                "selection stays unmistakable on that wash",
            strip.contains("active && isFocused -> KBAccent") &&
                strip.contains("active && isFocused -> KBVoid")
        )
        assertFalse(
            "the ring the CARDS wear is what the strip must not look like: at " +
                "TV distance, one press apart, an outlined pill and an outlined " +
                "card read as the same kind of thing",
            strip.contains("Modifier.border(2.dp, KBAccent, KBShapePill)")
        )
        assertTrue(
            "the strip observes the focus of its own subtree, the reading the " +
                "app's poster tiles use for a tile plus its caption",
            strip.contains("onFocusChanged { isFocused = it.hasFocus }")
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
    fun `the pages are the payload the detail call already carries`() {
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
            "the posters come off that same part, NOT a second call to the " +
                "/images endpoint the detail request already asks for inline",
            screen.contains("tmdbDetail?.images?.posters.orEmpty()")
        )
        assertTrue(
            "the pages are derived from those three lists, so a page with " +
                "nothing on it is never offered",
            screen.contains(
                "DetailMediaRow.tabs( mediaVideos.size, mediaBackdrops.size, " +
                    "mediaPosters.size )"
            )
        )
        assertTrue(
            "and the page the viewer is on is projected through the rule " +
                "object, one branch per page",
            screen.contains("MediaTab.TRAILERS -> DetailMediaRow.trailers(mediaVideos)") &&
                screen.contains("MediaTab.BACKDROPS -> DetailMediaRow.backdrops(mediaBackdrops)") &&
                screen.contains("MediaTab.POSTERS -> DetailMediaRow.posters(mediaPosters)")
        )
        assertTrue(
            "a poster card draws the portrait shape it was projected with, " +
                "so the one page that is not a 16:9 still is not cropped into one",
            screen.contains("portrait = card.portrait") &&
                screen.contains("val tileWidth = if (portrait) posterSize.width else 240.dp") &&
                screen.contains("val tileHeight = if (portrait) posterSize.height else 135.dp")
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
    fun `a trailer the player cannot open says so instead of swallowing the press`() {
        assertTrue(
            "the row's verdict reaches the card",
            screen.contains("unplayable = card.video != null && !card.playable")
        )
        assertTrue(
            "and only a playable video reaches the player - a Vimeo key used to be " +
                "built into a youtube.com URL and die in a logged exception the " +
                "viewer never saw, which is a dead press",
            screen.contains("video != null && !card.playable -> Unit") &&
                screen.contains("video != null -> playTrailer(context, video)")
        )
        val card = screen
            .substringAfter("private fun MediaThumbCard(")
            .substringBefore("private fun BackdropOverlay(")
        assertTrue(
            "the card carries the mark",
            card.contains("if (unplayable) {") &&
                card.contains("text = \"UNAVAILABLE\"")
        )
        assertTrue(
            "in its own corner, because the type badge still has to say WHAT the " +
                "video is: a teaser that cannot be opened is still a teaser",
            card.contains("badge?.takeIf { it.isNotBlank() }?.let { label ->") &&
                card.contains(".align(Alignment.TopEnd)")
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

    @Test
    fun `UP out of a card puts the tab strip back before giving up`() {
        assertTrue(
            "the strip and the cards are separate lazy items, so scrolling the " +
                "cards in can push the strip out of composition - its requester " +
                "then reports false, and that report has to be read rather than " +
                "swallowed",
            screen.contains("suspend fun focusMediaStrip(from: DetailRow): Boolean {") &&
                screen.contains(
                    "runCatching { mediaStripFocusRequester.requestFocus() }" +
                        ".getOrDefault(false)"
                )
        )
        val strip = screen
            .substringAfter("suspend fun focusMediaStrip(from: DetailRow): Boolean {")
            .substringBefore("suspend fun moveFocusVertical(")
        assertTrue(
            "the strip is scrolled back in, anchored on its OWN index while it " +
                "has one and on the row the press came from otherwise - the " +
                "cards' key is only the right anchor for a press out of the " +
                "cards themselves, and anchoring on nothing walked up to the top",
            strip.contains("detailListState.revealRail(MEDIA_STRIP_KEY, hint)") &&
                strip.contains("firstOrNull { it.key == MEDIA_STRIP_KEY }?.index") &&
                strip.contains("firstOrNull { it.key == detailRowKey(from) }?.index") &&
                strip.contains("?: 0")
        )
        assertTrue(
            "and the focus is retried while it recomposes, not asked for once",
            strip.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                strip.contains("delay(RETURN_FOCUS_DELAY_MS)")
        )
        assertTrue(
            "the card's UP hands over to that, so the press is never consumed " +
                "with the focus left on the card",
            screen.contains("onFocusStrip = { startFocusMove { focusMediaStrip(") &&
                screen.contains("DetailRow.MEDIA") &&
                screen.contains("startFocusMove { focusFirstMediaCard() }")
        )
    }

    @Test
    fun `the active page survives the tab list being rebuilt`() {
        assertTrue(
            "the tab index must not be keyed on the mediaTabs instance: the " +
                "detail payload re-emits (a rating or runtime landing, a cache " +
                "refresh) and a rebuilt list snapped the viewer back to the " +
                "first page mid-browse",
            screen.contains("var mediaTabIndex by remember { mutableIntStateOf(0) }")
        )
        assertFalse(
            "the keyed spelling is the bug",
            screen.contains("var mediaTabIndex by remember(mediaTabs)")
        )
        assertTrue(
            "list shrinkage is still handled: the active page is read with a " +
                "clamp rather than indexed blind",
            screen.contains(
                "val mediaTab = mediaTabs.getOrNull(mediaTabIndex) ?: " +
                    "mediaTabs.firstOrNull()"
            )
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
