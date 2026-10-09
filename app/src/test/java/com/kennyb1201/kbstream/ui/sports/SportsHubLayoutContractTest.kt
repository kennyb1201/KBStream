package com.kennyb1201.kbstream.ui.sports

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
            "a game that has not started draws no score row at all",
            flat.contains("val away = game.away.score?.takeIf { it.isNotBlank() }") &&
                flat.contains("if (away == null && home == null) return")
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
            "the section computes it once and hands it down",
            flat.contains("val livePulse = rememberLivePulse()")
        )
        assertTrue(
            "every card takes that same value",
            flat.contains("livePulse = livePulse") && flat.contains("livePulse: Float")
        )
        val dot = slice("private fun LiveDot(", "private fun BroadcastChip(")
        assertFalse(
            "a dot that animated itself is the per-card timer this replaced",
            dot.contains("animateFloat")
        )
        assertTrue(
            "the dot only draws the alpha it was given",
            dot.contains("LiveDot(alpha: Float")
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
    }
}
