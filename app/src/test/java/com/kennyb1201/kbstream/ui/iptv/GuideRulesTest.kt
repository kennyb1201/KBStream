package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.XmltvChannel
import com.kennyb1201.kbstream.data.iptv.XmltvProgram
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The guide's text rules, on the JVM.
 *
 * `ui/iptv` was the one screen in the app with no test of any kind - two files,
 * 3,300 lines, and every decision inside a composable. [GuideRules] holds the
 * part that is a decision rather than a layout, so this file can pin what a
 * viewer actually reads.
 *
 * Each case below is an edge of a label that a viewer would see as a bug:
 *
 *  - a program that already ended (no badge, not "0 min left");
 *  - the last seconds of one (rounds UP, so it never says 0);
 *  - a start time under a minute away ("in <1 min", not "in 0 min");
 *  - a catch-up window that began yesterday, and one from further back, which
 *    must be told apart - see the note on the day split in [GuideRules], where
 *    writing these tests is what surfaced a caption that called every past day
 *    "Yesterday";
 *  - a window that starts exactly at midnight, which is the boundary the
 *    Today/Yesterday test gets wrong if it compares the wrong day.
 *
 * The zone is pinned because two of these format wall-clock times, and the
 * default zone of the machine running the tests must not be able to change the
 * answer. Locale is pinned for the same reason (AM/PM), and both are restored
 * afterwards so a later test in the same JVM fork is unaffected.
 */
class GuideRulesTest {

    private lateinit var previousZone: TimeZone
    private lateinit var previousLocale: Locale

    @Before
    fun pinZoneAndLocale() {
        previousZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(previousZone)
        Locale.setDefault(previousLocale)
    }

