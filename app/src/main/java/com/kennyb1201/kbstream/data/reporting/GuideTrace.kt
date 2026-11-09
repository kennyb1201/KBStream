package com.kennyb1201.kbstream.data.reporting

/**
 * What the guide's focus last did, in the words the diagnostics report prints.
 *
 * The guide's focus machinery fails INVISIBLY by design: a handoff that gives
 * up, an entry grab that never arms, a stand-down in favour of an in-flight
 * handoff and a sidebar collapse all end the same way - the press is consumed
 * and nothing moves, or the sidebar stays where it was. That is the right
 * behaviour for focus code (no error to show, playback is unaffected) and the
 * wrong one for the viewer, who reports "the first press does nothing" with no
 * way to say whether the grab landed, exhausted, or never ran at all.
 *
 * The release APK carries no logcat, and a TV has no way to read one, so the
 * last few answers are kept here and printed by [Diagnostics] - the same shape
 * as [TorBoxTrace], because the guide's GUIDE lines already existed in logcat
 * with no way to travel.
 *
 * Row indices, group labels, attempt counts and landed/exhausted only - never a
 * channel id, a program title or anything else that names the viewer's lineup.
 *
 * The ring is process-wide and is NOT cleared when the guide is left, the view
 * toggled or the profile switched: the report is taken from Settings AFTER the
 * repro, so the lines have to still be there. Only [reset] (tests) clears it.
 */
internal object GuideTrace {

    /** Lines kept. One guide entry is one to three lines; this is a short ring. */
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

    /** The recent lines, oldest first. Empty until the guide has been opened. */
    fun lines(): List<String> = lines

    /** Clears the ring (tests). */
    fun reset() {
        synchronized(lock) { lines = emptyList() }
    }
}
