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
            "nine rails carry a vertical handler directly on themselves - the " +
                "genre chips, the tag rail, the episode row, the people cards, " +
                "the network chips, the production chips, the review cards, the " +
                "collection posters and the More Like This posters - and all nine " +
                "go through the same one, so no row can disagree with another " +
                "about what is above or below it. The Media row's cards compose " +
                "that same handler inside a wrapper of their own (mediaCardFocus, " +
                "which answers UP itself because UP there means the row's tab " +
                "strip); the wrapper is pinned by DetailMediaRowWiringContractTest.",
            9,
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
            "the genre chips keep their column, which is what a press out of the " +
                "tag rail below them is aimed by",
            detail.contains(
                "detailRowVerticalFocus( DetailRow.GENRE, genreIndex, detailRowPresence )"
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
        assertTrue(
            "the review cards keep their column too, so the rail is reachable " +
                "from the production chips above and the collection below",
            detail.contains(
                "detailRowVerticalFocus( DetailRow.REVIEWS, reviewIndex, " +
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
            "and the bottom of the page is consumed too - the Media row is the " +
                "last row painted, so a DOWN off it stays where the viewer is " +
                "rather than teleporting to the header PLAY",
            detail.contains(
                "DetailFocusChain.nextRow(row, direction, presence) == null) { true } else {"
            )
        )
        assertFalse(
            "the teleport is what that arm used to do with the press",
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
        assertTrue(
            detail.contains("return focusRow(target, fromColumn, from, direction, presence)")
        )
        assertTrue(
            "and the one row the move does NOT land through focusRow is the " +
                "Media row on the way DOWN: that press means the row's TAB " +
                "STRIP, so it is given to the strip directly instead of to a " +
                "card - landing on a card strands the tab-switcher behind an UP " +
                "the viewer has no reason to expect",
            detail.contains(
                "if (target == DetailRow.MEDIA && direction == DetailFocusDirection.DOWN) { " +
                    "lastMediaCardColumn = fromColumn return focusMediaStrip(from) }"
            )
        )
    }

    @Test
    fun `a rail the scroll disposed is revealed, then the next row gets the press`() {
        val columnArm = detail.indexOf("focusChipColumn(targetKey, fromColumn)")
        val revealArm = detail.indexOf(
            "revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
        )
        val fallback = detail.indexOf(
            "anchorKey != null && revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
        )
        val nextRow = detail.indexOf(
            "val nextTarget = DetailFocusChain.nextRow(target, direction, presence)"
        )
        assertTrue("the direct ask comes first", columnArm >= 0)
        assertTrue("a rail that exists but is disposed is put back", revealArm >= 0)
        assertTrue(
            "and a rail that cannot answer at all walks ON rather than giving " +
                "up: the next row that way gets the press, so a rail that is " +
                "there and will not take the focus can never teleport the " +
                "viewer back to the top of the page",
            fallback >= 0 && nextRow > fallback
        )
        assertTrue(
            "the walk's own end is where the press STAYS: with no row left that " +
                "way it is consumed where the viewer is, never handed to the " +
                "default search with only the bottom spacer below it",
            detail.contains(
                "Log.w(TAG, \"focusRow: could not focus \$target or any row beyond it\")"
            ) &&
                detail.contains("focusRow(nextTarget, fromColumn, target, direction, presence)")
        )
        assertFalse(
            "the walk steps strictly the way the press was going, which is what " +
                "bounds it - asking for the very same row again is not a move",
            detail.contains("focusRow(target, fromColumn, target")
        )
        assertTrue(columnArm < revealArm)
    }

    @Test
    fun `the reveal waits for the rail to be composed before it asks for the focus`() {
        val reveal = detail
            .substringAfter("suspend fun revealAndFocusChipColumn(")
            .substringBefore("var returnTarget by remember")
        val scroll = reveal.indexOf("detailListState.revealRail(rowKey, hint)")
        val composed = reveal.indexOf("visibleItemsInfo.any { it.key == rowKey }")
        val ask = reveal.indexOf("focusChipColumn(rowKey, fromColumn)")
        assertTrue(
            "the rail is scrolled back in first, anchored on the row the press " +
                "came from",
            scroll >= 0
        )
        assertTrue(
            "then the row has to be back among the COMPOSED items before the " +
                "focus is asked for: a request against a card that is not composed " +
                "fails silently, and that silent failure is the stranding",
            composed in (scroll + 1) until ask
        )
        assertTrue(
            "and the ask is retried for a bounded number of frames rather than " +
                "made once, because the list composes the row a frame or more " +
                "after the scroll",
            reveal.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                reveal.contains("delay(RETURN_FOCUS_DELAY_MS)")
        )
        assertTrue(
            "the outcome is reported, which is what lets the caller fall back to " +
                "the header rather than leaving the press unlanded",
            reveal.contains("return true") && reveal.contains("return false")
        )
    }

    @Test
    fun `a landing is read from the request's own answer, not from whether the call threw`() {
        // Checked against the Compose this project builds on (ui 1.12.1): an
        // unattached requester prints the focus warning and RETURNS false - it
        // does not throw - so `runCatching { ... }.isSuccess` is true for every
        // call ever made, including one against a rail the scroll disposed,
        // whose filed requesters are all unattached. focusChipColumn therefore
        // answered true for a rail that was not on screen at all: the press was
        // consumed with the focus still on the row it came from, and the reveal
        // arm above - the one that puts the rail back - could never run.
        assertTrue(
            detail.contains(
                "return runCatching { requester.requestFocus() }.getOrDefault(false)"
            )
        )
        assertTrue(
            detail.contains(
                "runCatching { movieDetailsFocusRequester.requestFocus() }" +
                    ".getOrDefault(false)"
            )
        )
        assertTrue(
            detail.contains(
                "runCatching { playButtonFocusRequester.requestFocus() }" +
                    ".getOrDefault(false)"
            )
        )
        assertFalse(
            "the swallowed-call spelling is what made the answer meaningless",
            detail.contains("return runCatching { requester.requestFocus() }.isSuccess") ||
                detail.contains(
                    "runCatching { movieDetailsFocusRequester.requestFocus() }.isSuccess"
                ) ||
                detail.contains(
                    "runCatching { playButtonFocusRequester.requestFocus() }.isSuccess"
                )
        )
        assertTrue(
            "and the retry and the reveal both hang off that answer, so an " +
                "always-true focusChipColumn is exactly what leaves them unreachable",
            detail.contains(
                "focusChipColumn(targetKey, fromColumn) -> true present && " +
                    "retryChipColumn(targetKey, fromColumn) -> true anchorKey != null " +
                    "&& revealAndFocusChipColumn(targetKey, anchorKey, fromColumn) -> true"
            )
        )
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
            "the Media row is on the page exactly when its strip has a page to " +
                "offer, which is the same expression that renders it",
            detail.contains("hasMediaRow = mediaTabs.isNotEmpty()") &&
                detail.contains("if (hasMediaRow) {")
        )
        assertTrue(
            "and More Like This by the expression that renders it",
            detail.contains("hasMoreLikeThisRow = recommendations.isNotEmpty()") &&
                detail.contains("if (hasMoreLikeThisRow) {")
        )
        assertTrue(
            "the genre rail exists exactly when it renders - it is the header's " +
                "own chips, and the chain must not name it on a title with none",
            detail.contains("hasGenreRow = tmdbGenres.isNotEmpty()") &&
                detail.contains("if (tmdbGenres.isNotEmpty()) {")
        )
        assertTrue(
            "and the review rail by the expression that renders it",
            detail.contains("hasReviewsRow = reviews.isNotEmpty()") &&
                detail.contains("if (reviews.isNotEmpty()) {")
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
            "GENRE",
            "KEYWORDS",
            "EPISODES",
            "PEOPLE",
            "NETWORK",
            "PRODUCTION",
            "REVIEWS",
            "COLLECTION",
            "MORE_LIKE_THIS",
            "MEDIA"
        ).forEach { row ->
            val at = body.indexOf(row)
            assertTrue(
                "the chain must declare $row, in the order it is painted - " +
                    "people above networks above production companies, and the " +
                    "collection rail above More Like This above the Media row, " +
                    "which is painted last of all",
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

    @Test
    fun `the episode-or-season landing is verified, never assumed`() {
        val fn = detail
            .substringAfter("suspend fun focusSeasonOrEpisode(): Boolean {")
            .substringBefore("LaunchedEffect( effectiveSeason, episodesLoading")
        assertTrue(
            "the move is asynchronous - it scrolls a lazy rail into view first - " +
                "so it is a suspend function its caller awaits",
            detail.contains("suspend fun focusSeasonOrEpisode(): Boolean")
        )
        assertTrue(
            "the episode branch retries the focus while the rail composes and " +
                "reads the request's OWN answer",
            fn.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                fn.contains("}.getOrDefault(false)")
        )
        assertTrue(
            "the season branch does the same rather than returning true whatever " +
                "happened, and the function can answer false at all",
            fn.contains("seasonRequester") &&
                fn.contains("return true") &&
                fn.contains("return false")
        )
        assertTrue(
            "which is what lets the caller's fallback to the header actually run: " +
                "with an always-true left side it never did",
            detail.contains("DetailRow.EPISODES -> focusSeasonOrEpisode() || focusTopOfPage()")
        )
    }

    @Test
    fun `a row this page has is retried once before the press steps over it`() {
        // The 2026-10-14 field race: the keywords rail was present and on
        // screen, but its chips had not finished registering, so the first ask
        // failed and the press skipped a row that was there. A row the page HAS
        // now gets one more ask a frame later, gated on presence so a row that
        // is genuinely not there is still stepped over with no wait.
        val start = detail.indexOf("val targetKey = detailRowKey(target)")
        val end = detail.indexOf("suspend fun focusMediaStrip")
        assertTrue("the chip-row arm not found", start >= 0 && end > start)
        val arm = detail.substring(start, end)
        val direct = arm.indexOf("focusChipColumn(targetKey, fromColumn)")
        val retry = arm.indexOf("present && retryChipColumn(targetKey, fromColumn)")
        val reveal = arm.indexOf("revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)")
        val next = arm.indexOf(
            "val nextTarget = DetailFocusChain.nextRow(target, direction, presence)"
        )
        assertTrue("the direct ask comes first", direct >= 0)
        assertTrue(
            "a present-but-unready row is asked again before the walk moves on",
            retry > direct
        )
        assertTrue(
            "the retry is gated on the row's own presence, which is what tells a " +
                "row that is there from one that is not",
            detail.contains("val present = DetailFocusChain.exists(target, presence)")
        )
        assertTrue("the disposed-rail reveal is still there", reveal > retry)
        assertTrue(
            "and only after the retry and the reveal does the press walk on",
            next > reveal
        )
        assertTrue(
            "the retry is one frame then one more ask, and nothing else",
            detail.contains(
                "suspend fun retryChipColumn(rowKey: String, fromColumn: Int): Boolean"
            ) &&
                detail.contains("delay(RETRY_CHIP_DELAY_MS)") &&
                detail.contains("private const val RETRY_CHIP_DELAY_MS = 50L")
        )
        assertTrue(
            "a failed ask on a row that was there is logged as a retry, so the " +
                "field log says which cause it was",
            detail.contains("focusRow(KEYWORDS) failed") && detail.contains("on retry")
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