    /** A UTC instant, so the expectations below are readable as wall-clock. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    // ---------------------------------------------------------------- remaining

    @Test
    fun `a program that has already ended gets no remaining badge`() {
        assertNull(formatRemainingLabel(0L))
        assertNull(formatRemainingLabel(-1L))
        assertNull(formatRemainingLabel(-3_600_000L))
    }

    @Test
    fun `the last seconds of a program round up, so it never reads zero`() {
        assertEquals("1 min left", formatRemainingLabel(1L))
        assertEquals("1 min left", formatRemainingLabel(30_000L))
        assertEquals("1 min left", formatRemainingLabel(60_000L))
        assertEquals("2 min left", formatRemainingLabel(60_001L))
        assertEquals("2 min left", formatRemainingLabel(90_000L))
    }

    @Test
    fun `minutes, hours and both together use the singular-capable wording`() {
        assertEquals("59 min left", formatRemainingLabel(59L * 60_000L))
        assertEquals("1 hr left", formatRemainingLabel(60L * 60_000L))
        assertEquals("1 hr 1 min left", formatRemainingLabel(61L * 60_000L))
        assertEquals(
            "3 hr 20 min left",
            formatRemainingLabel((3L * 60L + 20L) * 60_000L)
        )
    }

    @Test
    fun `an hour exactly never grows a zero-minute tail`() {
        assertEquals("2 hr left", formatRemainingLabel(2L * 3_600_000L))
        assertEquals("2 hr 30 min left", formatRemainingLabel(2L * 3_600_000L + 30L * 60_000L))
    }

    // -------------------------------------------------------------- starts in

    @Test
    fun `a start that has already passed reads as starting`() {
        assertEquals("starting", formatStartsInLabel(0L))
        assertEquals("starting", formatStartsInLabel(-5_000L))
    }

    @Test
    fun `each unit boundary switches exactly once`() {
        assertEquals("in <1 min", formatStartsInLabel(1L))
        assertEquals("in <1 min", formatStartsInLabel(59_999L))
        assertEquals("in 1 min", formatStartsInLabel(60_000L))
        assertEquals("in 59 min", formatStartsInLabel(3_599_999L))
        assertEquals("in 1 hr", formatStartsInLabel(3_600_000L))
        assertEquals("in 23 hr", formatStartsInLabel(86_399_999L))
        assertEquals("in 1 d", formatStartsInLabel(86_400_000L))
        assertEquals("in 3 d", formatStartsInLabel(3L * 86_400_000L))
    }

    // ----------------------------------------------------------- time range

    @Test
    fun `a program's slot reads as two wall-clock times`() {
        assertEquals(
            "9:05 AM - 10:00 AM",
            formatTimeRange(utc(2026, 9, 30, 9, 5), utc(2026, 9, 30, 10, 0))
        )
    }

    @Test
    fun `midnight and noon are 12, not 0`() {
        assertEquals(
            "12:00 AM - 12:30 AM",
            formatTimeRange(utc(2026, 9, 30, 0, 0), utc(2026, 9, 30, 0, 30))
        )
        assertEquals(
            "12:00 PM - 1:00 PM",
            formatTimeRange(utc(2026, 9, 30, 12, 0), utc(2026, 9, 30, 13, 0))
        )
    }

    // --------------------------------------------------------- catch-up window

    @Test
    fun `a window earlier today is labelled Today`() {
        assertEquals(
            "Today \u00b7 9:05pm\u201310:00pm",
            formatCatchupWindow(
                utc(2026, 9, 30, 21, 5),
                utc(2026, 9, 30, 22, 0),
                nowMillis = utc(2026, 9, 30, 22, 30)
            )
        )
    }

    @Test
    fun `a window from the previous day is labelled Yesterday`() {
        assertEquals(
            "Yesterday \u00b7 9:05pm\u201310:00pm",
            formatCatchupWindow(
                utc(2026, 9, 29, 21, 5),
                utc(2026, 9, 29, 22, 0),
                nowMillis = utc(2026, 9, 30, 8, 0)
            )
        )
    }

    @Test
    fun `an older window is labelled with its weekday`() {
        // 2026-09-25 is a Friday; 2026-09-30 (the "now") is a Wednesday.
        assertEquals(
            "Fri \u00b7 1:30pm\u20132:00pm",
            formatCatchupWindow(
                utc(2026, 9, 25, 13, 30),
                utc(2026, 9, 25, 14, 0),
                nowMillis = utc(2026, 9, 30, 8, 0)
            )
        )
    }

    /**
     * The bug the weekday case above found: the label used to answer
     * "Yesterday" for every day before today, so the weekday branch was only
     * ever reached by a start in the FUTURE and a three-day-old catch-up
     * programme read "Yesterday". Catch-up reaches back a week.
     */
    @Test
    fun `only the day immediately before today reads as Yesterday`() {
        val now = utc(2026, 9, 30, 8, 0)
        val label = { startMillis: Long ->
            formatCatchupWindow(startMillis, startMillis + 30L * 60_000L, nowMillis = now)
                .substringBefore(" \u00b7")
        }

        assertEquals("Today", label(utc(2026, 9, 30, 9, 0)))
        assertEquals("Yesterday", label(utc(2026, 9, 29, 9, 0)))
        // 2026-09-28 is a Monday, 2026-09-27 a Sunday, 2026-09-25 a Friday.
        assertEquals("Mon", label(utc(2026, 9, 28, 9, 0)))
        assertEquals("Sun", label(utc(2026, 9, 27, 23, 59)))
        assertEquals("Fri", label(utc(2026, 9, 25, 9, 0)))
    }

    @Test
    fun `midnight is the day boundary, and it belongs to the new day`() {
        val midnightToday = utc(2026, 9, 30, 0, 0)
        val oneMinuteBefore = midnightToday - 60_000L
        val now = utc(2026, 9, 30, 8, 0)

        assertEquals(
            "Today",
            formatCatchupWindow(
                midnightToday,
                midnightToday + 30L * 60_000L,
                nowMillis = now
            ).substringBefore(" \u00b7")
        )
        assertEquals(
            "Yesterday",
            formatCatchupWindow(
                oneMinuteBefore,
                oneMinuteBefore + 30L * 60_000L,
                nowMillis = now
            ).substringBefore(" \u00b7")
        )
    }

    @Test
    fun `midnight and noon render as 12am and 12pm in the caption`() {
        val now = utc(2026, 9, 30, 8, 0)
        assertEquals(
            "Today \u00b7 12:00am\u201312:30am",
            formatCatchupWindow(utc(2026, 9, 30, 0, 0), utc(2026, 9, 30, 0, 30), nowMillis = now)
        )
        assertEquals(
            "Today \u00b7 12:00pm\u201312:30pm",
            formatCatchupWindow(utc(2026, 9, 30, 12, 0), utc(2026, 9, 30, 12, 30), nowMillis = now)
        )
    }

