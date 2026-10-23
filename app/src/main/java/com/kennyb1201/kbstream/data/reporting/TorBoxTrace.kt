package com.kennyb1201.kbstream.data.reporting

/**
 * What the TorBox key last did, in the words the diagnostics report prints.
 *
 * Both TorBox features fail SOFT and silent by design: a rejected key, a request
 * that never lands, or a source list with no infohash in it all end the same way
 * - no Cached chip, no library rows, no message. That is the right behaviour for
 * a cosmetic chip (nothing should block playback over it) and the wrong
 * behaviour for the viewer, who is left with "I never see a Cached chip" and no
 * way to tell a bad key from a title whose add-ons already handed over resolved
 * CDN links.
 *
 * The release APK carries no logcat, and a TV has no way to read one, so the
 * last few answers are kept here and printed by [Diagnostics]. Ids, hashes and
 * the key are never recorded - only counts and the class of the failure.
 */
internal object TorBoxTrace {

    /** Answers kept. One fetch is one or two lines; this is a short ring. */
    private const val MAX_LINES = 8

    private val lock = Any()

    @Volatile
    private var lines: List<String> = emptyList()

    /** Appends one line, dropping the oldest past [MAX_LINES]. */
    fun note(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) { lines = (lines + trimmed).takeLast(MAX_LINES) }
    }

    /** The recent lines, oldest first. Empty until the key has been used. */
    fun lines(): List<String> = lines

    /** Clears the ring (tests). */
    fun reset() {
        synchronized(lock) { lines = emptyList() }
    }
}
