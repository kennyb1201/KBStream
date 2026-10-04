package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * The arithmetic behind scrub previews, kept apart from the capture so it can be
 * tested on the JVM (the capture itself is Android-only — see
 * [TrickplayFrames]).
 *
 * A preview frame is a copy of the main player's own surface, and the player is
 * already rendering the scrubbed position (a scrub seeks it), so the frame the
 * viewer wants is on screen. Nothing here opens a stream or holds a decoder;
 * what this file decides is *when* a frame may be taken and trusted, and it
 * decides it in three places — which frame a drag position maps to (so a drag
 * across the same ten seconds asks once, not fifty times), whether a frame the
 * player is showing may be filed under the bucket that asked for it (so a stale
 * frame is never cached as the wrong moment), and how long the preview is
 * allowed to keep failing before the session gives up on it.
 */

/**
 * One preview frame per this much program.
 *
 * Ten seconds is the coarsest step that still answers "roughly where is this?"
 * on a 90-minute episode (540 possible frames) and instantly on any single
 * scene, and it is the same granularity the platforms that ship sprite sheets
 * use. Coarser than this and the thumbnail stops tracking the drag; finer and
 * every drag asks for a new capture it will not live long enough to show.
 */
internal const val TRICKPLAY_BUCKET_MS = 10_000L

/** How many captured frames the session keeps, newest-used first. */
internal const val TRICKPLAY_CACHE_FRAMES = 6

/**
 * The size a preview frame is captured at: 16:9, a sixth of a 1080p screen,
 * ~0.5 MB a frame.
 *
 * Shared by both engines because both feed the same card, and the two reach it
 * differently. The ExoPlayer path tells its surface copy this size to scale
 * into, so a frame costs a card-sized bitmap rather than a full-resolution one.
 * libmpv cannot be told anything of the sort: a screenshot is the file's own
 * resolution, so [trickplaySampleSize] decodes down to here instead.
 */
internal const val TRICKPLAY_CAPTURE_WIDTH = 480
internal const val TRICKPLAY_CAPTURE_HEIGHT = 270

/**
 * What the viewer is told when previews were attempted and produced nothing.
 *
 * Shared by both engines, because it is the same sentence: a viewer on a TV
 * cannot tell a silent feature from a broken one, and "were any frames ever
 * produced" is a question the diagnostics answer either way. The ExoPlayer path
 * has a notice of its own — a stream not rendering into a surface this app can
 * read is a different fault with a different fix — and libmpv has none, since it
 * has no surface to read.
 */
internal const val TRICKPLAY_NO_FRAMES_NOTICE =
    "No scrub preview thumbnails were available for this video"

/**
 * How often a preview that is waiting on the player looks again.
 *
 * Both engines wait, for the same reason and on the same clock. The ExoPlayer
 * path waits for the seek it just issued to land, so that the frame it copies
 * is the position the viewer picked rather than the one before it (see
 * [trickplayFrameFits]); libmpv waits for the same thing before it takes its
 * screenshot. Either way the wait is a second or so, and looking on a timer is
 * what turns "no thumbnail at the position you pressed for" into "the thumbnail
 * for that position, once the player has it anyway".
 */
internal const val TRICKPLAY_RETRY_MS = 400L

/**
 * How long an extraction may take before it counts as a failure.
 *
 * Generous on purpose: the first frame of a new position waits on the seek to
 * land and the surface to settle there, and a slow release can legitimately take
 * a few seconds. The failure it prevents is a request that never resolves at
 * all, which would otherwise wedge the one-slot pipeline for the rest of the
 * drag.
 */
internal const val TRICKPLAY_TIMEOUT_MS = 6_000L

/**
 * How far the player may be from the bucket it was asked for and still have the
 * frame it is showing counted as that bucket's.
 *
 * A seek lands on the nearest keyframe, so the player does not settle on the
 * bucket at all: it settles on the sync frame nearest to it, which on a long-GOP
 * release is half a GOP away — five seconds on a ten-second GOP, and the
 * previous four-second window refused every one of those as "not the position
 * that was asked for". Two presses of such a stream were therefore enough to
 * turn previews off for the rest of the film.
 *
 * Six seconds covers a 12s GOP and stays under the shortest scrub the UI has
 * (one [TRICKPLAY_BUCKET_MS] per press): any wider and the position the viewer
 * has just left could be mistaken for the one they are on, and a wrong frame
 * cached as a right one is worse than no preview, because it looks
 * authoritative.
 */
