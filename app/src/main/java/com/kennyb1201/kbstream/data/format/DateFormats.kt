package com.kennyb1201.kbstream.data.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The date and time formats the app shows, in one place.
 *
 * Before this file every screen carried its own pattern string: `"EEE, MMM d"`
 * existed in both the up-next rules and the view model, `"MMM d, yyyy"` twice
 * in the TMDB models, `"h:mm a"` in the guide and again in the player, and the
 * diagnostics report and the backup filename each had a private copy. A pattern
 * is a decision - "Sep 30", not "30/09" - and a copy per call site is how two
 * screens end up disagreeing about how the same date reads.
 *
 * Every entry here is an immutable [java.time.format.DateTimeFormatter], not a
 * `SimpleDateFormat`. That matters beyond style: `SimpleDateFormat` is mutable
 * and not thread-safe, so a shared instance is only ever safe on one thread,
 * and the old code had to say so in a comment each time. A `DateTimeFormatter`
 * is safe to hold in a `val` and call from anywhere.
 *
 * Locale is `Locale.US` for the fixed patterns on purpose - a stored or logged
 * date must not change shape with the device locale - and [Locale.getDefault]
 * only for the two formats the viewer reads as a clock, where a local day/month
 * name is what they expect.
 */
object DateFormats {

    private fun us(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.US)

    // ── Date-only labels ────────────────────────────────────────────────
    /** `Tue, Sep 30` - an air date on an up-next or upcoming rail. */
    val AIR_DATE: DateTimeFormatter = us("EEE, MMM d")

    /** `Sep 30, 2026` - a birth/death day, or a release date. */
    val MONTH_DAY_YEAR: DateTimeFormatter = us("MMM d, yyyy")

    /** `09/30/2026` - the short numeric date used inside detail rows. */
    val DISPLAY_DATE: DateTimeFormatter = us("MM/dd/yyyy")

    /** `20260930` - the day key the kids-time guard counts against. */
    val COMPACT_DAY: DateTimeFormatter = us("yyyyMMdd")

    /** `2026-09-30_140512` - a filename-safe stamp for backups and exports. */
    val FILE_STAMP: DateTimeFormatter = us("yyyy-MM-dd_HHmmss")

    /** `Tue` - a bare weekday name. */
    val WEEKDAY: DateTimeFormatter = us("EEE")

    // ── Times and timestamps ────────────────────────────────────────────
    /** `14:05:12` - a log line time, always 24-hour. */
    val TIME_SECONDS: DateTimeFormatter = us("HH:mm:ss")

    /** `2026-09-30 14:05:12` - a full log or diagnostics timestamp. */
    val DATE_TIME_SECONDS: DateTimeFormatter = us("yyyy-MM-dd HH:mm:ss")

    /** `2:05 PM` - the 12-hour half of [clock]'s pair (guide program times). */
    val CLOCK_12H: DateTimeFormatter = us("h:mm a")

    /** `14:05` - the 24-hour half of [clock]'s pair. */
    val CLOCK_24H: DateTimeFormatter = us("HH:mm")

    /** `Tue, 2:05 PM` - the guide's clock label. */
    val WEEKDAY_CLOCK_12H: DateTimeFormatter = us("EEE, h:mm a")

    /**
     * `Tue, Sep 30 · 2:05 PM` - a header clock that names the day and the date.
     *
     * The guide and the sports hub sit in front of a viewer for a whole evening,
     * so their clocks carry the day as well as the minute. The separator is a
     * middle dot, not a comma: the day and the date are one label and the time is
     * the other, and a second comma made `Tue, Sep 30, 2:05 PM` read like a
     * sentence that had lost its verb.
     */
    val WEEKDAY_DATE_CLOCK_12H: DateTimeFormatter = us("EEE, MMM d · h:mm a")

    /** `Oct 20, 6:30 PM` - when a schedule stopped, in a diagnostics/status line. */
    val MONTH_DAY_CLOCK_12H: DateTimeFormatter = us("MMM d, h:mm a")

    // ── Locale-aware clocks ─────────────────────────────────────────────
    // The viewer's own clock, so a locale's own day/month names apply. Kept as
    // factories because the pattern must be rebuilt per locale; callers hold
    // the result (e.g. in `remember`) rather than calling these per frame.
    fun clock12h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", locale)

    fun clock24h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", locale)

    /**
     * `14:05` or `2:05 PM`, whichever the viewer picked in settings.
     *
     * Every clock the app shows goes through one of the `*Clock(use24Hour)`
     * factories below, all taking the same flag: the setting is one answer, and
     * a surface that formats its own time is how the guide and the sports hub
     * came to disagree about the same evening. The flag is read once per screen
     * (see `AppPreferences.getUse24HourClock`) and passed down, so a toggle is a
     * value the layout already has rather than a global a pure rule has to
     * reach for.
     */
    fun clock(use24Hour: Boolean, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        if (use24Hour) clock24h(locale) else clock12h(locale)

    /** `Tue, 14:05` / `Tue, 2:05 PM` - a weekday in front of the clock. */
    fun weekdayClock(use24Hour: Boolean, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (use24Hour) "EEE, HH:mm" else "EEE, h:mm a", locale)

    /** `Tue, Sep 30 · 14:05` / `Tue, Sep 30 · 2:05 PM`. */
    fun dayClock(use24Hour: Boolean, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        if (use24Hour) dayClock24h(locale) else dayClock12h(locale)

    /** `Oct 20, 18:30` / `Oct 20, 6:30 PM` - a month and day in front of the clock. */
    fun monthDayClock(
        use24Hour: Boolean,
        locale: Locale = Locale.getDefault()
    ): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (use24Hour) "MMM d, HH:mm" else "MMM d, h:mm a", locale)

    /**
     * The viewer's own clock with the day and date in front of it, in either
     * convention - the sports hub's header reads the app's 12/24-hour toggle, so
     * it needs both shapes (see [WEEKDAY_DATE_CLOCK_12H] for the fixed one).
     */
    fun dayClock12h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", locale)

    fun dayClock24h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", locale)

    fun longDate(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM d, yyyy", locale)

    // ── Formatting helpers ──────────────────────────────────────────────
    /** Formats an epoch-millis instant in [zone] (the device's own by default). */
    fun time(
        millis: Long,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault()
    ): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(formatter)

    /** Formats the current wall-clock time in [zone]. */
    fun now(
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault()
    ): String =
        java.time.ZonedDateTime.now(zone).format(formatter)
}
