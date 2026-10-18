package com.kennyb1201.kbstream.ui.player

/**
 * The last tenth of the runtime. The end-of-episode card lives here, and a
 * session that is being left from it is treated as finished.
 */
internal const val CREDITS_TAIL_RATIO = 0.90f

/**
 * True when [positionMs] sits in the closing [CREDITS_TAIL_RATIO] of a KNOWN
 * runtime - the window from which a filed row is a "leaving" row rather than a
 * mid-episode resume. A zero/unknown duration never qualifies.
 *
 * A session left here can still be filed NOT completed: the local completion
 * rule is stricter than the tracker's, while the tracker's own stop may mark
 * the episode watched anyway. Continue Watching then holds a stale resume card
 * until something asks Home to re-merge against the tracker feeds (see the
 * players' use of ContinueWatchingRefreshBus); this is the predicate they use
 * to raise that request.
 */
internal fun isCreditsTail(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= (durationMs * CREDITS_TAIL_RATIO).toLong()

/**
 * Decides whether a source ERROR arrived so late in the file that the episode
 * is finished despite it.
 *
 * Field report (Onn 4K Plus, 0.6.110): an episode paused at 77.43%, resumed,
 * played on to 99.28% - and then the Matroska tail threw `No valid varint
 * length mask found`. The error path went to its recovery ladder, which
 * rebuilt the player; the rebuild re-ran the launch resume-restore, read the
 * resume point written back at the PAUSE (77.43%, because resume points are
 * written on pause/exit, not continuously) and restarted playback there. The
 * completion was never filed, so the episode was never marked watched and sat
 * in Continue Watching with nine minutes "left" on it.
 *
 * The error-time playhead is the fact the fix turns on, and it is why this
 * takes a position rather than reading the stored resume point: at the moment
 * the error is raised the player is still alive and still knows it was at
 * 99.28%. Same threshold [shouldRecordCompletion]'s position arm uses, passed
 * in by each engine as its own `COMPLETION_THRESHOLD_RATIO` so this path can
 * never answer to a different number than the save that follows it.
 *
 * A zero/unknown duration never qualifies: without a length there is no tail,
 * and such an error keeps the recovery path it has always had.
 */
internal fun errorAtCompletionThreshold(
    positionMs: Long,
    durationMs: Long,
    thresholdRatio: Float
): Boolean = durationMs > 0L && positionMs >= (durationMs * thresholdRatio).toLong()

/**
 * Decides whether a player session should be written to watch history as a
 * COMPLETED episode rather than a resumable position.
 *
 * [played] is the session's own "did anything ever play?" verdict — a painted
 * frame, or a playhead past the start. It gates everything: a source that never
 * came up can still reach the player's end-of-file verdict (an empty timeline,
 * a container the extractor reads as zero-length, or the stall fallback reading
 * a frozen-at-zero position as a tail), and treating that as a finished episode
 * is how a run of failed add-on sources filed themselves as watched and
 * auto-advanced while the error card was still on screen. Nothing was watched,
 * so nothing is completed.
 *
 * [playbackEnded] is the player's own end-of-file verdict — the ground truth
 * once [played] has held. When that verdict never arrives (a stream that stops
 * short of its declared duration, a frozen tail, or the viewer backing out of
 * the end card before the file's last second), a session that was already
 * showing its end-of-episode card in the closing minutes counts as finished
 * too. That is exactly what the external-player engine does: it concludes
 * first, then raises the card — whereas the in-app engines raised the card
 * early and waited on the end event, so leaving from the card left the episode
 * with no watch marker and its old progress bar still showing.
 *
 * The card fallback is limited to the last [CREDITS_TAIL_RATIO] of the
 * runtime, because the panel point is adjustable (down to 80%): a card the
 * viewer chose to open mid-episode must not be mistaken for the end. An
 * unknown duration (0) never triggers the card fallback on its own — only
 * the player's own ended verdict can complete a file whose length is unknown.
 *
 * [explicitAdvance] is the transport's own Next (or PLAY NEXT), and it is the
 * one case the card fallback above could not cover: the viewer presses it
 * *in the credits*, before the countdown has had a chance to raise the card,
 * and the episode is handed off with the panel never shown. Without this the
 * handoff filed a RESUME row with a minute or two left while the "stop"
 * scrobble simultaneously told the tracker the episode was watched — two
 * systems, two verdicts, and the tick later landed on an episode the rail was
 * still offering as "Resume 2 min left".
 *
 * It deliberately does NOT complete the episode on its own. Next is also an
 * ordinary mid-episode button, so an advance is only "done" when it happened
 * inside the same tail the card fallback uses; an explicit advance from the
 * middle of an episode still files a resume point. The `endPanelsShown` gate
 * above stays untouched for every other path — an unattended exit, a stream
 * that stops short, the app being backgrounded.
 *
 * [completionFiled] is the session's OWN record that a completion has already
 * been written to watch history, and it outranks every position-based arm
 * below. The arms above all read WHERE the playhead is, which is the right
 * question only while the episode is unfinished: the 90% threshold completes a
 * session without the file ever ending, so a viewer who crossed the threshold
 * and then moved the playhead back — a rewind, or simply resuming a paused
 * position — was re-decided as "not finished" by the next save, and the
 * resume row that save wrote OVERWROTE the completion. The episode lost its
 * watched marker and its old resume point ("Resume 32 min left") came back,
 * which is the reported "watched it to the end but it still says 10 minutes
 * left". A filed completion is a fact about the session, not about the
 * playhead, so it is never re-decided — and it is what stops the pause/exit
 * save from racing the completion it was already filed after.
 *
 * It is deliberately read only after [played]: a session that never played has
 * nothing that could have been filed, and the filed flag on such a session
 * would be a bug in the caller rather than a fact to honour.
 */
internal fun shouldRecordCompletion(
    playbackEnded: Boolean,
    endPanelsShown: Boolean,
    positionMs: Long,
    durationMs: Long,
    played: Boolean,
    explicitAdvance: Boolean = false,
    completionFiled: Boolean = false
): Boolean {
    if (!played) return false
    if (completionFiled) return true
    if (playbackEnded) return true

    // Inside the credits tail, an explicit advance is the viewer's own "done
    // with this episode" verdict — the same verdict the card fallback reads
    // from a raised card, arrived at before the card could be raised.
    if (explicitAdvance && isCreditsTail(positionMs, durationMs)) return true

    if (!endPanelsShown) return false
    return isCreditsTail(positionMs, durationMs)
}
