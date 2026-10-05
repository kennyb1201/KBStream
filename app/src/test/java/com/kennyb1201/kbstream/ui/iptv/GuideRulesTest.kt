package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
