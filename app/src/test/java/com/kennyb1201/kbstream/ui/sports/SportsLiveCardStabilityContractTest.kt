package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live card must not change size, and must not recompose every frame.
 *
 * Reported: "only LIVE cards bob, only some of them, intermittently, and the
 * card itself moves, not the whole list." The cause was live data churn, not the
 * focus machinery: three of a card's slots are conditional (the score row, the
 * situation line, the channel line), a 30s poll fills one of them, the card
 * remeasures, and every row below it - and every card under it in the section -
 * shifts. Only live games get data updates, which is why only they moved, and
 * only some polls change anything, which is why it was intermittent.
 *
 * There is no TV in CI, so the wiring is read out of the source, as the hub's
 * other contracts are. What is pinned: a LIVE card reserves the score row and
 * the situation line at the height their own text will occupy, upcoming and
 * final cards keep the conditional rows they always had, the channel line can
 * never wrap into a second line, and the shared LIVE pulse is a State the dot
 * reads itself - so a frame invalidates a 9dp circle rather than every live card
 * on the slate - and holds entirely while a sheet is over the hub.
 */
class SportsLiveCardStabilityContractTest {

    private val hub: String by lazy {
        source().replace(Regex("\\s+"), " ")
    }

    /** The game card, from its declaration to the team column after it. */
    private val card: String by lazy { slice("private fun GameCard(", "private fun TeamColumn(") }

    /** The score row, from its declaration to the situation line after it. */
    private val scoreRow: String by lazy { slice("private fun ScoreRow(", "private fun SituationLine(") }

    /** The situation line, plus the slot-height helper it reserves with. */
    private val situation: String by lazy { slice("private fun SituationLine(", "private fun ScoreText(") }

    /** The score text itself, for the style/weight the slot has to match. */
    private val scoreText: String by lazy { slice("private fun ScoreText(", "private fun TeamMark(") }

    // ── The reserved rows ───────────────────────────────────────────────

    @Test
    fun `a live card reserves the score row and the situation row`() {
        assertTrue(
            "the card knows which rows live data churn is allowed to fill",
            card.contains("val liveSlots = game.state == GameState.LIVE")
        )
        assertTrue(
            "and hands both of them the same decision",
            card.contains("ScoreRow(game = game, reserveSlot = liveSlots)") &&
                card.contains("SituationLine(game = game, reserveSlot = liveSlots)")
        )
    }

    @Test
    fun `a reserved row holds the height its own text will occupy`() {
        // v3: the slot is a fixed height instead of a placeholder character.
        // The height is MEASURED from the style the row draws its value in, not
        // written down as a dp: the theme sets no lineHeight for the headline
        // styles (which is why the placeholder had to exist at all), so the
        // score row's height is Oswald's own metrics at the viewer's font scale.
        // Only the platform can produce that number, and a constant would be
        // wrong at the next font scale - shorter than the score landing in it,
        // which is the bobbing this whole mechanism exists to stop.
        val slot = slice("private fun rememberRowSlotHeight(", "private fun ScoreRow(")
        assertTrue(
            "the slot is measured by the platform's own text layout",
            slot.contains("val measurer = rememberTextMeasurer()") &&
                slot.contains("measurer.measure(") &&
                slot.contains("with(density) { measured.size.height.toDp() }")
        )
        assertTrue(
            "the score slot is measured in the score's own style, weight and pair of glyphs",
            scoreRow.contains("style = MaterialTheme.typography.headlineLarge,") &&
                scoreRow.contains("sample = RESERVED_SCORE_SAMPLE,") &&
                scoreRow.contains("fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold")
        )
        assertTrue(
            "and that is the pair ScoreText itself draws with, so the slot cannot drift from it",
            scoreText.contains("fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold")
        )
        assertTrue(
            "the situation slot is measured in the line's own style",
            situation.contains("style = MaterialTheme.typography.bodySmall,") &&
                situation.contains("sample = RESERVED_SITUATION_SAMPLE")
        )
        assertTrue(
            "and both rows spend it on the reserved state only",
            scoreRow.contains(
                ".then(if (reserveSlot) Modifier.heightIn(min = slotHeight) else Modifier),"
            ) &&
                situation.contains(
                    ".then(if (reserveSlot) Modifier.heightIn(min = slotHeight) else Modifier)"
                )
        )
        assertFalse(
            "no written-down dp anywhere: it would be right at one font scale and wrong at the next",
            hub.contains("ScoreRowHeight = ") || hub.contains("SituationLineHeight = ") ||
                scoreRow.contains("Modifier.height(") || situation.contains("Modifier.height(")
        )
    }

    @Test
    fun `an empty reserved slot draws no stand-in text`() {
        assertTrue(
            "the empty state is empty text - the measured height is the whole reservation",
            situation.contains("text = situation.orEmpty(),") &&
                scoreRow.contains("score = away,") &&
                scoreRow.contains("score = home,")
        )
        assertFalse(
            "and the placeholder character is gone from the file",
            hub.contains("RESERVED_ROW_TEXT")
        )
        assertTrue(
            "the score text still draws whatever it is handed, blank included",
            scoreText.contains("text = score.orEmpty(),")
        )
    }

