package com.kennyb1201.kbstream.ui.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's visual spec, pinned where it can be seen.
 *
 * There is no TV in CI and this module has no Compose UI harness for a screen
 * that wants a ViewModel, a live tick and ESPN data, so - like the other UI
 * contracts in this tree - the structure is read out of the source. Which is
 * also what makes it worth having: a layout that took a photo of a TV to get
 * right should not be undoable by the next edit without something going red.
 *
 * What is pinned: the grid (two cards to a row, one live, one when the display
 * is too narrow), the card's own contents (marks with a letter fallback, the
 * record line, the week/venue context, the score as the largest thing on it),
 * the section heading's rhythm, and the single shared LIVE pulse.
 */
@OptIn(ExperimentalFoundationApi::class)
class SportsHubLayoutContractTest {

    /**
     * The source with runs of whitespace collapsed to one space. Indentation is
     * not what this file is testing, and pinning it would make every assertion
     * here fail on a reformat.
     */
    private val flat: String by lazy { source().replace(Regex("\\s+"), " ") }

    // ── The grid ────────────────────────────────────────────────────

    @Test
    fun `games are laid out in a two-column grid with a 16dp gutter`() {
        assertTrue(
            "a grid needs its column count",
            flat.contains("val gameColumns = if ( LocalConfiguration.current.screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP ) 2 else 1")
        )
        assertTrue(
            "and the gutter between the two cards is 16dp",
            flat.contains("private const val GRID_GUTTER_DP = 16")
        )
        assertTrue(
            "rows are built by chunking a section's games to the column count",
            flat.contains("games.chunked(columns)")
        )
        assertTrue(
            "the cards of a row share the width between them",
            flat.contains("horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)")
        )
    }

    @Test
    fun `a short last row keeps its cards the same width`() {
        // A lone final card stretched across the row reads as a different
        // layout rather than as the last line of the same one.
        assertTrue(
            "the leftover columns are filled with invisible spacers",
            flat.contains("repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }")
        )
    }

    @Test
    fun `a live game keeps a row to itself`() {
        assertTrue(
            "the LIVE section is drawn one card per row",
            flat.contains("games = live, columns = 1,")
        )
    }

    @Test
    fun `below 900dp the grid falls back to one column`() {
        assertTrue(
            "the threshold is 900dp wide",
            flat.contains("private const val TWO_COLUMN_MIN_WIDTH_DP = 900")
        )
        assertTrue(
            "and it is read from the display, not hardcoded",
            flat.contains("LocalConfiguration.current.screenWidthDp")
        )
    }

    // ── The card ────────────────────────────────────────────────────

    @Test
    fun `a team's mark is the crest where the feed has one and a letter tile where it does not`() {
        val mark = slice("private fun TeamMark(", "private fun clubTint(")
        assertTrue(
            "the crest loads through the app's image loader at 40dp",
            mark.contains("SubcomposeAsyncImage(") && mark.contains("size(40.dp)")
        )
        assertTrue(
            "a dead URL leaves the letter tile, not the empty plate it used to",
            mark.contains("loading = { TeamInitials(team = team) }") &&
                mark.contains("error = { TeamInitials(team = team) }")
        )
        assertTrue(
            "and a team the feed gives no mark for draws the initials outright",
            mark.contains("if (mark.isNullOrBlank())") && mark.contains("text = initials(team)")
        )
    }

