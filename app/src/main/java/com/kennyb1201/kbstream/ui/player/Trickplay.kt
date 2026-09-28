package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import kotlin.math.abs

/**
 * The arithmetic behind scrub previews, kept apart from the decoding so it can
 * be tested on the JVM (the decoder itself is Android-only — see
 * [TrickplayFrames]).
 *
 * A preview frame is expensive: it costs a second video decoder and a second
 * connection to the same source. What this file decides is therefore *when* it
 * is worth paying that, and it decides it in three places — which frame a drag
 * position maps to (so a drag across the same ten seconds asks once, not fifty
 * times), whether a frame the player just showed may be filed under the bucket
 * that asked for it (so a stale frame is never cached as the wrong moment), and
 * how long the preview is allowed to keep failing before the session gives up
 * on it.
 */

/**
 * One preview frame per this much programme.
 *
 * Ten seconds is the coarsest step that still answers "roughly where is this?"
 * on a 90-minute episode (540 possible frames) and instantly on any single
 * scene, and it is the same granularity the platforms that ship sprite sheets
 * use. Coarser than this and the thumbnail stops tracking the drag; finer and
 * every drag asks for a new decode it will not live long enough to show.
 */
internal const val TRICKPLAY_BUCKET_MS = 10_000L

/** How many decoded frames the session keeps, newest-used first. */
internal const val TRICKPLAY_CACHE_FRAMES = 6

/**
 * How long an extraction may take before it counts as a failure.
 *
 * Generous on purpose: the first frame of a new position has to re-open the
 * stream and fill a buffer, and a slow addon host can legitimately take a few
 * seconds. The failure it prevents is a request that never resolves at all,
 * which would otherwise wedge the one-slot pipeline for the rest of the drag.
 */
internal const val TRICKPLAY_TIMEOUT_MS = 6_000L

/**
 * How far the player may be from the bucket it was asked for and still have the
 * frame it is showing counted as that bucket's.
 *
 * The player is asked for the nearest keyframe rather than the exact
 * millisecond, so it can legitimately settle a GOP away from the bucket. Wide
 * enough for that, narrow enough that a position the player has not actually
 * reached yet is not mistaken for the one being dragged to — a wrong frame
 * cached as a right one is worse than no preview, because it looks
 * authoritative.
 */
internal const val TRICKPLAY_ACCEPT_WINDOW_MS = 4_000L

/**
 * Consecutive failures before the session stops asking.
 *
 * Usually the second decoder: a box that has no decoder left to hand out fails
 * the preview player outright, and the answer to that is to leave the first
 * decoder alone (the one playing the video) rather than to retry every time the
 * viewer presses RIGHT. Two windows rather than one because the first failure
 * is often a transient startup one.
 */
internal const val TRICKPLAY_MAX_FAILURES = 2

/**
 * How long the preview player is kept after the last request.
 *
 * Holding it holds a video decoder, so it is not kept for the session; but it
 * is not torn down the instant a drag ends either, because the way a viewer
 * scrubs is several presses with gaps between them, and rebuilding the player
 * between each one would cost a connection per press and show nothing. Five
 * seconds covers a scrub that pauses to look at what it landed on and no more
 * than that.
 */
internal const val TRICKPLAY_IDLE_RELEASE_MS = 5_000L

/**
 * How long a frame that arrives after the viewer let go of the button is still
 * the one they asked for.
 *
 * Scrubbing with a remote is a series of presses, not a drag, and a preview
 * frame is not ready the instant one lands: the first has to build a second
 * player and fill its buffer, so it arrives a second or more after the press
 * that asked for it. Tying the card to the press - taking it away the moment
 * the key came up - therefore dropped every frame a press-and-release scrub
 * ever asked for, which is all of them. Three seconds covers the build plus the
 * fill; a frame slower than that is still cached under the position it belongs
 * to (dragging back over the same ground stays free) but is no longer put on
 * screen, because a thumbnail that appears five seconds after the press reads
 * as a glitch rather than as an answer.
 */
internal const val TRICKPLAY_SHOW_GRACE_MS = 3_000L

/**
 * The bucket [positionMs] belongs to.
 *
 * Floored, not rounded: the bucket is also the position handed to the decoder,
 * and rounding up would preview the frame *after* the one being dragged to —
 * at the end of a title, past the last frame there is.
 */
