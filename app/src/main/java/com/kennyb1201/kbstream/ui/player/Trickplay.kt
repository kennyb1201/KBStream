package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
 * One preview frame per this much program.
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
 * The size a preview frame is captured at: 16:9, a sixth of a 1080p screen,
 * ~0.5 MB a frame.
 *
 * Shared by both engines because both feed the same card, and the two reach it
 * differently. The secondary decoder is TOLD this size - the capture surface is
 * 480x270, so a frame costs a bitmap copy rather than a full-size frame at
 * LAN-bitrate cost. libmpv cannot be told anything of the sort: a screenshot is
 * the file's own resolution, so [trickplaySampleSize] decodes down to here
 * instead.
 */
internal const val TRICKPLAY_CAPTURE_WIDTH = 480
internal const val TRICKPLAY_CAPTURE_HEIGHT = 270

/**
 * What the viewer is told when previews were attempted and produced nothing.
 *
 * Shared by both engines, because it is the same sentence: a viewer on a TV
 * cannot tell a silent feature from a broken one, and "were any frames ever
 * produced" is a question the diagnostics answer either way. The secondary
 * decoder has a second notice of its own — a decoder the device could not spare
 * is a different fault with a different fix — and libmpv has none, since it has
 * no second decoder to report.
 */
internal const val TRICKPLAY_NO_FRAMES_NOTICE =
    "No scrub preview thumbnails were available for this video"

/**
 * How much of the program the MAIN player must have already read past
 * [bucketMs] before the preview may decode it.
 *
 * This is the number that makes the preview cache-only, so it is a bound on
 * what the preview player is going to read, not a guess about the cache: the
 * preview seeks to the sync frame at or before the bucket, so its read starts
 * at or behind the bucket and runs forward from there. Four seconds of runway
 * covers filling the preview's own minimum buffer (1.5 s) from a cache hit on
 * an ordinary GOP, and it still fits inside the main player's own buffer - the
 * low-latency profile fills only 5 s ahead, so a wider requirement would leave
 * IPTV-shaped VOD with no previews at all. See [trickplayServableFromCache].
 */
internal const val TRICKPLAY_CACHE_RUNWAY_MS = 4_000L

/**
 * How often a preview that is waiting on the player looks again.
 *
 * Both engines wait, for the same reason and on the same clock. The secondary
 * decoder waits for the main player to have READ the bytes it wants (it only
 * ever decodes out of the disk cache - see [trickplayServableFromCache]);
 * libmpv waits for the seek it just issued to land, so that the frame it
 * screenshots is the position the viewer picked rather than the one before it.
 * Either way the wait is a second or so, and looking on a timer is what turns
 * "no thumbnail at the position you pressed for" into "the thumbnail for that
 * position, once the player has it anyway".
 */
internal const val TRICKPLAY_RETRY_MS = 400L

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
 * The preview player is asked for the nearest keyframe, so it does not land on
 * the bucket at all: it lands on the sync frame nearest to it, which on a
 * long-GOP release is half a GOP away — five seconds on a ten-second GOP, and
 * the previous four-second window refused every one of those as "not the
 * position that was asked for". Two presses of such a stream were therefore
 * enough to turn previews off for the rest of the film.
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
 * A decoder the device cannot spare does not wait for this count: it ends the
 * session's previews on its first refusal, because it will not have changed by
 * the next press (see [trickplayPermanentError]). What is left for this many
 * tries is the source that would not serve a second connection, the seek that
 * never settled, the very first frame that had to build a player and open a
 * stream at the same time — failures a longer film simply stops having.
 *
 * It used to be two, which on a device whose first preview frame took longer
 * than the decode budget meant previews were off for the whole session before
 * the second press had even landed.
 */
internal const val TRICKPLAY_MAX_TRANSIENT_FAILURES = 6

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
 *
 * Measured from the moment the frame is *on screen*, not from the press: this is
 * the viewer's time to look at it. Charging it from the press instead took the
 * card away before a slow decode could draw, which from the outside is exactly
 * what "I never see a thumbnail" looks like.
 */
internal const val TRICKPLAY_SHOW_GRACE_MS = 3_000L

/**
 * How long the card stays armed from the press that asked for a frame.
 *
 * A whole decode budget plus the show window, because a frame that is still
 * inside its own timeout is still the frame the viewer asked for. This is the
 * other half of the fix above: the press arms the card for as long as a decode
 * is allowed to take, and the frame that arrives re-arms it for
 * [TRICKPLAY_SHOW_GRACE_MS] from the draw. The first frame of a session — which
 * has to build a second player, open a connection and fill a buffer — is the
 * slowest one there is, and it is the one a press-length window always lost.
 */
internal const val TRICKPLAY_WAIT_MS = TRICKPLAY_TIMEOUT_MS + TRICKPLAY_SHOW_GRACE_MS

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

