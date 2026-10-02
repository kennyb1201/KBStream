package com.kennyb1201.kbstream.ui.player

/**
 * Reads mpv's error log for the one line that explains a failure to open, and
 * formats the lines the way the notice shows them.
 *
 * Why this exists: mpv reports a failed open on its LOUDEST line, which is
 * FFmpeg's generic "Failed to open <url>" — the same sentence for a 403, a
 * refused connection, a DNS failure and an unsupported protocol, and those want
 * four different fixes. The line that names the cause is logged just before it
 * (an "HTTP error 403", say) and it used to be thrown away, because only the
 * last line was kept. That is why the failure card could show a stream URL and
 * nothing else useful.
 *
 * A plain object, so the selection is unit-testable: no Android, no mpv.
 */
internal object MpvErrorReason {

    /** How many of mpv's error lines are kept while looking for the cause. */
    const val MAX_KEPT = 4

    /**
     * Phrases that name a CAUSE.
     *
     * Deliberately excludes the generic "Failed to open", which is the line
     * this exists to look past. Bare status digits are avoided too: a signed
     * stream URL is full of numbers, and matching those would prefer a URL over
     * the actual error.
     */
    private val SPECIFIC = listOf(
        "http error",
        "server returned",
        "protocol not found",
        "unsupported protocol",
        "connection refused",
        "connection reset",
        "timed out",
        "timeout",
        "no route to host",
        "could not resolve",
        "name or service not known",
        "forbidden",
        "unauthorized",
        "invalid data found"
    )

    /**
     * One mpv log line as the notice should read it.
     *
     * The library hands the prefix and the text separately; joining them
     * without a space is what put "streamFailed to open …" on screen.
     */
    fun format(prefix: String, text: String): String {
        val head = prefix.trim()
        val body = text.trim()
        return if (head.isEmpty()) body else "$head $body".trim()
    }

    /**
     * The line worth showing: the LAST line that names a cause (nearest the
     * failure, which is what matters when a stream was retried), else the last
     * line there is, else null when mpv said nothing.
     */
    fun pick(lines: List<String>): String? {
        val kept = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null

        return kept.lastOrNull { line ->
            val lower = line.lowercase()
            SPECIFIC.any { phrase -> lower.contains(phrase) }
        } ?: kept.last()
    }
}
