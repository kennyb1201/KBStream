package com.kennyb1201.kbstream.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kennyb1201.kbstream.data.reporting.PerfTrace

/**
 * Scrub-preview frames, copied out of the MAIN player's own video surface.
 *
 * ## Why this is a surface copy and not a second decoder
 *
 * The previous build decoded previews with a second `ExoPlayer` and a
 * `YUV_420_888` `ImageReader`. On this app's boxes that is the wrong trade three
 * times over: it costs a second video decoder (several of these devices have
 * barely enough for one stream - see the note in PlayerRebuild), a second
 * connection to a source that often allows exactly one (debrid links), and a
 * capture surface whose format and size a hardware decoder has to accept. A
 * field report is what the last one costs: the preview decoder queued input
 * (`QIB`) and never released an output buffer (`ROB`), so the player sat in
 * `STATE_BUFFERING` with `images=0` and every press timed out - and
 * `nBufferCountActual` was refused at 11, 10 and 9 buffers, i.e. the codec
 * wanted a queue an `ImageReader` cannot be built with at 4K.
 *
 * A surface copy asks none of that. The player is ALREADY rendering the
 * scrubbed position (a scrub seeks it - see the hold-to-scrub runnable in
 * NativePlayerActivity), so the frame the viewer wants is on screen, and
 * [PixelCopy] reads it. No second decoder, no second connection, no capture
 * surface, and nothing held between presses: [idle] and [release] have no
 * decoder to give back any more.
 *
 * ## What it deliberately trades away
 *
 * A preview can only show a position the player is actually at. That is exactly
 * right for a remote scrub, which moves the player to every position it asks
 * about. A touch drag on the seek bar does not - it seeks once, on release - so
 * a drag produces no preview (recorded as a decline, never as a fault, so it
 * cannot spend the failure budget that a real capture fault needs). The
 * TextureView fallback has no `Surface` to read and is the same case.
 *
 * Call [request] while the viewer scrubs, [idle] when they stop and [release] on
 * the way out of the activity. [onUnavailable] is called once if the pipeline
 * gives up for good, so the screen can say why rather than leaving the viewer
 * pressing RIGHT at nothing.
 */
