package com.kennyb1201.kbstream.data.iptv

/**
 * When the stored guide must be re-imported on ENTRY — the decision behind
 * "a profile with guide sources never goes blank", made pure so it can be
 * tested without a ViewModel, a database or a clock.
 *
 * The field case this comes from (2026-10-25): Kenny's own playlist on
 * Profile 1 read completely blank and a manual reimport fixed it. Nothing was
 * broken — the source list was intact and the import path worked — the guide
 * had simply aged past the window while every automatic attempt was deferred
 * (the background pass is active-profile-only, deferrable by playback and Doze,
 * and cancelled outright after a run of empty passes). The age gate the entry
 * path used (`EPG_REFRESH_MS`, 12 hours) is what let that happen: against a
 * 20-hour stored window it leaves only 8 hours of schedule on screen even when
 * it fires on time, and it says nothing at all about a guide whose window
 * already ended.
 *
 * So the signal that matters is the END OF THE WINDOW, not the age of the last
 * import. Age stays as a secondary signal for the cases window end cannot see:
 * a guide that holds nothing at all, and a partial import that left one source
 * un-refreshed while its siblings kept the window far ahead.
 *
 * [LEAD_MS] is the size of the warning: a refresh is started while this much
 * window is still left, so even a deferred one lands before the grid thins.
 * It is one read window plus one refresh interval — the same slack the stored
 * window itself is built from.
 */
internal object GuideRefreshPolicy {

    /**
     * How much window may be left before an entry refresh starts: the read
     * window (8h) plus one refresh interval (6h).
     *
     * Derived, never a literal: changing [EpgWindow]'s halves has to move this
     * with them, or the two drift the way the stored window once drifted away
     * from the rendered one.
     */
    const val LEAD_MS = EpgWindow.READ_FUTURE_MS + EpgWindow.REFRESH_INTERVAL_MS

    /** How badly the guide needs an import, if at all. */
    enum class Urgency {
        /** The window still reaches past [LEAD_MS] and nothing says otherwise. */
        NONE,

        /** The window is closing: start a refresh now, so it lands in time. */
        DUE,

        /**
         * The grid is thinning (or already past its end): import on entry even
         * while something is playing.
         *
         * The in-app import is never deferred by playback — only its SAVE phase
         * is paced (see `EpgWriteGate`) — so this is the distinction that says
         * out loud what happened, in the log and in the status line, rather
         * than a second gate that could suppress it.
         */
        URGENT,
    }

    /**
     * What to do on entry, given where the stored guide stops.
     *
     * [endsAtMs] is the newest program end the guide holds — null when it holds
     * no programs, which is NOT treated as an emergency: a feed that is empty
     * on purpose (an `EmptyFeedKeptLive` pass) would otherwise start an import
     * on every single entry, and that is the loop the stall cooldown exists to
     * prevent. An empty guide therefore falls back to [ageStale], exactly as
     * before.
     *
     * [ageStale] is the old age rule (`EPG_REFRESH_MS`, the file-moved marker
     * included) and still applies when the window reaches past [LEAD_MS]: a
     * partial pass leaves the freshness marker alone, so the source that failed
     * is retried even though its healthy siblings keep the window far ahead.
     */
    fun urgency(endsAtMs: Long?, nowMs: Long, ageStale: Boolean): Urgency = when {
        endsAtMs == null || endsAtMs <= 0L -> if (ageStale) Urgency.DUE else Urgency.NONE
        endsAtMs < nowMs + EpgWindow.READ_FUTURE_MS -> Urgency.URGENT
        endsAtMs < nowMs + LEAD_MS -> Urgency.DUE
        ageStale -> Urgency.DUE
        else -> Urgency.NONE
    }

    /** Whether [urgency] wants an import at all. */
    fun needsImport(urgency: Urgency): Boolean = urgency != Urgency.NONE
}