/**
 * Where the main player has read to: the only part of the program the preview
 * may decode.
 *
 * [bufferedMs] is the end of the main player's buffer, and its bytes arrived
 * through the shared disk cache, so a read inside it is served from disk and
 * opens nothing. [durationMs] is only needed for the end of a file, where there
 * is no runway left to require (see [trickplayServableFromCache]).
 */
internal data class TrickplayWindow(
    val playheadMs: Long,
    val bufferedMs: Long,
    val durationMs: Long
)

/**
 * Whether [bucketMs] can be decoded out of the disk cache alone.
 *
 * Until this existed the preview read through the cache and *fell through to
 * upstream on a miss* - which is the second connection the feature is built to
 * avoid, on sources that allow exactly one (debrid links, usenet) and hang
 * rather than refuse. A field report is what that costs: five extractions, all
 * five of them at positions outside the cached range, every one of them ending
 * at the 6 s budget with `player=buffering images=0` and two `ERROR_CODE_TIMEOUT`
 * - no thumbnail ever produced, the main player's own loads starved while they
 * tried (`stalls=5/6707ms`, `http` worst 9.6 s), and "scrubbing takes forever to
 * load back up".
 *
 * So the rule is not "prefer the cache" but "the cache or nothing": a bucket is
 * decoded only when the main player has already read it, and anything else is
 * left to arrive later ([TRICKPLAY_RETRY_MS]) or not at all. A viewer
 * scrubbing to a position the player has not reached yet gets the time bubble
 * they had before the feature existed, and never a stall to pay for it.
 */
internal fun trickplayServableFromCache(
    bucketMs: Long,
    window: TrickplayWindow,
    runwayMs: Long = TRICKPLAY_CACHE_RUNWAY_MS
): Boolean {
    // Ahead of the playhead was never asked for, and its bytes have not been
    // read at all on a file the player is still filling.
    if (bucketMs > window.playheadMs) return false
    // A file read through to the end has no runway left to give and needs none:
    // there is nothing past the end for a read to reach for.
    if (window.durationMs > 0L && window.bufferedMs >= window.durationMs) return true
    return window.bufferedMs - bucketMs >= runwayMs
}

/**
 * Whether the idle release may give the preview decoder back now.
 *
 * It may not while an extraction is in flight, and that is not a detail. The
 * release is armed the moment the viewer lets go of the scrub - usually a beat
 * before the frame they asked for has finished decoding - and a release that
 * lands on one cancels the request and its timeout together: the frame is
 * neither delivered nor counted as a failure, so the press leaves no card, no
 * notice and no line in the report, which from the outside is indistinguishable
 * from a feature that was never asked for. The first frame of a session is the
 * slowest there is (a player to build, a stream to open, a buffer to fill), so
 * that is the press it always cost.
 *
 * Waiting is bounded, not open-ended: a request in flight has its own
 * [TRICKPLAY_TIMEOUT_MS], so the deferral always ends and the decoder still goes
 * back when the viewer stops.
 */
internal fun trickplayMayRelease(awaitingFrame: Boolean): Boolean = !awaitingFrame

/**
 * Whether [failures] consecutive failures have used up the session's tries.
 *
 * Only failures that are the source's fault reach this: a decoder the device
 * cannot spare ends the previews at once, through [trickplayPermanentError].
 */
internal fun trickplayGivesUp(failures: Int): Boolean =
    failures >= TRICKPLAY_MAX_TRANSIENT_FAILURES

/**
 * Whether a playback error means the preview can never work in this session.
 *
 * A decoder the box cannot spare fails *configuration*, and that is not going to
 * change while the video holding the decoder keeps playing: retrying costs a
 * connection per press and reaches the same answer, so the pipeline stops
 * asking at once and says why. Everything else — a source that refused a second
 * connection, a format the extractors could not read — is worth another press.
 */
internal fun trickplayPermanentError(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> true

    else -> false
}

/**
 * The name of [playbackState], for the report.
 *
 * Null is a preview player that was never built, which is not the same answer as
 * `idle`: one is a pipeline that was never asked to decode anything, the other a
 * player that was built, opened something and stopped.
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
 * out" — and that word covers three faults with nothing in common: a source that
 * never gave the second player a byte, a capture surface the decoder never
 * rendered into, and a seek that never settled on the position it was asked for.
 * Each field below separates them, and each is a number the pipeline already
 * had:
 *
 *  - `player`, `loading` and `position`: a player still buffering somewhere
 *    short of `bucket` never got its data, while one sitting ready at `bucket`
 *    did and produced nothing anyway. The two want opposite fixes.
 *  - `images`: zero means the capture surface was never handed a frame at all,
 *    which no amount of source health can fix; a count above zero means frames
 *    did arrive and were refused by the gates in `onImageAvailable`.
 *  - `error`: the player's own code, which fails an extraction on the spot
 *    rather than at the timeout, so `none` here means the player never admitted
 *    to anything and the wait itself is the only evidence there is.
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
