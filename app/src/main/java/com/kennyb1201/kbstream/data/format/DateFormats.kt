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

    /** `2:05 PM` - a fixed 12-hour clock (guide program times). */
    val CLOCK_12H: DateTimeFormatter = us("h:mm a")

    /** `Tue, 2:05 PM` - the guide's clock label. */
    val WEEKDAY_CLOCK_12H: DateTimeFormatter = us("EEE, h:mm a")

    // ── Locale-aware clocks ─────────────────────────────────────────────
    // The viewer's own clock, so a locale's own day/month names apply. Kept as
    // factories because the pattern must be rebuilt per locale; callers hold
    // the result (e.g. in `remember`) rather than calling these per frame.
    fun clock12h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", locale)

    fun clock24h(locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", locale)

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
