package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reported failures on TV Detail, and what stops them coming back.
 *
 * **The trap.** On a keyword-less title (Dark Marvels) DOWN on a genre chip did
 * nothing at all, while UP still went back to PLAY; on 30 for 30, which HAS
 * keywords, DOWN worked. The chain steps from the genre chips to the FIRST row
 * the page has, so a keyword-less title takes the GENRE -> EPISODES handoff - and
 * that section is two list items rather than a rail the chain can name, so when
 * it sat below the fold nothing had filed a requester and the press died. A card
 * rail in the same state is revealed; the episode section never was.
 *
 * **The unreachable See more.** There was no D-pad route onto the overview's
 * "View more" toggle at all: the toggle was focusable but had no FocusRequester,
 * so nothing could ask for it, and DOWN out of PLAY deliberately skips it. Its
 * own comment says the way on is "walking D-pad up from the rails" - it was never
 * wired.
 *
 * The rule half is [DetailFocusChain] (pure, tested below as behaviour); the rest
 * is key handlers and Compose modifiers, which need a device and a remote to
 * press, so it is pinned from the source.
 */
class DetailGenreTrapAndSeeMoreTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /** A presence with exactly the rows this test cares about. */
    private fun presence(
        keywords: Boolean = false,
        episodes: Boolean = true,
        people: Boolean = false
    ) = DetailRowPresence(
        hasGenreRow = true,
        hasKeywordsRow = keywords,
        hasEpisodesRow = episodes,
        hasPeopleRow = people,
        hasNetworkRow = false,
        hasProductionRow = false,
        hasReviewsRow = false,
        hasCollectionRow = false,
        hasMoreLikeThisRow = false,
        hasMediaRow = false
    )

    private fun member(start: String, end: String): String =
        detail.substringAfter(start).substringBefore(end)

    // ── T1: the GENRE -> EPISODES handoff lands ────────────────────────────

    @Test
    fun `a keyword-less title hands DOWN straight to the episode section`() {
        assertEquals(
            "with no keywords the chain steps over that row and the episode " +
                "section is what DOWN from the genre chips means - the Dark " +
                "Marvels case",
            DetailRow.EPISODES,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(keywords = false)
            )
        )
        assertEquals(
            "and a title WITH keywords still stops on the tag rail first, so " +
                "30 for 30 is untouched",
            DetailRow.KEYWORDS,
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(keywords = true)
            )
        )
    }

    @Test
    fun `the episode section is revealed like a rail, not asked once`() {
        val row = member("DetailRow.EPISODES -> ", "DetailRow.GENRE,")
        assertTrue(
            "the EPISODES arm no longer answers a failed ask with the header " +
                "fallback alone: it reveals the section first, which is what a " +
                "card rail below the fold has always got",
            row.contains("revealAndFocusEpisodes(from) || focusTopOfPage()")
        )

        val reveal = member("suspend fun revealAndFocusEpisodes(", "suspend fun focusRow(")
        assertTrue(
            "the reveal asks first - the common case is a section already on " +
                "screen - and only then scrolls",
            reveal.indexOf("if (focusSeasonOrEpisode()) return true") in 0 until
                reveal.indexOf("revealRail(")
        )
        assertTrue(
            "the scroll anchors on the row the press came from, so a press from " +
                "the genres starts its search at the top of the list",
            reveal.contains(
                "firstOrNull { it.key == detailRowKey(from) }?.index ?: 0"
            )
        )
        assertTrue(
            "the season chips are revealed first: they are what a press from " +
                "above lands on, and the episode cards are one item below them",
            reveal.indexOf("revealRail(SEASON_ROW_KEY, hint)") in 0 until
                reveal.indexOf("revealRail(EPISODES_ROW_KEY, hint)")
        )
        assertTrue(
            "and the ask is retried after the scroll, so the press is never left " +
                "without a landing attempt",
            reveal.contains("return focusSeasonOrEpisode()")
        )
        assertTrue(
            "the two keys ARE the items that render the section",
            detail.contains("item(key = SEASON_ROW_KEY, contentType = \"section-row\")") &&
                detail.contains("key = EPISODES_ROW_KEY")
        )
    }

    // ── T2: loading is not an answer, and not an edge ──────────────────────

    @Test
    fun `a season that has not arrived is waited for, not answered with false`() {
        val focus = member("suspend fun focusSeasonOrEpisode(): Boolean {", "suspend fun armSeasonSwapSink(")
        assertTrue(
            "the season is read from the state that keeps up with recomposition, " +
                "not from the composition that made the call - a wait cannot see " +
                "the value it started with change",
            focus.contains("var season = currentSeason.value")
        )
        assertTrue(
            "and it waits the page's bounded budget before giving up",
            focus.indexOf("while (season == null && waitedForSeason < RETURN_FOCUS_STEPS)") in
                0 until focus.indexOf("val episodeNum = targetEpisodeNumber")
        )
        assertTrue(
            "the handle itself is the season the page is showing NOW",
            detail.contains("val currentSeason = rememberUpdatedState(effectiveSeason)")
        )
    }

    @Test
    fun `a press is not eaten as the page edge while its rows are still arriving`() {
        assertTrue(
            "the two loads the row presence depends on are what \"still " +
                "arriving\" means",
            detail.contains("fun rowsStillArriving(): Boolean = isLoading || episodesLoading")
        )

        val handler = member("fun detailRowVerticalFocus(", "fun nonRailVerticalFocus(")
        val edgeArm = handler.indexOf(
            "DetailFocusChain.nextRow(row, direction, presence) == null"
        )
        assertTrue(
            "the no-row-that-way arm is gated on the page having settled: the " +
                "wait is started BEFORE the press is consumed",
            edgeArm in 0 until handler.indexOf("if (rowsStillArriving()) {") &&
                handler.contains(
                    "startFocusMove { awaitRowsThenMove(row, direction, column, presence) }"
                )
        )

        val wait = member("suspend fun awaitRowsThenMove(", "fun detailRowVerticalFocus(")
        assertTrue(
            "the wait is bounded by the page's own retry budget",
            wait.contains("while (rowsStillArriving() && waited < RETURN_FOCUS_STEPS)")
        )
        assertTrue(
            "and it re-asks with the presence that LANDED, which is the whole " +
                "point of waiting",
            wait.contains("val settled = latestRowPresence.value ?: presence") &&
                detail.contains("SideEffect { latestRowPresence.value = detailRowPresence }")
        )
        assertTrue(
            "a page that genuinely ends there still ends there: no row, no move",
            wait.contains("the page has no row that way\"")
        )
        assertFalse(
            "and a press at a settled edge is not handed to the default search, " +
                "which is what the consumed arm has always meant",
            handler.contains("awaitRowsThenMove(row, direction, column, presence) false")
        )
    }

    // ── T3: the fall-through lands on the toggle ───────────────────────────

    @Test
    fun `a DOWN press the chain cannot place lands on the overview toggle`() {
        val move = member("suspend fun moveFocusVertical(", "fun detailRowVerticalFocus(")
        val fallThrough = move.indexOf("if (fallThroughIfMidPage(from, direction, presence)) return true")
        val toggle = move.indexOf("return focusOverviewToggle()")
        assertTrue(
            "the platform's search is tried first and the toggle is the stop " +
                "after it, so the existing default search keeps its primacy",
            fallThrough in 0 until toggle
        )
        assertTrue(
            "and only mid-page: a press at a real edge is still consumed where " +
                "it is, which is why the edge is asked again here",
            move.contains(
                "direction == DetailFocusDirection.DOWN && " +
                    "!DetailFocusChain.isPageEdge(from, direction, presence)"
            )
        )

        val ask = member("suspend fun focusOverviewToggle(): Boolean {", "suspend fun focusTopOfPage(")
        assertTrue(
            "a page whose overview fits has no toggle, and nothing is asked for " +
                "one",
            ask.contains("if (!overviewToggleAvailable) return false")
        )
        assertTrue(
            "the toggle's item is scrolled back before the ask is retried: a " +
                "disposed toggle has no requester to take the focus",
            ask.indexOf("revealRail(OVERVIEW_ROW_KEY, 0)") in 0 until
                ask.indexOf("while (attempts < RETURN_FOCUS_STEPS)")
        )
        assertTrue(
            "and the answer is the request's own report, not \"the call did not " +
                "throw\"",
            ask.contains("runCatching { overviewToggleRequester.requestFocus() }" +
                ".getOrDefault(false)") && ask.contains("return false")
        )
    }

    // ── T4/T5: the UP route, and the paths that must not move ──────────────

    @Test
    fun `UP out of the first list row is the toggle, and only UP`() {
        val move = member("suspend fun moveFocusVertical(", "fun detailRowVerticalFocus(")
        assertTrue(
            "the route is one place, in the page's single vertical handoff: every " +
                "first-list-row press (keywords, seasons, cast) goes through it, so " +
                "no rail can disagree about it",
            move.contains(
                "direction == DetailFocusDirection.UP && " +
                    "from == firstListRow(presence) && " +
                    "focusOverviewToggle()"
            )
        )
        assertTrue(
            "\"first list row\" is read from the chain, so it cannot drift from " +
                "the row order",
            detail.contains("fun firstListRow(presence: DetailRowPresence): DetailRow? =") &&
                detail.contains(
                    "DetailFocusChain.nextRow( DetailRow.GENRE, " +
                        "DetailFocusDirection.DOWN, presence )"
                )
        )
        assertTrue(
            "and the toggle gets the handle it never had",
            detail.contains(".focusRequester(overviewToggleRequester)") &&
                detail.contains("val overviewToggleRequester = remember { FocusRequester() }")
        )
        assertFalse(
            "the toggle is NOT a chain row: no row's idea of what is above or " +
                "below it moves, which is what keeps DOWN out of PLAY on the genres",
            DetailRow.entries.any { it.name.contains("OVERVIEW") || it.name.contains("INFO") }
        )
        assertTrue(
            "DOWN out of PLAY is still HEADER's own chain move, untouched",
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                presence(keywords = false)
            ) == DetailRow.GENRE
        )
        assertTrue(
            "and DOWN from the genres still lands on the first list row",
            DetailFocusChain.nextRow(
                DetailRow.GENRE,
                DetailFocusDirection.DOWN,
                presence(keywords = true)
            ) == DetailRow.KEYWORDS
        )
    }

    @Test
    fun `the toggle keeps its own presses, and UP still leaves it for PLAY`() {
        val toggleHandler = member(
            "fun overviewToggleVerticalFocus(",
            "suspend fun focusFirstMediaCard("
        )
        assertTrue(
            "UP is the header (PLAY), as it always was",
            toggleHandler.contains("onUp = { startFocusMove { focusTopOfPage() } }")
        )
        assertTrue(
            "and DOWN is the genres, through HEADER's chain position",
            toggleHandler.contains(
                "moveFocusVertical( DetailRow.HEADER, DetailFocusDirection.DOWN, 0, presence )"
            )
        )
        assertTrue(
            "the overview text itself is still not focusable - F2 routes onto the " +
                "toggle, not onto the paragraph",
            detail.contains("text = if (overviewExpanded) \"View less\" else \"View more\"")
        )
    }

    // ── T6: one route, no second ───────────────────────────────────────────

    @Test
    fun `exactly one place routes onto the toggle, and it is the UP walk`() {
        val asks = Regex("focusOverviewToggle\\(\\)").findAll(detail).count()
        assertEquals(
            "the declaration, the UP route, and the DOWN last resort - a third " +
                "caller would be a second route onto the toggle, which the report " +
                "asked not to add",
            3,
            asks
        )
        assertEquals(
            "and the requester is asked only inside the one function that owns it " +
                "(its first ask and its retry loop)",
            2,
            Regex("overviewToggleRequester\\.requestFocus\\(\\)").findAll(detail).count()
        )
        assertTrue(
            "the availability flag is written only where the toggle composes",
            detail.contains("SideEffect { overviewToggleAvailable = true }") &&
                detail.contains("SideEffect { overviewToggleAvailable = false }")
        )
        assertNull(
            "sanity: an empty request never resolves to a row",
            DetailFocusChain.nextRow(
                DetailRow.MEDIA,
                DetailFocusDirection.DOWN,
                presence()
            )
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

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
                if (candidate.isDirectory) return candidate
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
