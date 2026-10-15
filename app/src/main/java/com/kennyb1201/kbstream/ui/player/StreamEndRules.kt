package com.kennyb1201.kbstream.ui.player

/**
 * What mpv's `MPV_EVENT_END_FILE` means, from the state only the view that owns
 * the session has.
 *
 * The reported failure: a stream that died MID-PLAYBACK — a segment that
 * started answering 403, a reconnect that ran out of attempts — left the last
 * decoded frame frozen on screen for as long as the viewer cared to look at it.
 * No log line, no error card, no retry, no callback: mpv had ended the file,
 * and the handler's `if (!fileLoaded)` covered only the other case (a file that
 * never opened), so everything else fell out of the bottom of the branch.
 * Keep-open made it worse rather than better: mpv holds the window and the
 * final frame deliberately, so nothing on screen distinguished "the file is
 * over" from "the stream is gone".
 *
 * The event itself does not say which of those it was — mpv's own end-file
 * REASON lives in the event's data node, and [MpvBackend] drops that node at the
 * JNI seam (`event(eventId)`). So the classification is made here, from state
 * this view already tracks:
 *
 *  - [Verdict.IGNORE] — this view, or the Activity, asked for the file to go
 *    away: `release()` destroys the instance, a `loadfile replace` tears the
 *    previous file down while its replacement opens, and an engine handoff
 *    stops the file outright. None is a failure, and the replace case USED to
 *    be read as one: `startPendingLoad` clears [fileLoaded] before issuing the
 *    load, so the outgoing file's END_FILE landed in the open-failure branch
 *    and told the viewer a working source "could not be played" — with the
 *    open-failure ladder demoting the add-on that had just been playing.
 *  - [Verdict.OPEN_FAILED] — a file that never loaded, and nothing of ours was
 *    replacing it. The existing path, unchanged.
 *  - [Verdict.ENDED_NORMALLY] — `eof-reached`, or a playhead within
 *    [NORMAL_END_SLACK_MS] of a known duration. Nothing to report: `onEnded`
 *    has already been delivered.
 *  - [Verdict.DIED_MID_STREAM] — a file that HAD loaded, was not replaced, and
 *    ended well short of its end. This is the freeze above, and it is the one
 *    outcome that has to reach the screen.
 *
 * The two guards that keep it from crying wolf:
 *  - an unknown duration (`0`) never produces [Verdict.DIED_MID_STREAM]. A live
 *    stream or an unusual container can end without a length and without
 *    `eof-reached`, and an error card over a normal end is worse than the
 *    freeze this fixes;
 *  - the slack above means a stream that dies in the last seconds of a file
 *    reads as a normal end, which is indistinguishable to the viewer and
 *    already covered by the end-of-episode flow.
 *
 * Pure, so it is pinned by [StreamEndRulesTest] rather than through mpv.
 */
internal object StreamEndRules {

    enum class Verdict {
        /** Our own teardown, or the file we are replacing. Nothing happened. */
        IGNORE,

        /** A file that never loaded: the existing "could not open" path. */
        OPEN_FAILED,

        /** Reached the end (`eof-reached`, or the playhead at the duration). */
        ENDED_NORMALLY,

        /** Loaded, played, and ended well short of its end: a dropped stream. */
        DIED_MID_STREAM
    }

    /**
     * How close to a known duration still counts as reaching the end. Generous
     * on purpose: the last few seconds of a file are credits, and a stream that
     * dies there is a finished episode, not a broken one.
     */
    const val NORMAL_END_SLACK_MS = 5_000L

    fun verdict(
        fileLoaded: Boolean,
        /** Our own doing: a replaced file, or a stop this view issued itself. */
        ourOwnStop: Boolean,
        released: Boolean,
        eofReached: Boolean,
        positionMs: Long,
        durationMs: Long,
        slackMs: Long = NORMAL_END_SLACK_MS
    ): Verdict {
        // Teardown first: the instance is going away, and everything below
        // would be read off half-destroyed state.
        if (released) return Verdict.IGNORE
        // Our own replacement, or our own stop: the file ends here because we
        // said so, and it did nothing wrong.
        if (ourOwnStop) return Verdict.IGNORE
        if (!fileLoaded) return Verdict.OPEN_FAILED
        if (eofReached) return Verdict.ENDED_NORMALLY
        // No length to compare against: a live stream or a container without a
        // duration ends this way, and there is nothing to call a failure.
        if (durationMs <= 0L) return Verdict.IGNORE
        if (positionMs >= durationMs - slackMs) return Verdict.ENDED_NORMALLY
        return Verdict.DIED_MID_STREAM
    }
}