    // ------------------------------------------------- setup diagnostics line

    @Test
    fun `an empty setup reports exactly what is missing`() {
        assertEquals(
            "Playlist missing  \u2022  EPG optional",
            buildSetupDiagnosticsText(
                playlistUrl = "",
                epgUrl = "",
                playlistName = "",
                playlist = null,
                channelCount = 0,
                isImportingGuide = false,
                guideImportLabel = ""
            )
        )
    }

    @Test
    fun `a loaded playlist adds its channel count`() {
        val text = buildSetupDiagnosticsText(
            playlistUrl = "https://example.test/playlist.m3u",
            epgUrl = "https://example.test/guide.xml",
            playlistName = "Living room",
            playlist = IptvPlaylist(name = "Living room"),
            channelCount = 412,
            isImportingGuide = false,
            guideImportLabel = ""
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided  \u2022  Channels 412  \u2022  Name: Living room",
            text
        )
    }

    @Test
    fun `extra EPG sources are counted whether newline- or semicolon-separated`() {
        fun extra(value: String) = buildSetupDiagnosticsText(
            playlistUrl = "u",
            epgUrl = "u",
            playlistName = "",
            playlist = null,
            channelCount = 0,
            isImportingGuide = false,
            guideImportLabel = "",
            extraEpgUrls = value
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided  \u2022  Extra EPG x2",
            extra("https://a.test/g.xml\nhttps://b.test/g.xml")
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided  \u2022  Extra EPG x3",
            extra("https://a.test/g.xml;https://b.test/g.xml;https://c.test/g.xml")
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided",
            extra("   \n  ;  ")
        )
    }

    @Test
    fun `an import in progress is reported once, with its label preferred`() {
        fun importing(label: String, flag: Boolean) = buildSetupDiagnosticsText(
            playlistUrl = "u",
            epgUrl = "u",
            playlistName = "",
            playlist = null,
            channelCount = 0,
            isImportingGuide = flag,
            guideImportLabel = label
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided  \u2022  EPG importing",
            importing("", true)
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided  \u2022  EPG importing: 12 of 30",
            importing("12 of 30", true)
        )
        assertEquals(
            "Playlist ready  \u2022  EPG provided",
            importing("", false)
        )
    }

    // ── the request queue: why a row stops saying "Loading guide..." ──────
    //
    // A row renders as loading while its channel id is absent from the
    // resolved set. The lineup flow cancels an in-flight query the moment the
    // next scroll batch arrives (flatMapLatest), and a cancelled query never
    // emits - so the queue has to keep the superseded batch's ids, or those
    // rows never resolve even though their channels are marked as queued.

    @Test
    fun `a new batch is added to what is still waiting, not swapped in`() {
        val pending = GuideRequestQueue.enqueue(emptySet(), setOf("a", "b"))
        assertEquals(
            setOf("a", "b", "c", "d"),
            GuideRequestQueue.enqueue(pending, setOf("c", "d"))
        )
    }

    @Test
    fun `a superseded batch is re-issued rather than stranded`() {
        // Batch A is queued; before it is answered batch B lands and cancels
        // A's query. The next request must still carry A's channel.
        val pendingA = GuideRequestQueue.enqueue(emptySet(), setOf("a"))
        val pendingAB = GuideRequestQueue.enqueue(pendingA, setOf("b"))
        // The emission that lands answers b but not a.
        val afterB = GuideRequestQueue.clearAnswered(pendingAB, resolved = setOf("b"))
        assertEquals(setOf("a"), afterB)
    }

    @Test
    fun `only answered ids leave the queue`() {
        assertEquals(
            setOf("b", "d"),
            GuideRequestQueue.clearAnswered(
                pending = setOf("a", "b", "c", "d"),
                resolved = setOf("a", "c")
            )
        )
    }

    @Test
    fun `an empty answer leaves the queue untouched`() {
        val pending = setOf("a", "b")
        assertEquals(pending, GuideRequestQueue.clearAnswered(pending, emptySet()))
    }

