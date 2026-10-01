package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Player

/**
 * The live-channel keep-alive, kept out of NativePlayerActivity so it can be
 * read and tested on its own (the same reason PlayerRebuild.kt holds
 * shouldRebuildAfterStop).
 *
 * A live IPTV channel is the one session nothing else in the player watches.
 * The stall watchdog returns immediately for live, and the retry ladder only
 * ever runs off a hard error — so a channel whose server stops sending does not
 * error and does not retry: the connection simply goes quiet and the player
 * sits on the last frame it painted. That is the reported morning state. A live
 * channel left running overnight is "jammed" and looks paused, and a zap fixes
 * it because a zap builds a new player — which is exactly the recovery this
 * gives a stalled one, without the viewer.
 *
 * The decision is the same shape as the VOD stall watchdog's — has anything
 * moved, and for how long has it been still — but the verdict is different,
 * because a live channel has no buffered edge to seek past: the session goes to
 * the reconnect ladder, which rebuilds against the SAME url. That is a re-tune.
 */

/** How often a live channel's progress is checked. */
internal const val LIVE_STALL_TICK_MS = 2_000L

/**
 * How long a live channel may go with nothing moving before it is re-tuned.
 *
 * Live buffers 5-10s and a segment lands every few seconds, so a merely slow
 * source spends its budget long before this — and, importantly, a slow source
 * still GROWS its buffer, which [liveProgressed] reads as movement. Twenty
 * seconds with neither the playhead nor the buffer moving is not a slow link,
 * it is a server that stopped sending, and the picture has already been frozen
 * for most of it.
 */
internal const val LIVE_STALL_NO_PROGRESS_MS = 20_000L

/** The step in either direction that counts as movement, as on the VOD watchdog. */
internal const val LIVE_PROGRESS_STEP_MS = 500L

/** What one live-watchdog tick should do. */
internal enum class LiveWatchdogAction {
    /** Not a live session, or not a state to act on: leave it alone. */
    IGNORE,

    /** Data is still arriving, or the quiet window is young: check again. */
    KEEP_WAITING,

    /** The channel has gone quiet: hand the session to the reconnect ladder. */
    RETUNE
}

/**
 * Whether the playhead or the buffer has advanced since the tracked baseline.
 *
 * Either one counts. A live stream that is refilling a drained buffer grows
 * `bufferedMs` while its playhead stands still, and re-tuning that session
 * would throw away the data it is waiting on — the mistake the VOD watchdog
 * already documents.
 */
internal fun liveProgressed(
    positionMs: Long,
    bufferedMs: Long,
    lastPositionMs: Long,
    lastBufferedMs: Long
): Boolean =
    positionMs > lastPositionMs + LIVE_PROGRESS_STEP_MS ||
        bufferedMs > lastBufferedMs + LIVE_PROGRESS_STEP_MS

/**
 * One tick's verdict for a live session.
 *
 * Every guard that returns [LiveWatchdogAction.IGNORE] is a session this must
 * not touch:
 *
 *  - not a live channel: a VOD session has its own watchdog;
 *  - the screen is finishing or destroyed, so there is nothing to re-tune for;
 *  - a reconnect is already in flight: the ladder owns the session until it
 *    lands, and a second rebuild would fight the first for the one decoder this
 *    class of box hands out;
 *  - the viewer paused it — a paused live channel is not a broken one, and it
 *    is re-armed when playback resumes;
 *  - IDLE or ENDED, which belong to the ladder and the end-of-title paths;
 *  - no first frame yet, because a live channel's first bytes can legitimately
 *    take a while and the startup watchdog owns that window.
 *
 * @param quietMs how long it has been since the playhead or the buffer last
 *   moved, as measured by the caller's own baseline.
 */
internal fun liveWatchdogAction(
    live: Boolean,
    finishing: Boolean,
    reconnecting: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
    firstFrameRendered: Boolean,
    positionMs: Long,
    bufferedMs: Long,
    lastPositionMs: Long,
    lastBufferedMs: Long,
    quietMs: Long
): LiveWatchdogAction {
    if (!live || finishing) return LiveWatchdogAction.IGNORE
    if (reconnecting) return LiveWatchdogAction.IGNORE
    if (!playWhenReady) return LiveWatchdogAction.IGNORE
    if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) {
        return LiveWatchdogAction.IGNORE
    }
    if (!firstFrameRendered) return LiveWatchdogAction.IGNORE
    if (liveProgressed(positionMs, bufferedMs, lastPositionMs, lastBufferedMs)) {
        return LiveWatchdogAction.KEEP_WAITING
    }
    return if (quietMs >= LIVE_STALL_NO_PROGRESS_MS) {
        LiveWatchdogAction.RETUNE
    } else {
        LiveWatchdogAction.KEEP_WAITING
    }
}

/**
 * The rung an exhausted retry ladder restarts at, or -1 when the ladder really
 * is spent and the session parks on the error card.
 *
 * A live channel never ends, so "the ladder is spent" is not an outcome for
 * one: it is a provider that is down right now, on the screen most often
 * watched with nobody awake in front of it. Restarting the ladder at its
 * LONGEST backoff keeps the session reconnecting once every thirty seconds for
 * as long as it takes, instead of parking on a card nobody is there to press
 * while the channel is, in fact, perfectly watchable again. BACK still leaves
 * the session and CH+/CH- still zaps; both are outside this path.
 *
 * @param live the session is a live IPTV channel.
 * @param rungCount how many backoff rungs the ladder has.
 */
internal fun retryLoopRung(live: Boolean, rungCount: Int): Int =
    if (live && rungCount > 0) rungCount - 1 else -1