    @Test
    fun `the record and the week or venue fill the card between the teams`() {
        assertTrue(
            "the code and the season record share one line under the crest",
            flat.contains("team.record?.trim()?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "joined with a middot, the way a scoreboard writes it",
            flat.contains(").joinToString(\" · \")")
        )
        assertTrue(
            "the context line prefers the week and falls back to the venue",
            flat.contains("fun ContextLine(week: String?, venue: String?)") &&
                flat.contains("val context = week?.takeIf { it.isNotBlank() } ?: venue?.takeIf { it.isNotBlank() } ?: return")
        )
        assertTrue(
            "and a note that repeats it is not drawn twice",
            flat.contains("!note.equals(context, ignoreCase = true)")
        )
    }

    @Test
    fun `a ranked side prints its ranking on the code line, and an unranked one is unchanged`() {
        // "#5 TEX · 4-0": the ranking is part of the code a scoreboard prints,
        // not a chip beside it. That is what keeps it safe - no new row means no
        // new height, and a card whose height does not change is a card a poll
        // cannot move - and it is why a pro or unranked side prints exactly the
        // line it always did.
        assertTrue(
            "the ranking prefixes the abbreviation with a space, so the code reads \"#5 TEX\"",
            flat.contains(
                "val code = listOfNotNull( team.rank?.let { \"#\$it\" }, " +
                    "team.abbreviation.ifBlank { team.displayName } ).joinToString(\" \")"
            )
        )
        assertTrue(
            "and the record is still joined to the whole of that line with the middot",
            flat.contains(
                "text = listOfNotNull( code, team.record?.trim()?.takeIf { it.isNotBlank() } )" +
                    ".joinToString(\" · \").let { if (favorite) \"★ \$it\" else it }"
            )
        )
        assertEquals(
            "the star is applied once - to the finished string, ranking and all",
            1,
            Regex("★ ").findAll(flat).count()
        )
        assertEquals(
            "and the card and the sheet share the one column, so a ranking cannot land on only one",
            1,
            Regex("private fun TeamColumn\\(").findAll(flat).count()
        )
    }

    @Test
    fun `the score is the largest thing on the card and a live one is heavier`() {
        val scoreText = slice("private fun ScoreText(", "private fun TeamMark(")
        assertTrue(
            "scores are set at headlineLarge - larger than anything else on the card",
            scoreText.contains("typography.headlineLarge")
        )
        assertTrue(
            "a live score is Bold where a final's is SemiBold",
            scoreText.contains("fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold")
        )
        assertTrue(
            "a game that has not started draws no score row at all - the one exception being a " +
                "LIVE card, which reserves the row so the score cannot resize the card when it " +
                "arrives (see SportsLiveCardStabilityContractTest)",
            flat.contains("val away = game.away.score?.takeIf { it.isNotBlank() }") &&
                flat.contains("if (away == null && home == null && !reserveSlot) return")
        )
    }

    @Test
    fun `the card is padded to the tighter rhythm the spec asks for`() {
        assertTrue(
            "16dp of side padding and 12dp top and bottom",
            flat.contains("Modifier.padding(horizontal = 16.dp, vertical = 12.dp)")
        )
        assertTrue(
            "12dp between stacked cards",
            flat.contains("verticalArrangement = Arrangement.spacedBy(CARD_GAP_DP.dp)")
        )
        assertTrue(
            "and the heading carries 12dp of its own, on top of the list's gap",
            flat.contains("modifier = Modifier.padding(top = CARD_GAP_DP.dp)")
        )
    }

    @Test
    fun `every section heading counts its contents`() {
        assertTrue(
            "UPCOMING says how many",
            flat.contains("SportsSectionHeader(title = \"UPCOMING\", count = upcoming.size)")
        )
        assertTrue(
            "and so does FINAL",
            flat.contains("SportsSectionHeader(title = \"FINAL\", count = final.size)")
        )
    }

    // ── The LIVE pulse ──────────────────────────────────────────────

    @Test
    fun `the live pulse is one shared transition, not one per card`() {
        assertEquals(
            "the whole screen runs exactly one infinite transition",
            1,
            Regex("rememberInfiniteTransition\\(").findAll(flat).count()
        )
        assertTrue(
            "the section computes it once and hands it down, told whether the beat is running",
            flat.contains("val livePulse = rememberLivePulse(pulseEnabled = pulseEnabled)")
        )
        assertTrue(
            "every card takes that same clock, as a State",
            flat.contains("livePulse = livePulse") && flat.contains("livePulse: State<Float>")
        )
        val dot = slice("private fun LiveDot(", "private fun BroadcastChip(")
        assertFalse(
            "a dot that animated itself is the per-card timer this replaced",
            dot.contains("animateFloat")
        )
        assertTrue(
            "and the dot is where the State is READ: a frame invalidates the 9dp circle, not the " +
                "card that passed the clock through untouched",
            dot.contains("LiveDot(pulse: State<Float>") && dot.contains("val alpha = pulse.value")
        )
    }

    // ── The header ──────────────────────────────────────────────────

    @Test
    fun `the screen names itself the sports hub`() {
        // The header used to read "SPORTS", which is the section word rather
        // than the screen's name - and the tab, the deep link and every contract
        // in this file already call it the hub.
        assertTrue(
            "the page title is the hub's own name",
            flat.contains("KBPageTitle(text = \"SPORTS HUB\")")
        )
        assertFalse(
            "and the bare section word is gone from the title",
            flat.contains("KBPageTitle(text = \"SPORTS\")")
        )
    }

    // ── The focus-ring landing ──────────────────────────────────────

    @Test
    fun `a focused card lands with room for its ring, not flush with the edge`() {
        // The cut this pins: a LazyColumn clips its content to its own bounds,
        // and the default bring-into-view lands the focused card's LAYOUT
        // rectangle flush with the bottom edge - so the 2dp accent border and
        // the glow, which draw OUTSIDE that rectangle, were sheared off flat
        // along the list. The first card of a section never showed it, because
        // focus arrives with that card already fully visible and nothing
        // scrolls; every card after it, which the D-pad does have to scroll to,
        // did.
        assertTrue(
            "the games list and the standings columns both land through the one wrapper",
            flat.contains("HubList( listState = listState, modifier = Modifier.fillMaxSize() )") &&
                flat.contains("HubList( listState = listState, modifier = modifier )")
        )
        assertTrue(
            "which hands the scroll a spec instead of leaving it the default one",
            flat.contains(
                "CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec)"
            )
        )
        assertTrue(
            "with the margin taken from the theme's own row inset",
            flat.contains(
                "SportsCardBringIntoViewSpec(insetPx = with(density) { KBFocusRowInset.toPx() })"
            )
        )
        assertTrue(
            "a margin that can never eat the viewport out from under a tall card",
            flat.contains("val margin = insetPx.coerceAtMost(containerSize / 3f)")
        )
        assertTrue(
            "and a card already clear of both edges is not scrolled at all - no bounce",
            flat.contains("else -> 0f")
        )
        assertTrue(
            "the list keeps enough slack past its last card for that same landing",
            flat.contains("contentPadding = PaddingValues(bottom = KBFocusRowInset + 8.dp)")
        )
    }

    /**
     * The landing the hub installs, at the inset it resolves to on a
     * TV-density screen. Arithmetic, so what follows is asserted on the spec
     * itself rather than read out of its source - the same way the Home rail's
     * landing is (see CollectionRailFocusLandingContractTest).
     */
    private val spec: SportsCardBringIntoViewSpec =
        SportsCardBringIntoViewSpec(insetPx = INSET_PX)

    @Test
    fun `a card already inside the viewport is never scrolled, wherever it sits in it`() {
        // v5, and the last of the movement: reported "only LIVE cards bob, the
        // whole card moving up and down, focused only". v1-v3 made the card's
        // CONTENT stable across a poll and v4 took the focus scale off live
        // cards, so what was left was the scroll. Bring-into-view is asked again
        // every time the focused card remeasures, and a LIVE card remeasures on
        // the 30s poll - and a card resting INSIDE the margin still had a
        // distance to travel to it, worked out from the card's own measured
        // rectangle, so a poll that shifted that rectangle by any amount
        // scrolled the card by the same amount. Inside the viewport the answer
        // is now zero however the card measured, so no recomposition can move
        // it. The two bands below are the ones the landing used to pull at, and
        // the property that ends the bob is that both are 0f rather than a
        // fresh distance.
        assertEquals(
            "a card clear of both margins rests",
            0f,
            spec.calculateScrollDistance(
                offset = 234f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "and so does one inside the LEADING margin: inside the viewport is inside the viewport",
            0f,
            spec.calculateScrollDistance(offset = 5f, size = CARD_HEIGHT, containerSize = VIEWPORT),
            TOLERANCE
        )
        assertEquals(
            "and one that stops a few pixels short of the trailing edge",
            0f,
            spec.calculateScrollDistance(
                offset = VIEWPORT - CARD_HEIGHT - 5f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
    }

    @Test
    fun `a card off the viewport is still landed on the margin, and stays where it lands`() {
        // The half that must not change: a card focus has to scroll to is still
        // brought in with [KBFocusRowInset] of room for the ring its LAYOUT
        // rectangle does not cover, in both directions - and the position it
        // lands on asks for no further scroll, which is what keeps a landing
        // from repeating itself.
        val overshoot =
            spec.calculateScrollDistance(
                offset = 1800f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            )
        assertTrue("a card past the trailing margin is pulled back", overshoot > 0f)
        assertEquals(
            "and lands exactly on the margin",
            VIEWPORT - INSET_PX,
            1800f - overshoot + CARD_HEIGHT,
            TOLERANCE
        )
        assertEquals(
            "where it asks for no further scroll",
            0f,
            spec.calculateScrollDistance(
                offset = 1800f - overshoot,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        val backOff =
            spec.calculateScrollDistance(
                offset = -100f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            )
        assertTrue("a card above the leading margin is backed off", backOff < 0f)
        assertEquals("onto the same margin", INSET_PX, -100f - backOff, TOLERANCE)
    }

    // ── Source access ───────────────────────────────────────────────

    private fun slice(startMarker: String, endMarker: String): String {
        val src = source()
        val start = src.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = src.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return src.substring(start, end)
    }

    private fun source(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"

        /** The value the hub resolves [KBFocusRowInset] to on a TV-density screen. */
        const val INSET_PX = 16f

        /** A card's height, in the raw pixels the spec is handed. */
        const val CARD_HEIGHT = 150f

        /** A 1080p viewport, in the raw pixels the spec is handed. */
        const val VIEWPORT = 1920f

        const val TOLERANCE = 0.001f
    }
}
