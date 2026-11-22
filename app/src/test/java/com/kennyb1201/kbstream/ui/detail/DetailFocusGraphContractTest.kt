package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion of the TV detail page's focus graph: every focusable on the
 * page answers a vertical press through one authority.
 *
 * The reports this pins were all "trapped at a different spot": a DOWN off the
 * header found nothing on a genre-less title, a season chip's UP/DOWN ran the
 * default search (and any landing on a chip re-selects its season), the
 * overview's "View more" was a dead end, stale chip-column slots outlived the
 * title they were filed from, the review rail had no scroll state to be revealed
 * into, a season reload yanked the focus back to the episode card after the
 * viewer had left it, the season-swap sink could fail to park and stay armed,
 * and a DOWN off the Media strip could not reach a row that was below the fold.
 *
 * Read from the source, like the rest of this directory's contract tests: these
 * are key handlers and Compose modifiers, which need a device and a remote.
 */
class DetailFocusGraphContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The chain's own rules file, same treatment. */
    private val chain: String by lazy { normalized(stripComments(read(CHAIN_FILE))) }

    /** The page's hand-back driver, same treatment. */
    private val driver: String by lazy { normalized(stripComments(read(DRIVER_FILE))) }

    // --- F1: the header's action buttons ---------------------------------

    /** The header buttons' handler. */
    private val headerHandler: String by lazy {
        detail.substringAfter("fun headerActionVerticalFocus(")
            .substringBefore("fun seasonChipVerticalFocus(")
    }

    @Test
    fun `every header button answers DOWN through the chain and releases UP to the shell`() {
        assertEquals(
            "the seven action buttons - Play, Random episode, Trailer, Library, " +
                "the two thumbs and the rating - each carry the handler, plus its " +
                "own definition. A button left without one falls back to the " +
                "default search, which is the reported dead DOWN on a title with " +
                "no genre chips under it",
            8,
            Regex("headerActionVerticalFocus\\(").findAll(detail).count()
        )
        assertTrue(
            "DOWN borrows HEADER's own chain position, so it lands on the first " +
                "row this page actually has, in this button's column",
            headerHandler.contains(
                "moveFocusVertical( DetailRow.HEADER, DetailFocusDirection.DOWN, " +
                    "column, presence )"
            )
        )
        assertTrue(
            "and UP is RELEASED, not swallowed (2026-11-21): the action row is the " +
                "top of the page, so the shell's dead-press handler is the thing " +
                "that should answer it - and it answers with the TV top bar. A " +
                "screen that keeps a dead Up is a screen the shell never gets to " +
                "answer, which is why the bar was unreachable from this page",
            headerHandler.contains("Key.DirectionUp -> {") &&
                headerHandler.contains("detail header UP -> top bar") &&
                Regex("Key.DirectionUp -> \\{[^}]*false")
                    .containsMatchIn(headerHandler) &&
                !headerHandler.contains("onUp = {},")
        )
        assertTrue(
            "the press is noted and THEN released, so the paste distinguishes " +
                "\"the shell was asked\" from \"the handler ate it\"",
            headerHandler.contains("\"detail header UP -> top bar\") false }")
        )
    }

    // --- F2: the season chips --------------------------------------------

    /** The chips' handler. */
    private val seasonHandler: String by lazy {
        detail.substringAfter("fun seasonChipVerticalFocus(")
            .substringBefore("fun overviewToggleVerticalFocus(")
    }

    @Test
    fun `a season poster leaves the section UP and stays inside it DOWN`() {
        assertTrue(
            "every poster carries it, handed in by the page rather than decided " +
                "inside the rail",
            detail.contains(".onPreviewKeyEvent(chipVerticalFocus)") &&
                detail.contains("chipVerticalFocus = seasonChipVerticalFocus(") &&
                detail.contains("SeasonPosterRow(")
        )
        assertTrue(
            "UP is the chain's own press out of the section - the tag rail, or " +
                "the genre chips when there are none",
            seasonHandler.contains(
                "moveFocusVertical( DetailRow.EPISODES, DetailFocusDirection.UP, " +
                    "0, presence )"
            )
        )
        assertTrue(
            "DOWN is the episode cards directly under the chips, NOT the chain's " +
                "DOWN out of EPISODES - that row is the season chips and the " +
                "cards at once, so its DOWN is the cast rail - and it asks for a " +
                "CARD only: after a season change the chip is already focused, so " +
                "a landing that can answer with the chip is a press that reports " +
                "success and moves nothing",
            seasonHandler.contains(
                "startFocusMove(DetailRow.EPISODES, DetailFocusDirection.DOWN, presence) {"
            ) &&
                seasonHandler.contains("if (!focusEpisodeCard())")
        )
        assertTrue(
            "and when those cards are still arriving the press is not swallowed: " +
                "it takes the page's own fall-through, which is a mid-page press " +
                "and so ends in the default search, not on the chip again",
            seasonHandler.contains(
                "fallThroughIfMidPage( DetailRow.EPISODES, " +
                    "DetailFocusDirection.DOWN, presence )"
            )
        )
        assertFalse(
            "and the generic EPISODES DOWN move - the cast rail - is nowhere " +
                "near this handler",
            seasonHandler.contains("moveFocusVertical( DetailRow.EPISODES, DetailFocusDirection.DOWN")
        )
    }

    // --- F3: the overview's View-more toggle ------------------------------

    /** The toggle's handler. */
    private val toggleHandler: String by lazy {
        detail.substringAfter("fun overviewToggleVerticalFocus(")
            .substringBefore("suspend fun focusFirstMediaCard(")
    }

    @Test
    fun `the View-more toggle is the first chain row inside Details`() {
        assertTrue(
            "the toggle answers both presses through the page's one chain handler",
            detail.contains("overviewToggleVerticalFocus( detailRowPresence )")
        )
        assertTrue(
            "UP is the strip above OVERVIEW, with the header landing as the " +
                "failure arm",
            toggleHandler.contains(
                "DetailFocusChain.nextRow( DetailRow.OVERVIEW, DetailFocusDirection.UP, rows )"
            ) && toggleHandler.contains("|| focusTopOfPage(\"toggle-up\")")
        )
        assertTrue(
            "DOWN is the next Details row, asked with the presence that LANDED " +
                "rather than the one this handler was composed with",
            toggleHandler.contains("val rows = latestRowPresence.value ?: presence") &&
                toggleHandler.contains("DetailFocusChain.nextRow(") &&
                toggleHandler.contains("focusByGeometry(DetailFocusDirection.DOWN)")
        )
        assertTrue(
            "and the chain names the row (2026-11-22): the overview is the first " +
                "item of Details, under the strip, with DOWN walking into the tab",
            chain.contains("OVERVIEW") &&
                chain.contains("fun toggleDownRow(presence: DetailRowPresence): DetailRow") &&
                chain.contains("nextRow(DetailRow.OVERVIEW, DetailFocusDirection.DOWN, presence)")
        )
        assertTrue(
            "and it did NOT land between PLAY and the chips: the common DOWN out of " +
                "Play must still reach the chips, not a text button",
            DetailFocusChain.nextRow(
                DetailRow.HEADER,
                DetailFocusDirection.DOWN,
                DetailRowPresence(
                    hasGenreRow = true,
                    hasOverviewRow = true,
                    hasKeywordsRow = false,
                    hasEpisodesRow = false,
                    hasPeopleRow = false,
                    hasNetworkRow = false,
                    hasProductionRow = false,
                    hasReviewsRow = false,
                    hasCollectionRow = false,
                    hasMoreLikeThisRow = false,
                    hasMediaRow = false
                )
            ) == DetailRow.GENRE
        )
    }

    // --- F4: the chip-column slot registry -------------------------------

    /** The ask-time clamp, and nothing else. */
    private val ask: String by lazy {
        detail.substringAfter("fun focusChipColumn(")
            .substringBefore("suspend fun retryChipColumn(")
    }

    /** The title-change effect that resets the page's per-title state. */
    private val titleEffect: String by lazy {
        detail.substringAfter(
            "LaunchedEffect( type, id, initialTarget?.season, initialTarget?.episode, " +
                "detailRetryTick ) {"
        ).substringBefore("viewModel.load(")
    }

    @Test
    fun `the slot registry has a lifecycle, and one clamp`() {
        assertTrue(
            "the slots are cleared where the season and episode maps are, so a " +
                "drill-down into a narrower title starts from nothing",
            titleEffect.contains("seasonFocusRequesters.clear()") &&
                titleEffect.contains("episodeFocusRequesters.clear()") &&
                titleEffect.contains("chipColumnRequesters.clear()")
        )
        assertTrue(
            "the ask is clamped by the RAIL's item count, not by the slots filed " +
                "from a title this composition no longer shows - the reveal's own " +
                "scroll already clamps that way, and two clamps that disagree is " +
                "how a press was aimed past the end of a narrower rail",
            ask.contains("val railCount = railStateFor(rowKey)?.layoutInfo?.totalItemsCount ?: 0") &&
                ask.contains("val count = if (railCount > 0) railCount else slots.size")
        )
        assertTrue(
            "and a slot past the rail's own end is unreadable rather than a throw",
            ask.contains("slots.getOrNull(column)")
        )
    }

    @Test
    fun `every row the chain can aim at has a scroll state, reviews included`() {
        assertTrue(
            "the reveal's rail-scroll step reads this mapping, so a row missing " +
                "from it could only be entered while the card in the press's " +
                "column happened to be composed",
            detail.contains("fun railStateFor(rowKey: String): LazyListState? = when (rowKey)") &&
                detail.contains("REVIEWS_ROW_KEY -> reviewsRowState") &&
                // Every row state carries the idle pre-composition window now
                // (spec 2026-11-18, F4); the mapping is what this pin is about.
                detail.contains("val reviewsRowState = rememberIdleLazyListState()")
        )
        assertTrue(
            "and the review rail itself is scrolled by it",
            detail.contains("item(key = REVIEWS_ROW_KEY, contentType = \"section-row\") {") &&
                detail.contains("state = reviewsRowState,")
        )
    }

    // --- F6: the episode latch -------------------------------------------

    @Test
    fun `the episode effects stop placing the focus once the viewer steers away`() {
        assertEquals(
            "one of them is left - the rail's auto-SCROLL. The per-card " +
                "auto-FOCUS is gone entirely (2026-11-21, rule 2: data arrival " +
                "never moves focus); the scroll still has to respect the latch, " +
                "because a late episode delivery dragging the rail back after " +
                "the viewer walked away is the reported snap-back",
            1,
            Regex("episodeAutoFocusAllowed\\(\\) &&").findAll(detail).count()
        )
        assertFalse(
            "and nothing asks an episode card for focus when a list lands",
            detail.contains("shouldFocusThisCard")
        )
        assertTrue(
            "the record is the REQUESTED target, written where every chain move " +
                "passes, so a walk that had to step over a row still says where " +
                "the press meant to go",
            detail.contains("fun episodeAutoFocusAllowed(): Boolean =") &&
                detail.contains("lastChainLandingRow == null || lastChainLandingRow == DetailRow.EPISODES")
        )
        val move = detail.substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
        assertTrue(
            "recorded in the one funnel",
            move.contains("lastChainLandingRow = target")
        )
        assertTrue(
            "the episode card's UP records the section when its chip takes the " +
                "focus - that press stayed inside EPISODES, so a late delivery " +
                "may still place the focus",
            detail.contains("lastChainLandingRow = DetailRow.EPISODES")
        )
        assertTrue(
            "and a new title starts with no record at all",
            titleEffect.contains("lastChainLandingRow = null")
        )
    }

    // --- F7: the season-swap sink ----------------------------------------

    /** The sink's arm-and-park helper. */
    private val sink: String by lazy {
        detail.substringAfter("fun armSeasonSwapSink(")
            .substringBefore(
                "LaunchedEffect( effectiveSeason, episodesLoading, episodes, " +
                    "episodeTransitionState.edge )"
            )
    }

    /** The edge-restore effect. */
    private val edgeEffect: String by lazy {
        detail.substringAfter(
            "LaunchedEffect( effectiveSeason, episodesLoading, episodes, " +
                "episodeTransitionState.edge ) {"
        ).substringBefore("fun detailRowKey(")
    }

    /** The one place an edge is cleared. */
    private val clearState: String by lazy {
        detail.substringAfter("fun clearEpisodeTransitionState() {")
            .substringBefore("val hasStreamAddons")
    }

    @Test
    fun `the sink parks after a frame and is disarmed centrally`() {
        assertEquals(
            "both edge handlers arm through it - LEFT at the season boundary and " +
                "RIGHT at the other end - plus its own definition",
            3,
            Regex("armSeasonSwapSink\\(").findAll(detail).count()
        )
        assertTrue(
            "the park follows a frame, because the arm is a recomposition away " +
                "from the canFocus the park reads: parking in the same frame asked " +
                "a still-unfocusable node and the answer was thrown away",
            sink.contains("seasonSwapSinkArmed = true") &&
                sink.contains("awaitFrame()") &&
                sink.contains("runCatching { seasonSwapFocusSink.requestFocus() }")
        )
        assertTrue(
            "and the answer is LOGGED - silence is what left the field unable to " +
                "say whether the park had engaged",
            sink.contains("Log.i( TAG, \"season swap sink park: edge=")
        )
        assertTrue(
            "the single place an edge is cleared disarms the sink with it, so an " +
                "armed zero-size node cannot be left behind for the default search " +
                "to park the D-pad on",
            clearState.contains("seasonSwapSinkArmed = false")
        )
    }

    @Test
    fun `a swap whose season fails to load does not leave the sink armed`() {
        assertTrue(
            "a terminal error means no card will ever take this swap's focus, so " +
                "the sink is disarmed and the edge cleared - the error card's own " +
                "retry is the retry path",
            edgeEffect.contains("episodeError != null") &&
                edgeEffect.contains("clearEpisodeTransitionState()") &&
                edgeEffect.contains("season swap abandoned")
        )
    }

    // --- F8: the Media strip's DOWN anchor --------------------------------

    @Test
    fun `DOWN off the Media strip is anchored on the strip's own index`() {
        assertTrue(
            "the strip is on screen whenever this runs, so its index is a hint " +
                "the reveal can use; anchored on the cards' key the hint collapsed " +
                "to the top of the list, and the reveal's window of eight could " +
                "not reach a media row below a long review rail",
            detail.contains(
                "revealAndFocusChipColumn(MEDIA_ROW_KEY, MEDIA_STRIP_KEY, " +
                    "lastMediaCardColumn)"
            )
        )
        assertFalse(
            "the cards' own key is no longer the anchor",
            detail.contains("revealAndFocusChipColumn(MEDIA_ROW_KEY, MEDIA_ROW_KEY,")
        )
    }

    // --- F9: the abandoned hand-back --------------------------------------

    @Test
    fun `an abandoned hand-back lands on the page's own controls`() {
        assertTrue(
            "the driver reports the abandon instead of leaving the framework to " +
                "restart the focus at the top of the screen",
            driver.contains("var abandoned by mutableStateOf<String?>(null)") &&
                driver.contains("driver.recordAbandoned(handBack.label)")
        )
        assertTrue(
            "and the page answers it with the landing a restart would have made: " +
                "its own controls, through focusTopOfPage",
            detail.contains("LaunchedEffect(handFocusBack.abandoned)") &&
                detail.contains("hand-back abandoned (\$label): landing on the page's controls") &&
                detail.contains("handFocusBack.clearedAbandoned()")
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
        const val DRIVER_FILE = "DetailHandBack.kt"
    }
}