    @Test
    fun `a fully answered queue drains to empty`() {
        assertEquals(
            emptySet<String>(),
            GuideRequestQueue.clearAnswered(setOf("a", "b"), resolved = setOf("a", "b"))
        )
    }

    // ------------------------------------------------------------- guide clock

    // The tick that keeps NOW/NEXT current used to re-issue the whole loaded
    // batch: a lineup query for up to 80 channels x 960 programs every two
    // minutes, for as long as the guide was open. Almost all of it answered a
    // question the loaded programs already could, so the cases below are the
    // boundary where that stops being true - the one place a query is owed.

    /** A one-hour program starting at [hour]:00 on the pinned test day. */
    private fun hourProgram(channelId: String, hour: Int): XmltvProgram {
        val start = utc(2026, 10, 7, hour, 0)
        return XmltvProgram(
            channelId = channelId,
            title = "P$hour",
            description = null,
            category = null,
            startUtcMillis = start,
            endUtcMillis = start + 3_600_000L
        )
    }

    /** One loaded guide row, in the shape `IptvRepository.mapChannels` leaves it. */
    private fun row(
        channelId: String,
        now: XmltvProgram?,
        next: XmltvProgram?,
        upcoming: List<XmltvProgram> = emptyList()
    ): IptvChannelWithEpg = IptvChannelWithEpg(
        channel = IptvChannel(
            id = channelId,
            name = channelId,
            displayName = channelId,
            streamUrl = "http://example.invalid/$channelId",
            groupTitle = null,
            logoUrl = null,
            tvgId = null,
            tvgName = null,
            tvgChno = null,
            catchup = null,
            catchupDays = null,
            catchupSource = null,
            providerChannelId = null
        ),
        epgChannel = XmltvChannel(id = channelId),
        epgMatchType = EpgMatchType.ID_MATCH,
        now = now,
        next = next,
        upcoming = upcoming
    )

    @Test
    fun `a tick inside a program moves nothing at all`() {
        // 20:40, with 20:00's program on air and the two after it loaded. This
        // is every tick but one per program: no row is rewritten and nothing is
        // sent back to the database.
        val loaded = mapOf(
            "ch1" to row(
                "ch1",
                now = hourProgram("ch1", 20),
                next = hourProgram("ch1", 21),
                upcoming = listOf(hourProgram("ch1", 22))
            )
        )
        val step = GuideClockAdvance.step(loaded, utc(2026, 10, 7, 20, 40))
        assertTrue("an unchanged row must not be rewritten", step.advanced.isEmpty())
        assertTrue("nothing has run out", step.expiredIds.isEmpty())
    }

    @Test
    fun `a tick past a program's end promotes the loaded next in memory`() {
        // 21:05. The 21:00 program was already loaded as NEXT; it becomes NOW
        // and the strip behind it shifts up, with no query at all.
        val loaded = mapOf(
            "ch1" to row(
                "ch1",
                now = hourProgram("ch1", 20),
                next = hourProgram("ch1", 21),
                upcoming = listOf(hourProgram("ch1", 22))
            )
        )
        val step = GuideClockAdvance.step(loaded, utc(2026, 10, 7, 21, 5))
        assertEquals("the database is not needed", emptySet<String>(), step.expiredIds)
        val moved = step.advanced.getValue("ch1")
        assertEquals("P21", moved.now?.title)
        assertEquals("P22", moved.next?.title)
        assertEquals(emptyList<String>(), moved.upcoming.map { it.title })
    }

    @Test
    fun `a row whose next program has ended is reported as run out`() {
        // The one state a tick cannot answer: the newest program the row holds
        // started before the clock reached it, so nothing left in memory says
        // what is next. It is still advanced - here to no program at all, since
        // the 20:00 one finished 40 minutes ago - so the row stops claiming a
        // finished program is on air while the query is out.
        val loaded = mapOf(
            "ch1" to row("ch1", now = hourProgram("ch1", 20), next = null)
        )
        val step = GuideClockAdvance.step(loaded, utc(2026, 10, 7, 21, 40))
        assertEquals(setOf("ch1"), step.expiredIds)
        assertNull(step.advanced.getValue("ch1").now)
    }

