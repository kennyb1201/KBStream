package com.kennyb1201.kbstream.data.format

import java.io.File
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide and sports-hub header clocks name the day and the date, not only the
 * time.
 *
 * Both headers sit in front of a viewer for an evening. A clock that reads
 * `Tue, 2:05 PM` at 00:05 is not a smaller version of the right label, it is the
 * wrong one - and the guide's own clock was missing the date entirely.
 *
 * The patterns are pinned to a fixed instant and zone so the strings below are
 * the assertion, not a description of one: a format is a decision, and this is
 * the decision (spec-free, reported by the field 2026-11-25).
 */
class HeaderClockDateContractTest {

    /** 2026-09-30T14:05Z - a Wednesday in the middle of a week, so both fields move. */
    private val instant = 1790777100000L
    private val utc = ZoneId.of("UTC")

    @Test
    fun `the fixed header clock reads day, date and time`() {
        assertEquals(
            "Wed, Sep 30 · 2:05 PM",
            DateFormats.time(instant, DateFormats.WEEKDAY_DATE_CLOCK_12H, utc)
        )
    }

    @Test
    fun `the viewer's own header clock reads day, date and time in both conventions`() {
        assertEquals(
            "Wed, Sep 30 · 2:05 PM",
            DateFormats.time(instant, DateFormats.dayClock12h(Locale.US), utc)
        )
        assertEquals(
            "Wed, Sep 30 · 14:05",
            DateFormats.time(instant, DateFormats.dayClock24h(Locale.US), utc)
        )
    }

    @Test
    fun `the guide header ticks the dated label in the viewer's convention`() {
        val guide = read("ui/iptv/GuideScreen.kt")
        val label = guide.substringAfter("private fun rememberCurrentTimeLabel(use24Hour: Boolean): String {")
            .substringBefore("private fun ChannelActionsDialog")
        assertTrue(
            "both the initial value and every tick must format the dated label, " +
                "in the shape the 24-hour setting asks for",
            label.contains("DateFormats.dayClock(use24Hour)") &&
                !label.contains("DateFormats.WEEKDAY_CLOCK_12H")
        )
        assertTrue(
            "and the toggle has to re-render the label rather than wait for the " +
                "next minute to redraw it",
            label.contains("LaunchedEffect(formatter)")
        )
    }

    @Test
    fun `the sports hub header reads the dated clock in the viewer's own convention`() {
        val hub = read("ui/sports/SportsHubScreen.kt")
        val clock = hub.substringAfter("private fun SportsHeaderClock(")
            .substringBefore("private fun HeaderIconButton(")
        assertTrue(
            "the 12/24-hour toggle must pick the dated shape of each clock",
            clock.contains("DateFormats.dayClock24h()") &&
                clock.contains("DateFormats.dayClock12h()")
        )
        assertTrue(
            "and the reserved width must cover the longer label, or the tick " +
                "resizes the header",
            hub.contains("private val SPORTS_CLOCK_MIN_WIDTH = 196.dp")
        )
    }

    @Test
    fun `the player overlay's clock carries the day and the date too`() {
        val exo = read("ui/player/NativePlayerActivity.kt")
        assertTrue(
            "the activity needs the dated pair, one per convention",
            exo.contains("private val dayClock12Format by lazy { DateFormats.dayClock12h() }") &&
                exo.contains("private val dayClock24Format by lazy { DateFormats.dayClock24h() }")
        )
        val body = exo.substringAfter("private fun updateClock()")
            .substringBefore("private fun renderSourceBadges()")
        assertTrue(
            "the wall clock is the dated pair, chosen by the setting",
            body.contains("val formatter = if (use24Hour) dayClock24Format else dayClock12Format")
        )
        assertTrue(
            "the Ends at estimate stays a bare time: it is a duration away from a " +
                "clock that already names the day",
            body.contains("val endsFormatter = if (use24Hour) clock24Format else clock12Format") &&
                body.contains("DateFormats.time(System.currentTimeMillis() + remainingMs, endsFormatter)")
        )
    }

    @Test
    fun `the shared chrome draws the dated clock for both engines`() {
        val chrome = read("ui/player/PlayerChrome.kt")
        val body = chrome.substringAfter("private fun refreshClock(positionMs: Long, durationMs: Long) {")
            .substringBefore("companion object")
        assertTrue(
            "the chrome is the MPV engine's only clock, so it must be dated too, " +
                "or the two engines disagree about the same row",
            body.contains("val formatter = if (use24Hour) dayClock24Format else dayClock12Format") &&
                body.contains("DateFormats.time(System.currentTimeMillis() + remainingMs, endsFormatter)")
        )
    }

    @Test
    fun `the phone guide header leads with the dated clock`() {
        val mobile = read("ui/mobile/GuideScreenMobile.kt")
        assertTrue(
            "the phone header is the same clock as the TV one, not a bare time",
            mobile.contains("DateFormats.dayClock(use24Hour)")
        )
        val header = mobile.substringAfter("text = buildList {")
            .substringBefore("}.joinToString(\"  ·  \")")
        assertTrue(
            "and it is first, so a long group name cannot push the date off the line",
            header.indexOf("clock.format(Date(nowMs))") < header.indexOf("channelCount channels")
        )
    }

    @Test
    fun `the undated pattern keeps meaning what the schedule rows expect`() {
        // `WEEKDAY_CLOCK_12H` is still the schedule's own label ("Tue, 2:05 PM").
        // The header got a new pattern instead, so a card's time did not grow a
        // date it has no room for.
        assertEquals("Wed, 2:05 PM", DateFormats.time(instant, DateFormats.WEEKDAY_CLOCK_12H, utc))
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
