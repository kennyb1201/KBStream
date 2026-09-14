package com.kennyb1201.kbstream.data.iptv

/**
 * Resolves Xtream-style catch-up (DVR) URL templates against a program's
 * air window. Providers advertise a template on the M3U entry
 * (`catchup="..."` / `catchup-source="..."`) with placeholder tokens that
 * get substituted with the requested broadcast's times.
 *
 * Supported token families (the de-facto provider set):
 *  - ${'$'}{start} ${'$'}{timestamp} {utc} ${'$'}{b}   -> program start, epoch SECONDS
 *  - ${'$'}{end} {utcend} ${'$'}{e}                    -> program end, epoch SECONDS
 *  - ${'$'}{offset:N} / ${'$'}{offset:-N}              -> now +/- N seconds
 *  - (b)N                                              -> now - N seconds
 *  - ${'$'}{yyyy} ${'$'}{mm} ${'$'}{dd} ${'$'}{hh} ${'$'}{MM} ${'$'}{ss} -> date pieces of the START time (UTC)
 *
 * Returns null when the template carries no recognized token — substituting
 * nothing would produce a URL that does not actually point at the requested
 * broadcast, so the caller must treat the channel as non-catchup instead.
 */
object CatchupUrls {

    fun build(
        template: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
        nowUtcMillis: Long = System.currentTimeMillis()
    ): String? {
        if (template.isBlank()) return null

        val dollar = "$"
        val startSec = startUtcMillis / 1000L
        val endSec = endUtcMillis / 1000L
        var out = template

        // Start-time tokens (epoch seconds).
        out = out
            .replace("$dollar{start}", startSec.toString())
            .replace("$dollar{timestamp}", startSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("$dollar{b}", startSec.toString())

        // End-time tokens (epoch seconds).
        out = out
            .replace("$dollar{end}", endSec.toString())
            .replace("{utcend}", endSec.toString())
            .replace("$dollar{e}", endSec.toString())

        // Relative offsets from now, in seconds.
        out = Regex(Regex.escape("$dollar") + "\\{offset:(-?\\d+)}").replace(out) { match ->
            (nowUtcMillis / 1000L + match.groupValues[1].toLong()).toString()
        }
        out = Regex("\\(b\\)(\\d+)").replace(out) { match ->
            (nowUtcMillis / 1000L - match.groupValues[1].toLong()).toString()
        }

        // Formatted date pieces of the program start (UTC — providers that
        // use these tokens index their DVR by UTC broadcast time).
        val start = java.time.Instant.ofEpochMilli(startUtcMillis)
            .atZone(java.time.ZoneOffset.UTC)
        out = out
            .replace("$dollar{yyyy}", "%04d".format(start.year))
            .replace("$dollar{mm}", "%02d".format(start.monthValue))
            .replace("$dollar{dd}", "%02d".format(start.dayOfMonth))
            .replace("$dollar{hh}", "%02d".format(start.hour))
            .replace("$dollar{MM}", "%02d".format(start.minute))
            .replace("$dollar{ss}", "%02d".format(start.second))

        return if (out != template) out else null
    }

    /** How many days of DVR the channel advertises (0 = unknown/unlimited). */
    fun daysSupported(catchupDays: String?): Int =
        catchupDays?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** True when the given program's start is within the channel's DVR window. */
    fun withinDvrWindow(
        catchupDays: String?,
        programStartUtcMillis: Long,
        nowUtcMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val days = daysSupported(catchupDays)
        if (days <= 0) return true // no advertised window — let the provider decide
        return nowUtcMillis - programStartUtcMillis <= days * 86_400_000L
    }
}