    @Test
    fun `upcoming and final cards keep the rows they always had`() {
        assertTrue(
            "with nothing reserved, a game that has not started still draws no score row",
            scoreRow.contains("if (away == null && home == null && !reserveSlot) return")
        )
        assertTrue(
            "and a game with no situation still draws no line - not a blank one",
            situation.contains("if (situation == null && !reserveSlot) return")
        )
        assertTrue(
            "the reservation is the caller's decision, defaulted off so every other caller is untouched",
            scoreRow.contains("reserveSlot: Boolean = false") &&
                situation.contains("reserveSlot: Boolean = false")
        )
    }

    // ── The third slot: the channel line ────────────────────────────────

    @Test
    fun `the channel line cannot wrap into a second line on a poll`() {
        // "Finding channel…" becomes "Not in your playlist" on the poll that
        // finishes the match, and at a narrow card width the longer sentence
        // could take two lines where the first took one - the same remeasure the
        // reserved rows above it exist to stop.
        val channelLine = slice("private fun ChannelLine(", "private fun TournamentCard(")
        assertTrue(
            "one line and an ellipsis, whatever the match pass says next",
            channelLine.contains("maxLines = 1,") && channelLine.contains("overflow = TextOverflow.Ellipsis")
        )
    }

    // ── The pulse ───────────────────────────────────────────────────────

    @Test
    fun `the pulse is read once per dot, and never by the card`() {
        val dot = slice("private fun LiveDot(", "private fun BroadcastChip(")
        assertTrue(
            "the dot takes the shared clock as a State, not as a value",
            dot.contains("LiveDot(pulse: State<Float>")
        )
        assertTrue(
            "and reads it inside itself, so a frame invalidates the dot",
            dot.contains("val alpha = pulse.value")
        )
        assertFalse(
            "the card passes the clock through: reading it up there is what rebuilt " +
                "every live card 60 times a second",
            card.contains("livePulse.value")
        )
        assertTrue(
            "it only forwards it",
            card.contains("GameStatusLine(game = game, livePulse = livePulse)")
        )
    }

    @Test
    fun `the score row's own style carries no line height to deduct one from`() {
        // The reason the reservation is a blank rather than a number: the theme
        // defines every type slot, and only the body styles set a line height.
        // Read from the theme, so a future edit that adds one there cannot leave
        // this contract quietly describing a mechanism that no longer exists.
        val theme = File(findSourceRoot(), THEME).readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "headlineLarge is font/size only",
            theme.contains("headlineLarge = androidx.compose.ui.text.TextStyle( fontFamily = OswaldFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.5.sp )")
        )
        assertTrue(
            "while bodySmall does set one, which is why its row could be reserved either way",
            theme.contains("bodySmall = androidx.compose.ui.text.TextStyle( fontFamily = OswaldFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp, lineHeight = 16.sp )")
        )
    }

    @Test
    fun `the beat holds while a sheet is open instead of animating behind it`() {
        val pulse = slice("private fun rememberLivePulse(", "private fun LiveDot(")
        assertTrue("the section tells the clock whether to run", pulse.contains("pulseEnabled: Boolean = true"))
        assertTrue(
            "reduced motion and a paused pulse are the same held state - full strength",
            pulse.contains("if (reducedMotion || !pulseEnabled) return held")
        )
        assertTrue(
            "and no transition is composed at all in that state, so a paused beat costs no frames",
            pulse.indexOf("return held") < pulse.indexOf("rememberInfiniteTransition(")
        )
    }

    @Test
    fun `the pulse is a State the section shares, held at full strength off-clock`() {
        val pulse = slice("private fun rememberLivePulse(", "private fun LiveDot(")
        assertTrue(
            "the clock is handed around as a State, never as a plain per-frame Float",
            pulse.contains("private fun rememberLivePulse(pulseEnabled: Boolean = true): State<Float>")
        )
        assertTrue(
            "reduced motion (and a paused beat) return a non-updating state held at exactly full strength",
            pulse.contains("val held = remember { mutableStateOf(1f) }")
        )
    }

    @Test
    fun `no pulse value is read outside the dot`() {
        // The whole point of the State: exactly one read, in the 9dp dot. A read
        // one level up rebuilt every live card on the slate ~60 times a second,
        // and the focused card's animated focus zoom never settled under that
        // recomposition storm - the shiver the field reported, which is why the
        // v1 slot reservations alone did not fix the bobbing.
        assertFalse(
            "the clock's value must never be read by a card or a status line",
            hub.contains("livePulse.value")
        )
        assertEquals(
            "exactly one read site in the whole section",
            1,
            Regex("pulse\\.value").findAll(hub).count()
        )
        assertTrue(
            "and it is the dot's own",
            slice("private fun LiveDot(", "private fun BroadcastChip(")
                .contains("val alpha = pulse.value")
        )
    }

    // ── Source access ───────────────────────────────────────────────────

    private fun slice(startMarker: String, endMarker: String): String {
        val start = hub.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = hub.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return hub.substring(start, end)
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
        const val THEME = "com/kennyb1201/kbstream/ui/theme/Theme.kt"
    }
}
