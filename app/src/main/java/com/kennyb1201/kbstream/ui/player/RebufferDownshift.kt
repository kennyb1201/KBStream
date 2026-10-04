package com.kennyb1201.kbstream.ui.player

/**
 * Adaptive source downshift on repeated stalls.
 *
 * The stream ranker already picks the device-appropriate source up front, and
 * the error ladder already switches when a source FAILS — but neither reacts to
 * the source that opens, plays, and simply cannot keep up. That is the reported
 * "38 rebuffers on a 1 Mbps stream": nothing ever errors, so every existing
 * recovery is blind to it, and the viewer watches a spinner in slices for the
 * length of the film.
 *
 * This is the missing signal. Count mid-playback rebuffers; once
 * [REBUFFER_DOWNSHIFT_COUNT] of them land inside
 * [REBUFFER_DOWNSHIFT_WINDOW_MS], drop to the next-ranked source through the
 * same `tryNextSource` ladder the error paths use. Because that ladder only
 * ever moves FORWARD through the ranking, a downshift can never bounce between
 * two sources, and its own per-source switch cap still bounds how far a
 * session walks the list.
 *
 * The arithmetic lives here, apart from the Activity, for the usual reason
 * ([PlaybackRecoveryRules], [LiveStallWatchdog]): the window is the whole
 * feature, and it can be exercised without a TV. The Activity owns the side
 * effects — recognising a rebuffer, the guards, and the switch itself.
 */

/**
 * How many mid-playback rebuffers the current source gets inside the window
 * before the session drops a rung.
 *
 * Three is deliberately low: a healthy source with any read-ahead buffer
 * essentially never rebuffers mid-playback at all, so one is a CDN hiccup and
 * two is a bad minute — while three inside [REBUFFER_DOWNSHIFT_WINDOW_MS] is a
 * source that plainly cannot sustain its own bitrate. Waiting for more would
 * spend minutes of the film proving what the first three already said.
 */
internal const val REBUFFER_DOWNSHIFT_COUNT = 3

/**
 * The window those rebuffers must land in.
 *
 * Long enough that an isolated stall every few minutes is not enough on its
 * own, short enough that the downshift arrives while the source is still
 * visibly bad rather than after the film has mostly played out.
 */
internal const val REBUFFER_DOWNSHIFT_WINDOW_MS = 5 * 60_000L

/**
 * A rebuffer shorter than this does not count.
 *
 * A seek, a track change and a moment of decoder starvation all flip through
 * `STATE_BUFFERING` and back within a few hundred milliseconds; counting those
 * would downshift a perfectly healthy source under a viewer who scrubs. A
 * source that cannot keep up stalls for seconds at a time.
 */
internal const val REBUFFER_DOWNSHIFT_MIN_STALL_MS = 2_000L

/**
 * How close a seek may sit to a rebuffer's start for that rebuffer to be read
 * as the seek's own.
 *
 * Media3 processes a seek asynchronously, so the discontinuity callback and the
 * `STATE_BUFFERING` that follows it can land in either order; the grace window
 * covers that reordering. A seek also flushes the read-ahead buffer, so its
 * buffering is a normal consequence of the jump and never evidence that the
 * source is slow.
 */
internal const val SEEK_REBUFFER_GRACE_MS = 1_000L

/**
 * Whether a just-finished rebuffer belongs to a seek rather than a starved
 * source.
 *
 * The Activity stamps [lastSeekAtMs] when it processes a seek discontinuity and
 * stamps `rebufferStartedAtMs` when buffering begins; this compares the two.
 * [lastSeekAtMs] of `0` means no seek has happened this session.
 */
internal fun rebufferFollowsSeek(
    rebufferStartedAtMs: Long,
    lastSeekAtMs: Long
): Boolean = lastSeekAtMs > 0L && lastSeekAtMs >= rebufferStartedAtMs - SEEK_REBUFFER_GRACE_MS

/**
 * The sliding window of a single source's mid-playback rebuffers.
 *
 * Only timestamps of rebuffers the Activity has already accepted (long enough,
 * while actually playing, not a seek's own) are recorded, so this class holds
 * no opinion about what a rebuffer is — it just answers "have enough of them
 * landed close together yet".
 *
 * Not thread-safe on its own: it is only ever touched from the Activity's one
 * player-listener handler thread.
 */
internal class RebufferDownshiftTracker {

    /** Rebuffer end timestamps, oldest first. */
    private val rebuffersMs = ArrayDeque<Long>()

    /** Records one counted rebuffer. */
    fun record(nowMs: Long) {
        rebuffersMs.addLast(nowMs)
        prune(nowMs)
    }

    /** True when the window already holds [REBUFFER_DOWNSHIFT_COUNT] rebuffers. */
    fun due(nowMs: Long): Boolean = count(nowMs) >= REBUFFER_DOWNSHIFT_COUNT

    /** How many rebuffers the window currently holds, for logs and reports. */
    fun count(nowMs: Long): Int {
        prune(nowMs)
        return rebuffersMs.size
    }

    /** A fresh source (or a spent ladder) starts with an empty window. */
    fun reset() {
        rebuffersMs.clear()
    }

    private fun prune(nowMs: Long) {
        while (rebuffersMs.isNotEmpty() && nowMs - rebuffersMs.first() > REBUFFER_DOWNSHIFT_WINDOW_MS) {
            rebuffersMs.removeFirst()
        }
    }
}
