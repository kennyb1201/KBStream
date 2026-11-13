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

    /**
     * How old the freshness marker has to be before age alone means "stale".
     *
     * The guide screen's own gate (`IptvViewModel.EPG_REFRESH_MS`) aliases this
     * value, and the diagnostics line reads it too, so the three cannot disagree
     * about what the entry check counted as stale. Twelve hours: one refresh
     * cadence doubled - long enough that a healthy guide is never re-imported for
     * being old, short enough that a marker which stopped moving is noticed
     * inside a day.
     */
    const val AGE_STALE_MS = 12L * 60L * 60L * 1000L

    /**
     * How long an EMPTY guide may stand untouched before an entry imports it
     * again, whatever the freshness marker says.
     *
     * The watchdog's own "don't hammer a provider" interval
     * ([EPG_ENTRY_RETRY_COOLDOWN_MS]), reused rather than invented: it is the
     * wait this app already established between automatic entry imports of a
     * guide that is not getting better. A broken guide therefore heals on the
     * next entry after a failure instead of a whole cadence later, and the
     * empty-guide rule cannot become an import on every open - which is what the
     * old "never urgent while the marker is young" rule was really protecting
     * against, at the cost of leaving a blank guide blank until the marker aged.
     */
    const val EMPTY_GUIDE_RETRY_MS = EPG_ENTRY_RETRY_COOLDOWN_MS

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
     * no programs at all, which is the BLANK GUIDE itself (field 2026-11-12, Fire
     * Stick: channels 15,425, `programsInWindow=0`, `newestProgramEnd=none`, and
     * nothing imported until a manual press). That state used to fall back to
     * [ageStale] alone, so a guide could stand empty behind a marker younger than
     * twelve hours and no entry would touch it — with the background worker
     * throttled on the same box, "repeatedly blank, only a manual reimport
     * fixes it" is exactly that pair.
     *
     * An empty guide is therefore URGENT, but not on every entry: once per
     * [EMPTY_GUIDE_RETRY_MS] ([lastAttemptMs] is when the last pass actually
     * STARTED, whoever started it). A feed that is empty on purpose is
     * re-attempted no more often than a broken one, and a blank guide is
     * repaired on the next entry instead of a day later.
     *
     * [ageStale] is the old age rule ([AGE_STALE_MS], the file-moved marker
     * included) and still applies when the window reaches past [LEAD_MS]: a
     * partial pass leaves the freshness marker alone, so the source that failed
     * is retried even though its healthy siblings keep the window far ahead.
     */
    fun urgency(
        endsAtMs: Long?,
        nowMs: Long,
        ageStale: Boolean,
        lastAttemptMs: Long = 0L
    ): Urgency = when {
        endsAtMs == null || endsAtMs <= 0L ->
            if (ageStale || attemptOverdue(nowMs, lastAttemptMs)) Urgency.URGENT else Urgency.NONE
        endsAtMs < nowMs + EpgWindow.READ_FUTURE_MS -> Urgency.URGENT
        endsAtMs < nowMs + LEAD_MS -> Urgency.DUE
        ageStale -> Urgency.DUE
        else -> Urgency.NONE
    }

    /**
     * Whether an empty guide has gone a whole cadence without an attempt.
     *
     * A stamp of 0 (never attempted, and nothing recorded because no import ever
     * ran here) is overdue by definition: the guide is empty and nobody has
     * tried. A stamp in the future is a clock that was put back, and is treated
     * as "just now" rather than silencing the retry for as long as the clock is
     * behind.
     */
    private fun attemptOverdue(nowMs: Long, lastAttemptMs: Long): Boolean {
        if (lastAttemptMs <= 0L) return true
        if (lastAttemptMs > nowMs) return false
        return nowMs - lastAttemptMs >= EMPTY_GUIDE_RETRY_MS
    }

    /** Whether [urgency] wants an import at all. */
    fun needsImport(urgency: Urgency): Boolean = urgency != Urgency.NONE
}
