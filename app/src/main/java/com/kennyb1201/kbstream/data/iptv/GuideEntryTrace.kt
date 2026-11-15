package com.kennyb1201.kbstream.data.iptv

import android.os.SystemClock
import com.kennyb1201.kbstream.data.reporting.GuideTrace
import com.kennyb1201.kbstream.data.reporting.PerfTrace

/**
 * How long the guide takes to put its first programs on screen (spec 2026-11-15,
 * guide grid entry F1).
 *
 * There was no such measurement anywhere in the tree - not a `guide.*` label,
 * not a count - so "the guide is slow to open" could not be answered at all. The
 * grid sits on a 102-120 MB EPG database with 15,000+ channels and tens of
 * thousands of programs in the window, and the spec is right that the size
 * decides nothing: a database that answers in milliseconds is not a cost. This
 * times the thing the viewer waits for, and prints what the entry actually did.
 *
 * What it records, and why each part is there:
 *
 *  - `guide.entry` = screen composed → the first window answer that carries
 *    programs. Not the first ANSWER: a channel with no EPG in this window
 *    answers empty, and counting that would report a fast entry for a grid that
 *    is still blank.
 *  - the counts it built (channels asked for, programs returned) - a fast entry
 *    over two rows and a slow one over thirty are different facts.
 *  - `scope=window` - the entry query is scoped to the six-hour span it draws
 *    (`getProgramsForChannelsInWindow`), not the whole database. Recorded so the
 *    next paste cannot be read as "it loads everything" by assumption.
 *  - `match=entry-cached` - the guide match DOES run on entry, over the batch,
 *    but from the cached match table first (see IptvRepository's
 *    loadGuideGridWindow: loadCachedMatches, then resolveMatchesMulti for the
 *    rest). Which of the two a slow entry spent its time in is the difference
 *    between moving the match to import time and leaving it alone.
 *
 * The figure also goes into [PerfTrace] under the same label, and into
 * [GuideTrace] as a line, so it travels in the guide section of COPY
 * DIAGNOSTICS even though [GuideTrace] is a sixteen-line ring and [PerfTrace] a
 * four-hundred-sample one.
 *
 * One entry is measured once. An entry that never draws a program (no EPG in
 * this window) records nothing rather than a made-up zero, which is why the
 * report stays silent on a guide with no data at all.
 */
internal object GuideEntryTrace {

    private val lock = Any()

    /** When the guide screen was composed, or 0 when no entry is open. */
    private var entryAtMs = 0L

    /** Guards the one-shot record: later window answers belong to a scroll. */
    private var reported = false

    /** Stamps the entry, once per guide open. */
    fun markEntry(atMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            entryAtMs = atMs
            reported = false
        }
    }

    /**
     * Called with the first window answer that carries programs, closing the
     * entry leg. A no-op when no entry is open or it has already been measured.
     */
    fun onFirstRows(
        channels: Int,
        programs: Int,
        atMs: Long = SystemClock.elapsedRealtime()
    ) {
        if (programs <= 0) return
        val leg = synchronized(lock) {
            val startedAt = entryAtMs
            if (startedAt <= 0L || reported) return
            reported = true
            (atMs - startedAt).coerceAtLeast(0L)
        }
        PerfTrace.record("guide.entry", leg)
        GuideTrace.note(
            "guide.entry first-row=${leg}ms channels=$channels programs=$programs " +
                "scope=window match=entry-cached"
        )
    }
}
