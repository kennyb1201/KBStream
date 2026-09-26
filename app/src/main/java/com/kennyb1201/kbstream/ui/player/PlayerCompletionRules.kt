package com.kennyb1201.kbstream.ui.player

/**
 * The last tenth of the runtime. The end-of-episode card lives here, and a
 * session that is being left from it is treated as finished.
 */
internal const val CREDITS_TAIL_RATIO = 0.90f

/**
 * Decides whether a player session should be written to watch history as a
 * COMPLETED episode rather than a resumable position.
 *
 * [playbackEnded] is the player's own end-of-file verdict - the ground truth.
 * When that verdict never arrives (a stream that stops short of its declared
 * duration, a frozen tail, or the viewer backing out of the end card before
 * the file's last second), a session that was already showing its
 * end-of-episode card in the closing minutes counts as finished too. That is
 * exactly what the external-player engine does: it concludes first, then
 * raises the card - whereas the in-app engines raised the card early and
 * waited on the end event, so leaving from the card left the episode with no
 * watch marker and its old progress bar still showing.
 *
 * The card fallback is limited to the last [CREDITS_TAIL_RATIO] of the
 * runtime, because the panel point is adjustable (down to 80%): a card the
 * viewer chose to open mid-episode must not be mistaken for the end. An
 * unknown duration (0) never triggers the card fallback on its own - only
 * the player's own ended verdict can complete a file whose length is unknown.
 */
internal fun shouldRecordCompletion(
    playbackEnded: Boolean,
    endPanelsShown: Boolean,
    positionMs: Long,
    durationMs: Long
): Boolean {
    if (playbackEnded) return true
    if (!endPanelsShown) return false
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * CREDITS_TAIL_RATIO).toLong()
}
