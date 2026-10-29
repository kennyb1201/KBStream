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
            "the feed is a grid, so a game can be its own item",
            flat.contains("LazyVerticalGrid(") &&
                flat.contains("columns = GridCells.Fixed(columns)")
        )
        assertTrue(
            "each game is keyed by its OWN id, not by the row it happened to be grouped in - a " +
                "row key was rewritten by every poll's repartition, which dropped focus",
            flat.contains("items = games,") && flat.contains("key = { it.id }")
        )
        assertTrue(
            "the cards of a row share the width between them",
            flat.contains("horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER_DP.dp)")
        )
    }

    @Test
    fun `a short last row keeps its cards the same width`() {
        // A grid cell is the column width whatever fills it, so a lone final
        // card can no longer stretch across the row the way a weighted row let
        // it - the leftover-space spacers the old chunked layout needed are
        // gone with it.
        assertTrue(
            "no filler spacers pad a short row any more",
            !flat.contains("repeat(columns - row.size)")
        )
        assertTrue(
            "and each card fills its own cell",
            flat.contains("modifier = Modifier.fillMaxWidth()")
        )
    }

    @Test
    fun `a live game keeps a row to itself`() {
        assertTrue(
            "the LIVE section asks for the full line for each of its cards",
            flat.contains("games = live, fullWidth = true,")
        )
        assertTrue(
            "which in the grid is a full-line span",
            flat.contains("span = { GridItemSpan(maxLineSpan) }")
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
            "the plate is sized by a parameter, so the sheet can run it at 40dp while a card keeps " +
                "the 48dp default",
            mark.contains("size: Dp = 48.dp") && mark.contains(".size(size)")
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
        val column = flat
            .substringAfter("private fun TeamColumn(")
            .substringBefore("private fun ScoreText(")
        assertEquals(
            "the star is applied once - to the finished string, ranking and all. Scoped to the " +
                "column, because the standings row draws its own ★ over a followed team " +
                "StandingsRow: a different surface, and not this line",
            1,
            Regex("★ ").findAll(column).count()
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
            "the games grid and the standings columns both land through a wrapper that installs it",
            // The grid's modifier also carries its focus observer now (the
            // league-entry rule lands the D-pad on the first card after a
            // switch), so the call is matched at the modifier rather than on
            // its exact tail - what this pins is that the games body goes
            // through the wrapper at all.
            flat.contains("HubGrid( listState = listState, columns = gameColumns, modifier = ") &&
                // The column wrapper, whose modifier is now the column's own
                // focus observer PLUS the caller's: the two-column body has to
                // know which column the viewer is in, because the screen's
                // D-pad scroll fallback used to read the list state nothing
                // composes with in that mode (P2 SP1).
                flat.contains("HubList( listState = listState, modifier = modifier.then(")
        )
        assertTrue(
            "which hands the scroll a spec instead of leaving it the default one",
            flat.contains(
                "CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec)"
            )
        )
        assertTrue(
            "with the margin taken from the theme's own row inset",
            flat.contains("insetPx = with(density) { KBFocusRowInset.toPx() }")
        )
        assertTrue(
            "and with the glow the focused card paints OUTSIDE its layout rect counted too",
            flat.contains("glowPx = with(density) { KBFocusGlow.toPx() }")
        )
        assertTrue(
            "plus the scale focus grows the card by, so the rect measured is the one on screen",
            flat.contains("focusedScale = KBFocusRow")
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

    /**
     * The landing the hub's grids and lists actually install: the row inset,
     * the 12dp focus glow and the row scale, as [KBFocusRow] applies them.
     *
     * Literal values rather than the theme's own constants for the same reason
     * [INSET_PX] is a literal - this pins the ARITHMETIC, and a theme edit that
     * changed the glow or the scale is exactly the kind of change that should
     * have to come here and re-derive it.
     */
    private val drawnSpec: SportsCardBringIntoViewSpec
        get() = SportsCardBringIntoViewSpec(
            insetPx = INSET_PX,
            glowPx = GLOW_PX,
            focusedScale = ROW_SCALE
        )

    @Test
    fun `a card inside the viewport keeps its cue room, and a resting card still asks for nothing`() {
        // v6. v5 answered zero for every card that fitted in the viewport (v5's
        // own field report was "only LIVE cards bob, the whole card moving up
        // and down, focused only": a card resting INSIDE the margin had a
        // distance to travel to it worked out from its own measured rectangle,
        // and a LIVE card remeasures on the 30s poll, so a poll that shifted
        // that rectangle by any amount scrolled the card by the same amount).
        // That zero also sheared the cue off a card resting within the margin of
        // an edge - the reported "clipping on bottom of matchup box", where the
        // box sits flush with the bottom of the list's clip and loses its 2dp
        // border, its 12dp glow and part of its last line to it. The room is
        // therefore paid for INSIDE the viewport too, but only past the
        // deadzone: a card on - or within the deadzone of - the room line is
        // left exactly where it is, which is the position a landing leaves it
        // in, so a remeasurement still cannot move it.
        assertEquals(
            "a card clear of both rooms rests",
            0f,
            spec.calculateScrollDistance(
                offset = 234f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "a card sitting ON the trailing room line rests - the band that used to bob",
            0f,
            spec.calculateScrollDistance(
                offset = VIEWPORT - INSET_PX - CARD_HEIGHT,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "and so does one on the leading room line",
            0f,
            spec.calculateScrollDistance(
                offset = INSET_PX,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "and one a couple of pixels inside the trailing room line, which is the jitter the deadzone is for",
            0f,
            spec.calculateScrollDistance(
                offset = VIEWPORT - INSET_PX - CARD_HEIGHT + 2f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        // The room the clip would be eating, paid for on both edges: the card is
        // moved by exactly what it is short of the room line, no more.
        assertEquals(
            "a card stopping a few pixels short of the trailing edge gets that much of its room back",
            INSET_PX - 5f,
            spec.calculateScrollDistance(
                offset = VIEWPORT - CARD_HEIGHT - 5f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "and one the same distance inside the leading edge is backed off by as much",
            -(INSET_PX - 5f),
            spec.calculateScrollDistance(
                offset = 5f,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "while a card flush with the trailing edge - the reported case - gets its whole room",
            INSET_PX,
            spec.calculateScrollDistance(
                offset = VIEWPORT - CARD_HEIGHT,
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

    @Test
    fun `the landing measures the DRAWN card - the glow and the focus growth included`() {
        // The WNBA report (2026-10-29): a Final card is the tallest shape in the
        // hub - series chip, note chip, venue, channel - and its LAYOUT rect can
        // sit exactly on the room line while the card the viewer sees, grown by
        // KBFocusRow and carrying a 12dp glow, still hangs under the clip. The
        // spec then asked for nothing, the card stayed cut, and every pass over
        // it re-requested the same zero-scroll - a card that is clipped and
        // bouncing, only while focused, in exactly one tab's card shape.
        val overhang = GLOW_PX + (ROW_SCALE - 1f) * CARD_HEIGHT / 2f
        // The layout trailing edge sits exactly ON the trailing room line.
        val onTheRoomLine = VIEWPORT - INSET_PX - CARD_HEIGHT
        assertEquals(
            "the drawn card still overhangs the room line by its glow plus half its growth, " +
                "and that is what the landing pays for",
            overhang,
            drawnSpec.calculateScrollDistance(
                offset = onTheRoomLine,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        // Live cards do not grow, so the same layout rect asks for nothing when
        // the scale really is 1f - the six-column arithmetic is untouched.
        assertEquals(
            "a container that does not scale lands its cards exactly as before",
            0f,
            spec.calculateScrollDistance(
                offset = onTheRoomLine,
                size = CARD_HEIGHT,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        // The two halves of the overhang are independent: the shortest card's
        // half-growth is under the deadzone and rightly rests, so the growth is
        // read off a tall one.
        val tall = 500f
        assertEquals(
            "while a card that grows but draws no glow pays only for the growth",
            (ROW_SCALE - 1f) * tall / 2f,
            SportsCardBringIntoViewSpec(insetPx = INSET_PX, focusedScale = ROW_SCALE)
                .calculateScrollDistance(
                    offset = VIEWPORT - INSET_PX - tall,
                    size = tall,
                    containerSize = VIEWPORT
                ),
            TOLERANCE
        )
    }

    @Test
    fun `a card flush with the bottom edge lands its DRAWN edge on the margin`() {
        // The settler's own case: the reported card, 500dp tall and flush with
        // the bottom of the list's clip. It must land with the whole drawn card
        // clear of the edge, not just its layout rect - which is what left the
        // channel line under the clip.
        val height = 500f
        val overhang = GLOW_PX + (ROW_SCALE - 1f) * height / 2f
        val flush = VIEWPORT - height
        val scroll = drawnSpec.calculateScrollDistance(
            offset = flush,
            size = height,
            containerSize = VIEWPORT
        )
        assertTrue("a card flush with the bottom is pulled up", scroll > 0f)
        assertEquals(
            "and its drawn trailing edge lands exactly on the margin",
            VIEWPORT - INSET_PX,
            flush - scroll + height + overhang,
            TOLERANCE
        )
    }

    @Test
    fun `a landing is idempotent for the drawn card - the second ask is always zero`() {
        // The bounce, stated as arithmetic: a landing whose settled position does
        // not satisfy the spec's own room rule re-requests on the next pass, and
        // a card astride the edge re-requests forever - "it jumps up and down"
        // until focus leaves. Every height the hub can draw, at offsets straddling
        // both room lines and the deadzone, must settle in ONE move.
        //
        // The returned distance is applied by SUBTRACTING it from the offset (see
        // the off-viewport test above, which lands a card at 1800f the same way),
        // so that is how the settled position is recomputed here.
        val heights = listOf(200f, 300f, 400f, 500f)
        heights.forEach { height ->
            val overhang = GLOW_PX + (ROW_SCALE - 1f) * height / 2f
            val room = INSET_PX + overhang
            val offsets = listOf(
                -60f, 0f, 1f,
                room - 1f, room, room + 1f,
                VIEWPORT - height - room - 5f,
                VIEWPORT - height - room - 1f,
                VIEWPORT - height - room,
                VIEWPORT - height - room + 1f,
                VIEWPORT - height - room + 5f,
                VIEWPORT - height - 1f,
                VIEWPORT - height,
                VIEWPORT - height + 40f,
                500f
            )
            offsets.forEach { offset ->
                val first = drawnSpec.calculateScrollDistance(
                    offset = offset,
                    size = height,
                    containerSize = VIEWPORT
                )
                val second = drawnSpec.calculateScrollDistance(
                    offset = offset - first,
                    size = height,
                    containerSize = VIEWPORT
                )
                assertEquals(
                    "a $height card landed from $offset must not ask again (asked $second)",
                    0f,
                    second,
                    TOLERANCE
                )
            }
        }
    }

    @Test
    fun `the deadzone still absorbs a drawn shortfall inside it, and pays past it`() {
        // Unchanged value, unchanged job: a card resting within a quarter of the
        // inset of its room is left alone, so a remeasurement cannot nudge it.
        // The shortfall is measured from the DRAWN edge here - the whole point of
        // the fix is that this is the edge that matters.
        val height = CARD_HEIGHT
        val overhang = GLOW_PX + (ROW_SCALE - 1f) * height / 2f
        // The offset that leaves the drawn trailing edge [shortfall] past the room
        // line, still inside the viewport.
        fun restingWith(shortfall: Float): Float =
            (VIEWPORT - INSET_PX + shortfall) - height - overhang
        assertEquals(
            "a drawn shortfall inside the deadzone rests",
            0f,
            drawnSpec.calculateScrollDistance(
                offset = restingWith(INSET_PX / 8f),
                size = height,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
        assertEquals(
            "and one past it is paid in full",
            INSET_PX / 4f + 3f,
            drawnSpec.calculateScrollDistance(
                offset = restingWith(INSET_PX / 4f + 3f),
                size = height,
                containerSize = VIEWPORT
            ),
            TOLERANCE
        )
    }

    @Test
    fun `every scroll body in the hub installs the drawn-rect spec, and no other one exists`() {
        // Three containers draw focusable KBCards under a clip: the games grid,
        // the standings list and the detail sheets' body. Each hands its scroll
        // the same spec, at the row scale its cards actually use - and a fourth
        // construction somewhere would be a container measuring a rect the
        // viewer never sees.
        val installedIn = source().lineSequence()
            .filter { it.contains("SportsCardBringIntoViewSpec(") }
            // The class's own declaration line, not a construction.
            .filterNot { it.contains("internal class") }
            .count()
        assertEquals(
            "the grid, the standings list and the sheet body, and nothing else",
            3,
            installedIn
        )
        listOf(
            "private fun HubList(" to "private fun HubGrid(",
            "private fun HubGrid(" to "internal class SportsCardBringIntoViewSpec(",
            "private fun ColumnScope.SheetScrollBody(" to "Hand this stop's D-pad Down"
        ).forEach { (start, end) ->
            val body = slice(start, end)
            assertTrue(
                "$start installs the spec with the glow and the row scale",
                body.contains("SportsCardBringIntoViewSpec(") &&
                    body.contains("glowPx = with(density) { KBFocusGlow.toPx() }") &&
                    body.contains("focusedScale = KBFocusRow")
            )
        }
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

        /** [KBFocusGlow], 12dp, at the TV density the hub runs at. */
        const val GLOW_PX = 12f

        /** [KBFocusRow]: the scale a non-live card in the hub grows to on focus. */
        const val ROW_SCALE = 1.02f

        const val TOLERANCE = 0.001f
    }
}
