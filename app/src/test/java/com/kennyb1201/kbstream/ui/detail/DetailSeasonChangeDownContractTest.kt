package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOWN off a season chip after a season change lands on an episode card
 * (detail season-change DOWN, F1-F3).
 *
 * The 0.9.221 field report from the Onn: UP to the chips works, moving across
 * them changes seasons, and then DOWN does nothing with the new season's
 * episodes visible on screen. The loading window was ruled out by that "visible"
 * - the cards are composed; what is dead is the scrolled row.
 *
 * The episode row keeps its old season's scroll position across a change (one
 * shared row state, re-keyed items, surviving offset), and `focusSeasonOrEpisode`
 * asked for the target card's requester BEFORE it scrolled - so the new season's
 * first card was off screen, uncomposed, unfiled. That branch was skipped, the
 * function fell back to the chip the press came from (already focused, answers
 * true), and the handler's fall-through never ran. Chicken and egg, eaten every
 * press.
 *
 * Pinned here: the chip's DOWN asks for a CARD only, scrolling first (F1); a
 * manual season change re-opens the row at the target by scrolling, never by
 * moving focus (F2); and a miss says which branch it was (F3). The chain
 * function's own contract, the edge-swap path and the auto-scroll effect's gate
 * are pinned as unchanged, because each of them is a report of its own.
 *
 * Source-reading style, like this page's other contract tests.
 */
class DetailSeasonChangeDownContractTest {

    private val detail by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The chip-DOWN path's card-only landing. */
    private val card: String by lazy {
        detail.substringAfter("suspend fun focusEpisodeCard(): Boolean {")
            .substringBefore("fun armSeasonSwapSink(")
    }

    /** The chain's shared landing, whose contract must not move. */
    private val seasonOrEpisode: String by lazy {
        detail.substringAfter("suspend fun focusSeasonOrEpisode(): Boolean {")
            .substringBefore("suspend fun focusEpisodeCard(): Boolean {")
    }

    /** The chips' vertical handler. */
    private val chipHandler: String by lazy {
        detail.substringAfter("fun seasonChipVerticalFocus(")
            .substringBefore("fun overviewToggleVerticalFocus(")
    }

    /** The pre-existing page resets the row to - gated on the viewer not steering. */
    private val autoScroll: String by lazy {
        detail.substringAfter(
            "LaunchedEffect( type, effectiveSeason, episodesLoading, targetEpisodeIndex, " +
                "userManuallyChangedSeason )"
        ).substringBefore(
            "LaunchedEffect( type, effectiveSeason, episodesLoading, episodes, " +
                "targetEpisodeIndex"
        )
    }

    /** The manual-change effect this spec adds. */
    private val manualScroll: String by lazy {
        detail.substringAfter(
            "LaunchedEffect( type, effectiveSeason, episodesLoading, episodes, " +
                "targetEpisodeIndex, userManuallyChangedSeason )"
        ).substringBefore("/**")
    }

    /** The edge-swap landing effect: the only path that moves focus across a swap. */
    private val swapLanding: String by lazy {
        detail.substringAfter(
            "LaunchedEffect( effectiveSeason, episodesLoading, episodes, " +
                "episodeTransitionState.edge )"
        )
    }

    // ── F1: the chip's DOWN asks for a card, scrolling first ──────────────

    @Test
    fun `the chip's DOWN lands on a card and never on the chip`() {
        assertTrue(
            "F1: the handler asks for the card-only landing",
            chipHandler.contains("if (!focusEpisodeCard())")
        )
        assertTrue(
            "F1: and the landing is a real one - [landFocusOn]'s two consecutive " +
                "acceptances, so a card that accepts and is disposed a frame later (the " +
                "season swap's own shape) cannot pass for a delivery",
            card.contains("val landed = landFocusOn(requester)") && card.contains("return landed")
        )
        assertFalse(
            "F1: the season chip is never a landing on this path - a chip that is already " +
                "focused answers true, which is exactly how the press reported success and " +
                "moved nothing",
            card.contains("seasonFocusRequesters") ||
                card.contains("focusSeasonChip") ||
                card.contains("requestFocus()")
        )
        assertTrue(
            "F1: true only when focus landed, and false leaves the caller's fall-through " +
                "to the geometry search - which can only find nodes BELOW the chip",
            card.contains("return false") && chipHandler.contains("fallThroughIfMidPage")
        )
    }

    @Test
    fun `the row is scrolled BEFORE the requester is looked up`() {
        assertTrue(
            "F1: the index-based scroll first - it needs no requester, which is what breaks " +
                "the loop the old order was stuck in",
            card.indexOf("scrollToItem(targetIndex)") in 0 until
                card.indexOf("episodeFocusRequesters[season to episodeNum]")
        )
        assertTrue(
            "F1: then the composition wait, then the lookup, then the ask - the same shape " +
                "the edge-swap landing effect uses",
            card.indexOf("scrollToItem(targetIndex)") < card.indexOf("delay(90)") &&
                card.indexOf("delay(90)") < card.indexOf("episodeFocusRequesters[")
        )
    }

    @Test
    fun `a press with no target yet is a miss, not a landing`() {
        assertTrue(
            "F1/F3: the season wait is the same bounded budget every ask in this file uses, " +
                "because a null season here is \"not yet\", not \"nothing to land on\"",
            card.contains("var season = currentSeason.value") &&
                card.contains("while (season == null && waitedForSeason < RETURN_FOCUS_STEPS)") &&
                card.contains("delay(RETURN_FOCUS_DELAY_MS)")
        )
        assertTrue(
            "F1: with no season, no target episode or no index there is nothing to scroll to " +
                "or ask for, and the press answers false instead of pretending",
            card.contains("if (season == null || episodeNum == null || targetIndex < 0) {") &&
                card.indexOf("targetIndex < 0") < card.indexOf("scrollToItem(targetIndex)")
        )
    }

