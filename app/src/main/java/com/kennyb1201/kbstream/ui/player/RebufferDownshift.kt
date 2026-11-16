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
 * Counting is not the whole signal, though: one stall of
 * [REBUFFER_DOWNSHIFT_LONG_STALL_MS] or more convicts the source on its own
 * (see that constant). The count rule is what the hiccup-versus-slow-source
 * reasoning was written for, and it still governs every shorter stall.
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
 *
 * That reasoning is about SHORT stalls, and it is why the count rule still
 * governs every stall under [REBUFFER_DOWNSHIFT_LONG_STALL_MS]. A stall long
 * enough to be conclusive on its own does not wait for the second and third.
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
 * One rebuffer at least this long convicts the source on its own.
 *
 * The count rule above is about short stalls: one is a CDN hiccup and two is a
 * bad minute. A nine-second stall is neither. A source with any read-ahead
 * buffer does not starve for nine seconds and then recover by itself — it has
 * stopped being able to sustain its own bitrate, and the only thing waiting for
 * the second and third stall buys is more of the viewer's time paid to a source
 * already proven unfit. The reported case is Landman S02E02 on Usenet Vault 4K
 * HEVC (7.4 GB): three rebuffers, the last 9184 ms, before the switch fired.
 *
 * Eight seconds sits just under that stall and is 4x
 * [REBUFFER_DOWNSHIFT_MIN_STALL_MS]: long enough that no seek, track change or
 * decoder flicker reaches it, short enough to fire on the first stall a source
 * of this kind produces. It is a constant, not a preference — the guards around
 * it (the 2s minimum, the seek grace, the forward-only ladder, the per-source
 * switch cap and the decoder-exhaustion hold) are what keep it safe.
 */
internal const val REBUFFER_DOWNSHIFT_LONG_STALL_MS = 8_000L

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
 * Only rebuffers the Activity has already accepted (long enough, while actually
 * playing, not a seek's own) are recorded, so this class holds no opinion about
 * what a rebuffer is — it just answers "is this source done": either one stall
 * lasted [REBUFFER_DOWNSHIFT_LONG_STALL_MS], or [REBUFFER_DOWNSHIFT_COUNT] of
 * them landed close together.
 *
 * The duration travels with the timestamp because the long-stall rule is a
 * property of one stall, not of how many there have been: a single 9s stall
 * says what three 2s ones only say together.
 *
 * Not thread-safe on its own: it is only ever touched from the Activity's one
 * player-listener handler thread.
 */
internal class RebufferDownshiftTracker {

    /** One counted rebuffer: when it ended, and how long it lasted. */
    private class Rebuffer(val endedAtMs: Long, val stalledMs: Long)

    /** Counted rebuffers, oldest first. */
    private val rebuffers = ArrayDeque<Rebuffer>()

    /** Records one counted rebuffer and how long it lasted. */
    fun record(nowMs: Long, stalledMs: Long) {
        rebuffers.addLast(Rebuffer(nowMs, stalledMs))
        prune(nowMs)
    }

    /**
     * True when the source is done: one long stall, or
     * [REBUFFER_DOWNSHIFT_COUNT] of them inside the window.
     */
    fun due(nowMs: Long): Boolean =
        longStall(nowMs) || count(nowMs) >= REBUFFER_DOWNSHIFT_COUNT

    /**
     * True when the window holds a stall long enough to convict the source on
     * its own, for the log line and the report.
     */
    fun longStall(nowMs: Long): Boolean {
        prune(nowMs)
        return rebuffers.any { it.stalledMs >= REBUFFER_DOWNSHIFT_LONG_STALL_MS }
    }

    /** How many rebuffers the window currently holds, for logs and reports. */
    fun count(nowMs: Long): Int {
        prune(nowMs)
        return rebuffers.size
    }

    /** A fresh source (or a spent ladder) starts with an empty window. */
    fun reset() {
        rebuffers.clear()
    }

    private fun prune(nowMs: Long) {
        while (rebuffers.isNotEmpty() && nowMs - rebuffers.first().endedAtMs > REBUFFER_DOWNSHIFT_WINDOW_MS) {
            rebuffers.removeFirst()
        }
    }
}