    @Test
    fun `a row that never had a program is left alone, not re-queried`() {
        // An unmatched channel, or a match with no programs in the window.
        // "No program data" is a settled answer rather than a stale one, and
        // asking for it twice a minute is the churn this path removes - a new
        // import or playlist bump re-requests the window for it anyway.
        val loaded = mapOf("ch1" to row("ch1", now = null, next = null))
        val step = GuideClockAdvance.step(loaded, utc(2026, 10, 7, 20, 40))
        assertTrue(step.advanced.isEmpty())
        assertTrue(step.expiredIds.isEmpty())
    }

    @Test
    fun `a run-out row is only asked again once it can answer the clock`() {
        // What the ViewModel writes a channel off with. False while the row is
        // stuck at the end of its listing, true the moment a re-query brings
        // back a program that has not started yet - which is how a channel that
        // recovered leaves that set without anything having to track it.
        val runOut = row("ch1", now = hourProgram("ch1", 20), next = null)
        assertFalse(GuideClockAdvance.canAnswer(runOut, utc(2026, 10, 7, 20, 40)))
        assertTrue(GuideClockAdvance.canAnswer(runOut, utc(2026, 10, 7, 19, 40)))

        val refreshed = row(
            "ch1",
            now = hourProgram("ch1", 20),
            next = hourProgram("ch1", 21)
        )
        assertTrue(GuideClockAdvance.canAnswer(refreshed, utc(2026, 10, 7, 20, 40)))
    }

    @Test
    fun `one tick asks only for the rows that ran out`() {
        // The point of the whole thing at 21:05: three loaded channels, two of
        // them moved by the clock in memory, and one query - for the single
        // channel at the end of its listing.
        val loaded = mapOf(
            // Already reading the 21:00 program: nothing to do.
            "ch1" to row(
                "ch1",
                now = hourProgram("ch1", 21),
                next = hourProgram("ch1", 22)
            ),
            // Still reading 20:00 as NOW: advanced here, no query.
            "ch2" to row(
                "ch2",
                now = hourProgram("ch2", 20),
                next = hourProgram("ch2", 21),
                upcoming = listOf(hourProgram("ch2", 22))
            ),
            // At the end of its listing: the only channel asked about.
            "ch3" to row("ch3", now = hourProgram("ch3", 20), next = null)
        )
        val step = GuideClockAdvance.step(loaded, utc(2026, 10, 7, 21, 5))
        assertEquals(setOf("ch3"), step.expiredIds)
        assertEquals(setOf("ch2", "ch3"), step.advanced.keys)
        assertNull("a run-out row does not keep a program that ended", step.advanced.getValue("ch3").now)
    }

    @Test
    fun `focusing a chip selects it even mid move-to-list transit`() {
        // Down sets moveFocusToChannelList=true, then Left/Right lands on an
        // adjacent chip before focus leaves the row. selectedGroup must follow
        // the focused chip (the old guard swallowed it) and the pending transit
        // must be cancelled by the same update.
        val state = chipFocusState(
            focusedGroup = "Sports",
            moveFocusToChannelList = true
        )
        assertEquals("Sports", state.selectedGroup)
        assertFalse(state.moveFocusToChannelList)
    }

    @Test
    fun `a chip focus walk with no pending transit still follows focus`() {
        val state = chipFocusState(
            focusedGroup = "News",
            moveFocusToChannelList = false
        )
        assertEquals("News", state.selectedGroup)
        assertFalse(state.moveFocusToChannelList)
    }

    @Test
    fun `chips refuse focus while a move-to-list transit is pending`() {
        // The reported walk bug: a Left/Right from the channel list changes the
        // group, the keyed rows swap, focus is cleared for a frame, and default
        // resolution landed it on the first chip -- whose onFocus adopted that
        // chip's group and reset the walk to "All". Refusing focus for the
        // duration of the transit makes the re-anchor the only thing that runs.
        assertFalse(chipRowAcceptsFocus(listTransitPending = true))
    }

    @Test
    fun `chips take focus again once no transit is pending`() {
        // Both deliberate chip paths must still work: walking the chips row,
        // and Up from the list's top row (which sets its own flag, not this
        // one).
        assertTrue(chipRowAcceptsFocus(listTransitPending = false))
    }