internal const val TRICKPLAY_ACCEPT_WINDOW_MS = 6_000L

/**
 * Consecutive failures *from the source* before the session stops asking.
 *
 * What reaches this many tries is the seek that never settled, the surface that
 * would not hand over a frame, the very first capture that had to wait on a
 * still-opening stream — failures a longer film simply stops having. A stream
 * that is not rendering into a readable surface does not wait for this count:
 * it ends the previews on its first refusal, because it will not have changed by
 * the next press.
 *
 * It used to be two, which on a device whose first preview frame took longer
 * than the capture budget meant previews were off for the whole session before
 * the second press had even landed.
 */
internal const val TRICKPLAY_MAX_TRANSIENT_FAILURES = 6

/**
 * How long the preview player is kept after the last request.
 *
 * A capture is not ready the instant a press lands — the seek has to settle and
 * the surface to be readable — and the way a viewer scrubs is several presses
 * with gaps between them, so waiting a little past the last request is what
 * keeps the frame that press asked for from being thrown away. Five seconds
 * covers a scrub that pauses to look at what it landed on and no more than
 * that.
 */
internal const val TRICKPLAY_IDLE_RELEASE_MS = 5_000L

/**
 * How long a frame that arrives after the viewer let go of the button is still
 * the one they asked for.
 *
 * Scrubbing with a remote is a series of presses, not a drag, and a preview
 * frame is not ready the instant one lands: the first has to wait for the seek
 * to land and the surface to settle on it, so it arrives a second or more after
 * the press that asked for it. Tying the card to the press - taking it away the
 * moment the key came up - therefore dropped every frame a press-and-release
 * scrub ever asked for, which is all of them. Three seconds covers the settle; a
 * frame slower than that is still cached under the position it belongs to
 * (dragging back over the same ground stays free) but is no longer put on
 * screen, because a thumbnail that appears five seconds after the press reads
 * as a glitch rather than as an answer.
 *
 * Measured from the moment the frame is *on screen*, not from the press: this is
 * the viewer's time to look at it. Charging it from the press instead took the
 * card away before a slow capture could draw, which from the outside is exactly
 * what "I never see a thumbnail" looks like.
 */
internal const val TRICKPLAY_SHOW_GRACE_MS = 3_000L

/**
 * How long the card stays armed from the press that asked for a frame.
 *
 * A whole capture budget plus the show window, because a frame that is still
 * inside its own timeout is still the frame the viewer asked for. This is the
 * other half of the fix above: the press arms the card for as long as a capture
 * is allowed to take, and the frame that arrives re-arms it for
 * [TRICKPLAY_SHOW_GRACE_MS] from the draw. The first frame of a session — which
 * has to wait on a seek into a still-opening stream — is the slowest one there
 * is, and it is the one a press-length window always lost.
 */
internal const val TRICKPLAY_WAIT_MS = TRICKPLAY_TIMEOUT_MS + TRICKPLAY_SHOW_GRACE_MS

/**
 * The bucket [positionMs] belongs to.
 *
 * Floored, not rounded: the bucket is also the position the player is asked to
 * seek to, and rounding up would preview the frame *after* the one being dragged
 * to — at the end of a title, past the last frame there is.
 */
internal fun trickplayBucket(positionMs: Long): Long =
    if (positionMs <= 0L) 0L else (positionMs / TRICKPLAY_BUCKET_MS) * TRICKPLAY_BUCKET_MS

/**
 * The `inSampleSize` that decodes a capture down to about [targetWidth].
 *
 * For the engine that screenshots its own output there is no capture size to
 * choose: the file is whatever resolution the video is, so a 4K release writes
 * a 3840x2160 image that the card then draws at 480px wide. Decoding that whole
 * thing costs a ~31 MB bitmap on a box with a 192 MB heap - for a thumbnail -
 * so it is decoded at a fraction instead.
 *
 * Powers of two only, because that is all `BitmapFactory` honors without extra
 * work, and they land well: 3840 and 1920 both divide to exactly 480, and 1280
 * to 640. Never below 1, and never so far that the frame has no pixels left.
 */
internal fun trickplaySampleSize(
    width: Int,
    height: Int,
    targetWidth: Int = TRICKPLAY_CAPTURE_WIDTH
): Int {
    if (width <= 0 || height <= 0 || targetWidth <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= targetWidth) sample *= 2
    return sample
}