internal fun trickplayBucket(positionMs: Long): Long =
    if (positionMs <= 0L) 0L else (positionMs / TRICKPLAY_BUCKET_MS) * TRICKPLAY_BUCKET_MS

/**
 * Whether a frame the preview player is showing right now can be filed under
 * [bucketMs].
 *
 * The extractor only asks this once the player has settled, which is what makes
 * it meaningful: a decoder hands back frames from before the seek target while
 * it is still filling a buffer at the new position, and those must not be
 * cached under the bucket that asked for the seek.
 */
internal fun trickplayFrameFits(
    bucketMs: Long,
    positionMs: Long,
    windowMs: Long = TRICKPLAY_ACCEPT_WINDOW_MS
): Boolean = abs(positionMs - bucketMs) <= windowMs

/** Whether [failures] consecutive failures have used up the session's tries. */
internal fun trickplayGivesUp(failures: Int): Boolean = failures >= TRICKPLAY_MAX_FAILURES

/**
 * The container hint the preview player's media item carries.
 *
 * The main player resolves one for the stream before it plays, using a rule
 * that knows the extension-less shapes (a playlist whose `.m3u8` marker lives
 * only in the query, an HLS path with no file name at all), and passes it in
 * here. The MPV engine's side has nothing to pass — libmpv does its own probing
 * — so the one guess that matters is made here instead: without it, a playlist
 * served as `.../stream?type=.m3u8` reaches media3 as an unknown type, is handed
 * to the progressive extractors, and the preview fails on a stream that plays
 * perfectly in the player beside it.
 *
 * Null means "let media3 decide", which is right for everything with a real
 * extension in its path.
 */
internal fun trickplayMimeHint(url: String, resolved: String?): String? {
    if (!resolved.isNullOrBlank()) return resolved
    val lower = url.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
        "m3u8" in lower -> MimeTypes.APPLICATION_M3U8
        // An HLS directory with no file name at all. The same rule the main
        // player's own resolver carries, and the same reason: the playlist text
        // is otherwise handed to the progressive extractors.
        "/hls/" in path -> MimeTypes.APPLICATION_M3U8
        else -> null
    }
}

/**
 * The horizontal translation that centres something [cardWidth] wide on
 * [anchorX] inside a [contentWidth]-wide parent, without letting it hang off
 * either edge.
 *
 * The preview is laid out centred, so this is the offset that moves it over the
 * seek bar's thumb — clamped, because a thumbnail half off the screen is worse
 * than one that is merely not exactly over the thumb, and the bar's own ends
 * are where a viewer scrubs most.
 */
internal fun trickplayAnchorTranslation(
    anchorX: Float,
    contentWidth: Int,
    cardWidth: Int,
    edgeMarginPx: Float
): Float {
    if (contentWidth <= 0 || cardWidth <= 0) return 0f
    val half = contentWidth / 2f
    val lowest = cardWidth / 2f + edgeMarginPx - half
    val highest = half - cardWidth / 2f - edgeMarginPx
    // A card wider than the space it has: centred beats clamped to a nonsense
    // edge it would then be hanging off anyway.
    if (lowest > highest) return 0f
    return (anchorX - half).coerceIn(lowest, highest)
}

/**
 * Decoded preview frames, least-recently-used first out.
 *
 * Small and bounded because the entries are bitmaps: six frames of a 480x270
 * capture are about 3 MB, which is worth keeping for the length of a scrub
 * (dragging back over ground already covered then costs nothing and does not
 * re-open the stream) and not worth keeping beyond it.
 *
 * Written by hand rather than with `android.util.LruCache` so the eviction
 * order — the only part of this that can be wrong — is reachable from a JVM
 * test.
 */
internal class TrickplayFrameCache<V>(private val limit: Int) {

    private val entries = object : LinkedHashMap<Long, V>(limit.coerceAtLeast(1), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, V>?): Boolean =
            size > limit.coerceAtLeast(1)
    }

    /** The frame for [bucketMs], marking it as the most recently used one. */
    fun get(bucketMs: Long): V? = entries[bucketMs]

    fun put(bucketMs: Long, frame: V) {
        entries[bucketMs] = frame
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