    @Test
    fun `a program hit key is unique across overlapping EPG entries`() {
        // Same channel + same start, different end: two EPG sources for one
        // channel. The old channel|start key collided and Compose threw
        // "Key ... was already used" (Sentry ANDROID-S). An exact duplicate row
        // is separated only by the index.
        val keys = listOf(
            guideProgramHitKey("ch1", 1000L, 2000L, 0),
            guideProgramHitKey("ch1", 1000L, 3000L, 1),
            guideProgramHitKey("ch1", 1000L, 2000L, 2)
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a program hit key carries the channel, window and index`() {
        assertEquals(
            "program|ch9|1000|2000|7",
            guideProgramHitKey("ch9", 1000L, 2000L, 7)
        )
    }

    @Test
    fun `a hidden row hands its place to the row that takes it`() {
        // Hiding the 12th channel of the list: the row that slides into slot 12
        // is the one the viewer's eyes are already on. Resetting to the first
        // row here is what scrolled the guide back to its top.
        assertEquals(12, inheritedChannelRowIndex(removedAt = 12, newSize = 40))
    }

    @Test
    fun `a hidden row at the end hands its place to its neighbour`() {
        // The removed row was last: the anchor clamps to the new last row, not
        // out of bounds and not back to the top.
        assertEquals(9, inheritedChannelRowIndex(removedAt = 10, newSize = 10))
        assertEquals(0, inheritedChannelRowIndex(removedAt = 1, newSize = 1))
    }

    @Test
    fun `a hidden first row leaves the selection at the top`() {
        assertEquals(0, inheritedChannelRowIndex(removedAt = 0, newSize = 5))
    }

    @Test
    fun `an anchor that cannot be placed starts at the top`() {
        // -1: the selection was never in the previous list (a group switch, a
        // playlist reload). There is no vicinity to keep, so it starts at the
        // top - which is also the old behaviour, and right for the cases this
        // rule does not cover.
        assertEquals(0, inheritedChannelRowIndex(removedAt = -1, newSize = 5))
    }

    @Test
    fun `an empty list has no row to inherit`() {
        assertNull(inheritedChannelRowIndex(removedAt = 0, newSize = 0))
        assertNull(inheritedChannelRowIndex(removedAt = 3, newSize = 0))
    }

    // ── whose Back a press is, in the search overlay ───────────────────────────

    @Test
    fun `a Back while the keyboard is still up belongs to the keyboard`() {
        assertTrue(searchBackClosesKeyboard(editing = true, sinceEditingChangeMs = 0L))
        assertTrue(searchBackClosesKeyboard(editing = true, sinceEditingChangeMs = 60_000L))
    }

    @Test
    fun `a Back on the heels of the keyboard closing is still the keyboard's`() {
        // Fire OS closes its keyboard AND hands the app the press, and the two
        // arrive in either order: one before the field's session ends (caught by
        // editing), one after it (caught here). Both must leave the results up.
        assertTrue(searchBackClosesKeyboard(editing = false, sinceEditingChangeMs = 0L))
        assertTrue(
            searchBackClosesKeyboard(
                editing = false,
                sinceEditingChangeMs = SEARCH_IME_ECHO_MS - 1
            )
        )
    }

    @Test
    fun `a Back with the keyboard genuinely gone closes the search`() {
        assertFalse(
            searchBackClosesKeyboard(
                editing = false,
                sinceEditingChangeMs = SEARCH_IME_ECHO_MS
            )
        )
        assertFalse(searchBackClosesKeyboard(editing = false, sinceEditingChangeMs = 30_000L))
    }

    @Test
    fun `a session that never reported leaves the first Back to the overlay`() {
        // The clock starts at zero, so "never reported" reads as an enormous
        // gap - and a negative gap (a clock that moved back) must not be read as
        // "just changed" either: neither is an echo.
        val neverReported = System.currentTimeMillis()
        assertFalse(searchBackClosesKeyboard(editing = false, sinceEditingChangeMs = neverReported))
        assertFalse(searchBackClosesKeyboard(editing = false, sinceEditingChangeMs = -5L))
    }
}