/**
 * Whether a frame the preview player is showing right now can be filed under
 * [bucketMs].
 *
 * The capture only asks this once the player has settled, which is what makes it
 * meaningful: the player can still be showing the previous moment while it
 * finishes the seek, and that frame must not be cached under the bucket that
 * asked for the seek.
 */
internal fun trickplayFrameFits(
    bucketMs: Long,
    positionMs: Long,
    windowMs: Long = TRICKPLAY_ACCEPT_WINDOW_MS
): Boolean = abs(positionMs - bucketMs) <= windowMs

/**
 * Whether an idle release may give up waiting for a capture now.
 *
 * It may not while a capture is in flight, and that is not a detail. The idle
 * release is armed the moment the viewer lets go of the scrub - usually a beat
 * before the frame they asked for has arrived - and giving up there cancels the
 * request and its timeout together: the frame is neither delivered nor counted
 * as a failure, so the press leaves no card, no notice and no line in the
 * report, which from the outside is indistinguishable from a feature that was
 * never asked for. The first frame of a session is the slowest there is (a seek
 * into a stream still opening, a surface to settle), so that is the press it
 * always cost.
 *
 * Waiting is bounded, not open-ended: a capture in flight has its own
 * [TRICKPLAY_TIMEOUT_MS], so the deferral always ends and the pipeline still goes
 * idle when the viewer stops.
 */
internal fun trickplayMayRelease(awaitingFrame: Boolean): Boolean = !awaitingFrame

/**
 * Whether [failures] consecutive failures have used up the session's tries.
 *
 * A stream that is not rendering into a readable surface does not reach this:
 * it ends the previews at once, since that will not have changed by the next
 * press. What is left for this many tries is the seek that never settled and the
 * copy that came back empty.
 */
internal fun trickplayGivesUp(failures: Int): Boolean =
    failures >= TRICKPLAY_MAX_TRANSIENT_FAILURES

/**
 * The name of [playbackState], for the report.
 *
 * Null is the main player not being there to read, which is not the same answer
 * as `idle`: one is a pipeline that never had a player to capture from, the
 * other a player that is there and stopped.
 */
internal fun trickplayPlayerState(playbackState: Int?): String = when (playbackState) {
    null -> "none"
    Player.STATE_IDLE -> "idle"
    Player.STATE_BUFFERING -> "buffering"
    Player.STATE_READY -> "ready"
    Player.STATE_ENDED -> "ended"
    else -> "unknown"
}

/**
 * Why an extraction that ran out of time produced no frame.
 *
 * Every failure of the preview used to reach the report as one word — "timed
 * out" — and that word covers three faults with nothing in common: a player
 * that never reached the position, a surface that never handed over a frame, and
 * a seek that never settled on the position it was asked for. Each field below
 * separates them, and each is a number the pipeline already had:
 *
 *  - `player`, `loading` and `position`: a player still buffering somewhere
 *    short of `bucket` has not reached the frame, while one sitting ready at
 *    `bucket` did and produced nothing anyway. The two want opposite fixes.
 *  - `images`: zero means no frame was ever copied off the surface, which no
 *    amount of source health can fix; a count above zero means copies did
 *    arrive and were refused by the gates in the PixelCopy callback.
 *  - `error`: the player's own code, recorded so the timeout's reason can name
 *    it, so `none` here means the player never admitted to anything and the wait
 *    itself is the only evidence there is.
 */
internal fun trickplayTimeoutReason(
    timeoutMs: Long,
    playerState: String,
    loading: Boolean,
    positionMs: Long?,
    bucketMs: Long?,
    imagesSeen: Int,
    playerError: String?
): String = "timed out after ${timeoutMs}ms" +
    " (player=$playerState, loading=$loading, position=${positionMs ?: "none"}," +
    " bucket=${bucketMs ?: "none"}, images=$imagesSeen," +
    " error=${playerError ?: "none"})"

/**
 * The container hint for a stream whose type is not in its path.
 *
 * A rule that knows the extension-less shapes — a playlist whose `.m3u8` marker
 * lives only in the query, an HLS path with no file name at all — so a playlist
 * served as `.../stream?type=.m3u8` is not handed to the progressive extractors
 * as an unknown type and played from the wrong place. The MPV engine's side has
 * nothing to pass, because libmpv does its own probing.
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
 * The horizontal translation that centers something [cardWidth] wide on
 * [anchorX] inside a [contentWidth]-wide parent, without letting it hang off
 * either edge.
 *
 * The preview is laid out centered, so this is the offset that moves it over the
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
    // A card wider than the space it has: centered beats clamped to a nonsense
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