    // ── F2: a manual season change re-opens the row at the target ─────────

    @Test
    fun `a manual season change scrolls the row and leaves the focus on the chip`() {
        assertTrue(
            "F2: the effect keys on the season, the load, the landed episodes, the target " +
                "and the manual flag - so it runs when the new season's list arrives, not " +
                "while the old one is still on screen",
            manualScroll.isNotEmpty() &&
                manualScroll.contains("userManuallyChangedSeason &&") &&
                manualScroll.contains("!episodesLoading") &&
                manualScroll.contains("episodes.isNotEmpty()")
        )
        assertTrue(
            "F2: and it scrolls the row to the target - index-based, so the card composes " +
                "and files the requester the next DOWN needs",
            manualScroll.contains("episodesRowState.scrollToItem(targetEpisodeIndex)")
        )
        assertFalse(
            "F2: SCROLL ONLY. The chip the viewer pressed is where focus belongs; the edge " +
                "swap is the one path that moves focus across a season change",
            manualScroll.contains("requestFocus") ||
                manualScroll.contains("landFocusOn") ||
                manualScroll.contains("seasonFocusRequesters") ||
                manualScroll.contains("episodeFocusRequesters")
        )
    }

    @Test
    fun `the auto-scroll effect keeps its manual-change gate`() {
        assertTrue(
            "F2: the '!userManuallyChangedSeason' gate is the scroll-yank fix (a late " +
                "episode delivery dragging the rail back after the D-pad left it) and it " +
                "stays - the new effect is separate precisely because it must not loosen it",
            autoScroll.contains("episodeAutoFocusAllowed()") &&
                autoScroll.contains("!userManuallyChangedSeason ) {") &&
                autoScroll.contains("episodesRowState.scrollToItem(targetEpisodeIndex)")
        )
    }

    // ── F3: the miss names its branch ─────────────────────────────────────

    @Test
    fun `every miss writes one line naming its branch`() {
        listOf("branch=loading", "no-requester", "branch=exhausted").forEach { token ->
            assertTrue("F3: $token is logged", card.contains(token))
        }
        assertTrue(
            "F3: one line per failure, in the file's Log.w(TAG, ...) pattern with a " +
                "branch= token - and the scores include episodes and loading, so a paste " +
                "separates \"still arriving\" from \"never composed\"",
            card.count("Log.w(TAG, missLine)") == 3 &&
                card.count("branch=") == 3 &&
                card.contains("loading=\$episodesLoading episodes=\${episodes.size}")
        )
        assertTrue(
            "F3 and the settler's COPY DIAGNOSTICS step: the same line goes to the page's " +
                "trace as well. Log.w alone is logcat, and the release APK carries no debug " +
                "log (a TV has none at all) - so a paste would not show the miss at all",
            card.count("DetailTrace.note(missLine)") == 3 &&
                card.indexOf("Log.w(TAG, missLine)") in 0 until
                card.indexOf("DetailTrace.note(missLine)")
        )
        assertTrue(
            "F3: the exhausted line also names the rows still visible, which is how a " +
                "composed-but-unfiled card is told from a row that never scrolled",
            card.contains("visible=\${episodesRowState.layoutInfo.visibleItemsInfo.size}")
        )
    }

    // ── §3: what must not change ──────────────────────────────────────────

    @Test
    fun `the chain's own landing keeps its contract for its other caller`() {
        assertTrue(
            "the season-chip fallback stays in focusSeasonOrEpisode: the GENRE->EPISODES " +
                "handoff legitimately lands on the chip when the page has no target episode",
            seasonOrEpisode.contains("seasonRequester") &&
                seasonOrEpisode.contains("return true")
        )
        assertTrue(
            "and that handoff still goes through it",
            detail.contains("if (focusSeasonOrEpisode()) return true") &&
                detail.contains(
                    "DetailRow.EPISODES -> revealAndFocusEpisodes(from) || " +
                        "focusByGeometry(DetailFocusDirection.DOWN)"
                )
        )
        assertTrue(
            "no second entry path was added for it either - the new function is only the " +
                "chip's DOWN",
            chipHandler.contains("focusEpisodeCard()") &&
                detail.count("focusEpisodeCard()") == 2
        )
    }

    @Test
    fun `the edge-swap path is untouched`() {
        assertTrue(
            "the sink is still armed by that path only, and disarmed after the landing",
            detail.contains("fun armSeasonSwapSink(edge: EpisodeFocusEdge, targetSeason: Int)") &&
                detail.contains("seasonSwapSinkArmed = true") &&
                swapLanding.contains("landFocusOn(") &&
                swapLanding.contains("seasonSwapSinkArmed = false")
        )
        assertTrue(
            "and its landing still targets the season's FIRST or LAST card, not the page's " +
                "resume episode",
            swapLanding.contains("if (edge == EpisodeFocusEdge.START) 0 else episodes.lastIndex")
        )
    }

    @Test
    fun `UP out of the section still goes through the chain`() {
        assertTrue(
            "the chip's UP is the chain's row above the section, unchanged by this fix",
            chipHandler.contains(
                "moveFocusVertical( DetailRow.EPISODES, DetailFocusDirection.UP, 0, presence )"
            )
        )
        assertFalse(
            "and this handler never takes the chain's EPISODES DOWN - the cast rail",
            chipHandler.contains(
                "moveFocusVertical( DetailRow.EPISODES, DetailFocusDirection.DOWN"
            )
        )
    }

    // ── source access ─────────────────────────────────────────────────────

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

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }

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
        const val DETAIL_FILE = "DetailScreen.kt"
    }
}