@UnstableApi
internal class TrickplayFrames(
    /**
     * The player whose surface is read, sampled per call rather than held: the
     * activity rebuilds its player on a source switch, and a preview that kept
     * the dead one would copy a surface nothing is rendering into any more.
     */
    private val player: () -> ExoPlayer?,
    /**
     * The surface the picture is going to right now, or null when there is none
     * to read (no surface yet, or the TextureView fallback).
     */
    private val surface: () -> Surface?,
    private val onUnavailable: (reason: String) -> Unit = {},
    private val onFrame: (bucketMs: Long, frame: Bitmap) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val cache = TrickplayFrameCache<Bitmap>(TRICKPLAY_CACHE_FRAMES)

    /**
     * The bucket being dragged over now. A frame that arrives for anything else
     * is still worth keeping (the viewer may drag back), but it is not what they
     * are looking at, so it is not delivered.
     */
    private var wantedBucket: Long? = null

    /** The bucket a capture is running for, if any. */
    private var inFlightBucket: Long? = null

    /** True between asking for a position and either a frame or a failure. */
    private var awaitingFrame = false

    /** Uptime when the capture now in flight was asked for, for its latency. */
    private var askedAtMs = 0L

    /** True while a [PixelCopy] is outstanding, so only one is ever in flight. */
    private var copyInFlight = false

    private var failures = 0
    private var disabled = false
    private var released = false

    /**
     * How many captures have come back this session.
     *
     * The one number that separates the two halves of "no thumbnail": a copy
     * that never arrived versus one that arrived and was refused by the gates
     * in [onCopyFinished]. From outside the app those two are the same sentence.
     */
    private var capturesSeen = 0

    /** The last error the MAIN player admitted to, for [timeoutReason]. */
    private var lastPlayerError: String? = null

    /** The player the listener below is attached to, so a rebuild re-attaches. */
    private var listenerPlayer: ExoPlayer? = null

    /** False once the session has given up, so callers can stop asking. */
    val isUsable: Boolean get() = !disabled && !released

    /**
     * Asks for the frame covering [positionMs]. A frame already captured for
     * that bucket is delivered immediately and for no capture cost at all, which
     * is what makes dragging back over ground already covered free.
     */
    fun request(positionMs: Long, durationMs: Long) {
        if (disabled || released) return
        if (durationMs <= 0L) return
        val bucket = trickplayBucket(positionMs).coerceAtMost(durationMs - 1L)
        wantedBucket = bucket
        cache.get(bucket)?.let { frame ->
            onFrame(bucket, frame)
            return
        }
        startNext()
    }

    /**
     * The scrub is over. Nothing is held open any more - a surface copy owns no
     * decoder and no connection - so there is nothing to give back and nothing
     * to keep warm. Kept as a call so the player's scrub lifecycle has one
     * vocabulary whether or not the implementation had a resource to release.
     */
    fun idle() = Unit

    /** Tears the pipeline down for good. Idempotent. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        listenerPlayer?.removeListener(listener)
        listenerPlayer = null
    }

    // --- Capture --------------------------------------------------------------

    private fun startNext() {
        if (disabled || released || awaitingFrame) return
        val bucket = wantedBucket ?: return
        if (cache.get(bucket) != null) return
        val active = player() ?: return
        attachListener(active)
        inFlightBucket = bucket
        awaitingFrame = true
        askedAtMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(settleCheck)
        handler.post(settleCheck)
        handler.postDelayed(timeoutRunnable, TRICKPLAY_TIMEOUT_MS)
    }

    /**
     * Waits for the player to be showing the position that was asked for.
     *
     * The scrub seeks the player, but a seek does not land instantly - and on a
     * long-GOP release the nearest keyframe can be seconds from the target - so
     * the frame is only worth copying once the player has settled there. Polling
     * rather than a player callback because the condition is three things the
     * player reports separately (state, loading, position) and none of their
     * callbacks fires for all three.
     */
    private val settleCheck = object : Runnable {
        override fun run() {
            if (disabled || released || !awaitingFrame) return
            if (copyInFlight) {
                handler.postDelayed(this, SETTLE_POLL_MS)
                return
            }
            if (!settledAt(inFlightBucket)) {
                handler.postDelayed(this, SETTLE_POLL_MS)
                return
            }
            capture()
        }
    }

    private fun settledAt(bucket: Long?): Boolean {
        if (bucket == null) return false
        val active = player() ?: return false
        if (active.playbackState != Player.STATE_READY) return false
        if (active.isLoading) return false
        return trickplayFrameFits(bucket, active.currentPosition)
    }

    /**
     * Copies the surface into a card-sized bitmap.
     *
     * The destination size is the card's, sized from the player's own reported
     * video size so a scope release is not stretched - [PixelCopy] scales the
     * whole source into the destination, so the copy is cheap and the app never
     * allocates a full-resolution frame for a thumbnail.
     */
    private fun capture() {
        val bucket = inFlightBucket ?: return
        val source = surface()
        if (source == null || !source.isValid) {
            fail(bucket, "no video surface to copy")
            return
        }
        val size = captureSize()
        val dest = runCatching {
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run {
            fail(bucket, "no bitmap for the capture")
            return
        }
        copyInFlight = true
        try {
            // The 4-arg Surface overload. The null-Rect 5-arg variant also
            // exists from API 26 (the app's floor), but passing the whole
            // surface is what this capture wants anyway, so the narrower form
            // is both correct and the call this code intends.
            PixelCopy.request(source, dest, { result ->
                copyInFlight = false
                // A copy that lands after the request was abandoned (a newer
                // press, a timeout, teardown) is dropped rather than delivered
                // under a bucket it does not belong to.
                val stillWanted = awaitingFrame &&
                    inFlightBucket == bucket &&
                    !released &&
                    !disabled
                when {
                    result == PixelCopy.SUCCESS && stillWanted -> deliver(bucket, dest)
                    result == PixelCopy.SUCCESS -> dest.recycle()
                    stillWanted -> {
                        dest.recycle()
                        fail(bucket, "pixel copy failed (result=$result)")
                    }
                    else -> dest.recycle()
                }
            }, handler)
        } catch (t: Throwable) {
            copyInFlight = false
            dest.recycle()
            fail(bucket, "pixel copy could not start: ${t.message}")
        }
    }

    private fun captureSize(): android.util.Size {
        val video = player()?.videoSize
        if (video == null || video.width <= 0 || video.height <= 0) {
            return android.util.Size(TRICKPLAY_CAPTURE_WIDTH, TRICKPLAY_CAPTURE_HEIGHT)
        }
        val height = TRICKPLAY_CAPTURE_WIDTH * video.height / video.width
        return android.util.Size(
            TRICKPLAY_CAPTURE_WIDTH,
            height.coerceIn(1, TRICKPLAY_CAPTURE_WIDTH)
        )
    }

    private fun deliver(bucket: Long, frame: Bitmap) {
        awaitingFrame = false
        inFlightBucket = null
        capturesSeen++
        handler.removeCallbacks(timeoutRunnable)
        // Into the diagnostics trace as well as onto the screen: "I never see a
        // thumbnail" is answered differently by "none was ever captured" and
        // "they were captured and never shown", and neither is visible from the
        // outside of the app.
        PerfTrace.record("trickplay.decode", SystemClock.uptimeMillis() - askedAtMs)
        // A frame is proof the surface reads, so whatever failed earlier was
        // transient and the budget starts over.
        failures = 0
        cache.put(bucket, frame)
        if (bucket == wantedBucket) onFrame(bucket, frame)
        startNext()
    }

    /**
     * Records a failed capture for [bucket]. Nothing is in flight afterwards,
     * which is what lets the next request start one.
     *
     * [permanent] is the difference between "this device will never hand the
     * preview a surface" and "this attempt did not work": the first ends the
     * session's previews there and then, the second is left to the viewer's next
     * press.
     */
    private fun fail(bucket: Long?, reason: String, permanent: Boolean = false) {
        if (!awaitingFrame) return
        awaitingFrame = false
        inFlightBucket = null
        handler.removeCallbacks(timeoutRunnable)
        failures++
        PerfTrace.record(
            "trickplay.miss",
            SystemClock.uptimeMillis() - askedAtMs,
            ok = false
        )
        // The reason goes into the report as well as the log: "no thumbnails"
        // is answered differently by a surface that never gave a frame, a copy
        // that was refused, and a seek that never settled, and none of those
        // three is visible from outside the app.
        PerfTrace.record("trickplay.reason:$reason", 0L, ok = false)
        Log.w(TAG, "no preview frame: $reason (failure $failures)")
        if (permanent || trickplayGivesUp(failures)) {
            Log.i(TAG, "scrub previews off for this session: $reason")
            PerfTrace.record("trickplay.off", 0L, ok = false)
            disabled = true
            release()
            onUnavailable(
                if (permanent) NO_SURFACE_NOTICE else TRICKPLAY_NO_FRAMES_NOTICE
            )
            return
        }
        if (wantedBucket != bucket) startNext()
    }

    /**
     * The extraction in flight produced nothing in its budget.
     *
     * A timeout where the player never reached the requested position is NOT a
     * capture fault: the preview simply cannot exist yet, because the main
     * player is not showing that moment (a seek-bar drag only seeks on release,
     * which is the whole case). That is recorded as a decline - visible in a
     * report - and must not spend the failure budget, or one drag would turn
     * previews off for the rest of the session.
     */
    private val timeoutRunnable: Runnable = Runnable {
        if (!awaitingFrame || copyInFlight) return@Runnable
        val bucket = inFlightBucket
        val position = player()?.currentPosition
        if (bucket != null && (position == null || !trickplayFrameFits(bucket, position))) {
            awaitingFrame = false
            inFlightBucket = null
            handler.removeCallbacks(timeoutRunnable)
            PerfTrace.record(
                "trickplay.decline:the player never reached the position",
                0L,
                ok = false
            )
            if (wantedBucket != bucket) startNext()
            return@Runnable
        }
        fail(bucket, timeoutReason())
    }

    /**
     * Why the capture in flight produced nothing, in the shape
     * [trickplayTimeoutReason] documents.
     *
     * `images` is this build's capture count rather than an ImageReader's: zero
     * means nothing was ever copied off the surface, which no source health can
     * fix, while a count above zero means frames did arrive and were refused by
     * the gates in [onCopyFinished]'s caller.
     */
    private fun timeoutReason(): String = trickplayTimeoutReason(
        timeoutMs = TRICKPLAY_TIMEOUT_MS,
        playerState = trickplayPlayerState(player()?.playbackState),
        loading = player()?.isLoading == true,
        positionMs = player()?.currentPosition,
        bucketMs = inFlightBucket,
        imagesSeen = capturesSeen,
        playerError = lastPlayerError
    )

    /**
     * Aptly named for what it does here: the main player's error is playback's
     * problem, not the preview's, so an extraction is NOT failed on it - it is
     * recorded only, so a timeout's reason can name the player's own code.
     */
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            lastPlayerError = error.errorCodeName
        }
    }

    private fun attachListener(active: ExoPlayer) {
        if (listenerPlayer === active) return
        listenerPlayer?.removeListener(listener)
        active.addListener(listener)
        listenerPlayer = active
    }

    private companion object {
        const val TAG = "PLAYER_TRICKPLAY"

        /** How often the settle gate is re-checked while a frame is owed. */
        const val SETTLE_POLL_MS = 120L

        /**
         * Said in the app, once, when previews can never work this session.
         *
         * A silent feature and a broken one look identical on a TV, and the two
         * reasons a frame cannot be produced here - no readable surface, or a
         * capture that keeps coming back empty - want opposite fixes. So the
         * screen is told, not only the log.
         */
        const val NO_SURFACE_NOTICE =
            "Scrub previews need the video surface, and this stream is not rendering into one this app can read"
    }
}
