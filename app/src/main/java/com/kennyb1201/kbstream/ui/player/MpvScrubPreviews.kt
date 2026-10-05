package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import java.io.File

/**
 * Scrub-preview frames for the libmpv engine, taken from the frame mpv is
 * already showing.
 *
 * ## Why this is not [TrickplayFrames]
 *
 * The two engines reach the same picture by different routes. The ExoPlayer path
 * seeks the main player and copies that player's own surface with a PixelCopy;
 * this engine can be ASKED for the picture it is displaying, so its preview is a
 * screenshot of the very frame the viewer is scrubbing over. Neither path opens
 * a second stream or decodes anything twice — only the mechanism differs.
 *
 * The command is `screenshot-to-file` because that is the only screenshot mpv
 * exposes to us: `MPVLib` binds `mpv_command` over a string array and nothing
 * that returns a value, so `screenshot-raw` — the raw pixels, which would avoid
 * the file entirely — is out of reach from Kotlin.
 *
 * ## Which frame this can be
 *
 * A screenshot is always the CURRENT position, so a preview is only possible
 * when the player is actually at the position being previewed. That is checked
 * with the same [trickplayFrameFits] rule the other engine uses, and for the
 * same reason: filing the previous moment under the bucket that asked for a
 * seek is worse than showing nothing, because it looks authoritative.
 *
 * On this engine that splits the two ways a viewer scrubs:
 *
 *  - the remote's held LEFT/RIGHT seeks as it goes, so the player arrives at
 *    every position and each one can be previewed;
 *  - the seek bar deliberately does not seek until the finger comes up (see
 *    `MpvPlayerActivity`), so during a drag the player is still somewhere else
 *    and there is nothing honest to capture. Those presses wait
 *    ([deferToPlayer]) and the frame lands when the release seeks — the moment
 *    the viewer is looking at the bar rather than at the picture.
 *
 * ## What a frame costs
 *
 * A screenshot is a full-resolution file — 3840x2160 for a 4K release — so it is
 * written as JPEG (for `screenshot-to-file` the extension is what picks the
 * format), into the app's own cache, decoded down to
 * [TRICKPLAY_CAPTURE_WIDTH] ([trickplaySampleSize]), and deleted as soon as it
 * has been read. What survives a frame is a ~0.5 MB bitmap in the session's
 * frame cache and nothing on disk.
 *
 * Call [request] while the viewer scrubs and [idle] when they stop; [release] on
 * the way out of the activity. [onUnavailable] is called once if the pipeline
 * gives up for good, so the screen can say so rather than leaving the viewer
 * pressing RIGHT at nothing.
 */
internal class MpvScrubPreviews(
    private val activity: Activity,
    /**
     * The playhead as the engine currently reports it, sampled per call.
     *
     * A lambda, not a snapshot: the whole gate here is whether the player has
     * ARRIVED at the position being previewed, and that changes underneath a
     * single scrub — it is what a deferred attempt is waiting for.
     */
    private val playerPositionMs: () -> Long,
    /** Asks the engine to write the frame it is showing to [path]. */
    private val captureTo: (path: String) -> Unit,
    private val onUnavailable: (reason: String) -> Unit = {},
    private val onFrame: (bucketMs: Long, frame: Bitmap) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val cache = TrickplayFrameCache<Bitmap>(TRICKPLAY_CACHE_FRAMES)

    /**
     * Dedicated thread for the screenshot command.
     *
     * `mpv.command` is synchronous and writes a full-resolution (up to 4K) JPEG
     * plus the file, so running it on the main thread blocked the UI for a full
     * encode on every scrub step. Daemon so a stuck capture can never keep the
     * process alive.
     */
    private val captureExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-scrub-capture").apply { isDaemon = true }
    }

    /**
     * Serialises the native screenshot against [release]: [release] sets
     * [released] and then waits on this lock, so an in-flight capture always
     * finishes before mpv is torn down (a command against a destroyed handle is
     * the crash this exists to avoid).
     */
    private val captureLock = Any()

    /** The bucket being dragged over now; see [TrickplayFrames.wantedBucket]. */
    private var wantedBucket: Long? = null

    private var inFlightBucket: Long? = null
    private var awaitingCapture = false
    private var askedAtMs = 0L

    /** The file the capture in flight is being written to, if any. */
    private var pendingFile: File? = null

    /** How many times the capture file has been looked for so far. */
    private var captureTries = 0

    /** The bucket waiting for the player to arrive, and the deadline for that. */
    private var deferredBucket: Long? = null
    private var deferUntilMs = 0L
    private var deferRecorded = false

    private var failures = 0
    private var disabled = false
    private var released = false

    /** False once the session has given up, so callers can stop asking. */
    val isUsable: Boolean get() = !disabled && !released

    /**
     * Asks for the frame covering [positionMs]. A frame already captured for
     * that bucket is delivered immediately and for no screenshot at all, which
     * is what makes dragging back over ground already covered free.
     */
    fun request(positionMs: Long, durationMs: Long) {
        if (disabled || released) return
        if (durationMs <= 0L) return
        val bucket = trickplayBucket(positionMs).coerceAtMost(durationMs - 1L)
        wantedBucket = bucket
        cancelIdleRelease()
        cache.get(bucket)?.let { frame ->
            onFrame(bucket, frame)
            return
        }
        attempt()
    }

    /**
     * The scrub is over. The pipeline is kept for
     * [TRICKPLAY_IDLE_RELEASE_MS] first: scrubbing is a series of presses with
     * gaps between them, and a frame captured a moment ago is the one the next
     * press will ask for.
     */
    fun idle() {
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, TRICKPLAY_IDLE_RELEASE_MS)
    }

    /** Tears the pipeline down for good. Idempotent. */
    fun release() {
        released = true
        handler.removeCallbacks(idleRelease)
        // Wait out any in-flight native screenshot before mpv is torn down, so
        // the capture thread cannot issue a command against a dead handle.
        synchronized(captureLock) { }
        teardown()
        captureExecutor.shutdown()
    }

    // --- Capture ------------------------------------------------------------

    /**
     * The capture step, spelled `: Unit` on purpose.
     *
     * It and [deliver] call each other — a capture that lands asks for the next
     * bucket, one that fails can retry the same one — so neither return type can
     * be inferred from the other. The compiler asks for exactly this annotation;
     * everywhere else in this file the type is left to inference.
     */
    private fun attempt(): Unit {
        if (disabled || released || awaitingCapture) return
        val bucket = wantedBucket ?: return
        cache.get(bucket)?.let { frame ->
            onFrame(bucket, frame)
            return
        }
        // The screenshot is the current position, so the player has to be there.
        // Anything else would file the moment before the requested one.
        if (!trickplayFrameFits(bucket, playerPositionMs())) {
            deferToPlayer(bucket)
            return
        }
        cancelDefer()

        inFlightBucket = bucket
        awaitingCapture = true
        askedAtMs = SystemClock.uptimeMillis()
        captureTries = 0

        // The directory has to exist before mpv is asked to write into it: a
        // screenshot aimed at a path whose parent is missing writes nothing and
        // says nothing, which from here is indistinguishable from a decoder that
        // cannot be read back.
        val dir = captureDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            fail(bucket, "no cache directory to write a capture to")
            return
        }
        val file = File(dir, "$bucket.jpg")
        runCatching { file.delete() }
        pendingFile = file

        // Off the main thread: the screenshot is a full-resolution JPEG encode
        // plus a file write, and doing it here stuttered playback on every
        // scrub step. The outcome is posted back to the main handler.
        captureExecutor.execute {
            val error = synchronized(captureLock) {
                if (released) return@execute
                runCatching { captureTo(file.absolutePath) }.exceptionOrNull()
            }
            handler.post {
                if (released || !awaitingCapture) return@post
                if (error != null) {
                    fail(bucket, "the screenshot command failed: ${error.message}")
                    return@post
                }
                // mpv writes the file inside the command, but that is not a
                // promise the bytes are all there the instant the call returns,
                // so the first look is given a beat and a second one (see
                // [captureRead]).
                handler.postDelayed(captureRead, CAPTURE_READ_DELAY_MS)
                handler.postDelayed(timeoutRunnable, TRICKPLAY_TIMEOUT_MS)
            }
        }
    }

    // Typed explicitly because it re-posts itself: the type of an initializer
    // that refers to its own property cannot be inferred from the body.
    private val captureRead: Runnable = Runnable {
        if (!awaitingCapture) return@Runnable
        val bucket = inFlightBucket ?: return@Runnable
        val file = pendingFile
        val frame = file?.let { decodeCapture(it) }
        if (frame == null) {
            if (captureTries < CAPTURE_READ_ATTEMPTS) {
                captureTries++
                handler.postDelayed(captureRead, CAPTURE_READ_DELAY_MS)
                return@Runnable
            }
            fail(bucket, "the screenshot command wrote no readable image")
            return@Runnable
        }
        deliver(bucket, frame)
    }

    private val timeoutRunnable = Runnable {
        if (awaitingCapture) {
            fail(inFlightBucket, "no capture within ${TRICKPLAY_TIMEOUT_MS}ms")
        }
    }

    private fun deliver(bucket: Long, frame: Bitmap) {
        awaitingCapture = false
        inFlightBucket = null
        cancelDefer()
        clearCapture()
        // Into the diagnostics trace as well as onto the screen: "I never see a
        // thumbnail" is answered differently by "none was ever captured" and
        // "they were captured and never shown", and neither is visible from
        // outside the app.
        PerfTrace.record("trickplay.decode:mpv", SystemClock.uptimeMillis() - askedAtMs)
        // A frame is proof the capture works, so whatever failed earlier was
        // transient and the budget starts over.
        failures = 0
        cache.put(bucket, frame)
        if (bucket == wantedBucket) onFrame(bucket, frame)
        attempt()
    }

    /**
     * Records a failed capture for [bucket]. Nothing is in flight afterwards,
     * which is what lets the next request start one.
     *
     * A screenshot the device will not produce — the known case is a hardware
     * decoder path that renders straight to the display, leaving mpv no frame it
     * can read back — does not change between presses, so the ordinary failure
     * budget turns it into "no previews this session" and says why. That is the
     * same trade the other engine makes: the frame is a nicety and playback is
     * not, and here there is not even a connection to lose.
     */
    private fun fail(bucket: Long?, reason: String) {
        if (!awaitingCapture) return
        awaitingCapture = false
        inFlightBucket = null
        cancelDefer()
        clearCapture()
        failures++
        PerfTrace.record("trickplay.miss", SystemClock.uptimeMillis() - askedAtMs, ok = false)
        PerfTrace.record("trickplay.reason:$reason", 0L, ok = false)
        Log.w(TAG, "no preview frame: $reason (failure $failures)")
        if (trickplayGivesUp(failures)) {
            Log.i(TAG, "scrub previews off for this session: $reason")
            PerfTrace.record("trickplay.off", 0L, ok = false)
            disabled = true
            teardown()
            onUnavailable(TRICKPLAY_NO_FRAMES_NOTICE)
            return
        }
        if (wantedBucket != bucket) attempt()
    }

    /**
     * [bucket] cannot be captured yet because the player is not at it.
     *
     * Not a failure and not counted as one: nothing was attempted and nothing
     * was spent, and on the seek bar path this is the normal state of an
     * in-progress drag — the player is only moved when the finger comes up, so
     * the frame arrives with that release rather than mid-drag. Waiting is
     * bounded by [TRICKPLAY_WAIT_MS], the same window the card is armed for, so
     * a wait that outlives the viewer's attention ends with it.
     */
    private fun deferToPlayer(bucket: Long) {
        inFlightBucket = null
        awaitingCapture = false
        val now = SystemClock.uptimeMillis()
        if (deferredBucket != bucket) {
            deferredBucket = bucket
            deferUntilMs = now + TRICKPLAY_WAIT_MS
            // Once a session, not once per check: this is re-evaluated several
            // times a second while the viewer drags, and one line per check
            // would push every other sample out of the trace ring.
            if (!deferRecorded) {
                deferRecorded = true
                PerfTrace.record(
                    "trickplay.defer:not-at-position",
                    (bucket - playerPositionMs()).coerceAtLeast(0L),
                    ok = false
                )
            }
        }
        handler.removeCallbacks(retry)
        if (now < deferUntilMs) handler.postDelayed(retry, TRICKPLAY_RETRY_MS)
    }

    private val retry = Runnable { attempt() }

    /** Stops waiting for the player: something else is happening now. */
    private fun cancelDefer() {
        deferredBucket = null
        deferUntilMs = 0L
        handler.removeCallbacks(retry)
    }

    /**
     * The decode of a capture, at a size the card can actually use.
     *
     * Full resolution is never decoded: a 4K screenshot is a ~31 MB bitmap on a
     * box whose whole Java heap is 192 MB, and the card draws it 480px wide.
     * Bounds first, then the power-of-two fraction that lands closest to
     * [TRICKPLAY_CAPTURE_WIDTH] without going under it.
     */
    private fun decodeCapture(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = trickplaySampleSize(bounds.outWidth, bounds.outHeight)
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun captureDir(): File = File(activity.cacheDir, CAPTURE_DIR)

    /** Removes the file a capture was written to; it is scratch, not a cache. */
    private fun clearCapture() {
        handler.removeCallbacks(captureRead)
        handler.removeCallbacks(timeoutRunnable)
        pendingFile?.let { file -> runCatching { file.delete() } }
        pendingFile = null
    }

    private val idleRelease = Runnable { releaseIfIdle() }

    /**
     * Gives the captured frames back — but never while one is being taken.
     *
     * Re-armed rather than dropped when a capture is in flight: a teardown that
     * lands on one cancels the request and its timeout together, so the frame is
     * neither delivered nor counted, and the press leaves no card, no notice and
     * no line in the report anywhere. What is being held meanwhile is a handful
     * of bitmaps, not a decoder, so the wait costs memory rather than playback.
     */
    private fun releaseIfIdle() {
        if (!trickplayMayRelease(awaitingCapture)) {
            handler.postDelayed(idleRelease, TRICKPLAY_IDLE_RELEASE_MS)
            return
        }
        teardown()
    }

    private fun cancelIdleRelease() = handler.removeCallbacks(idleRelease)

    private fun teardown() {
        awaitingCapture = false
        inFlightBucket = null
        cancelDefer()
        clearCapture()
        cache.clear()
    }

    private companion object {
        const val TAG = "PLAYER_TRICKPLAY"

        /** Where captures are written; scratch only, inside the app cache. */
        const val CAPTURE_DIR = "scrub_previews"

        /**
         * How long to give mpv to have the file on disk, and how many looks.
         *
         * The file is written inside the command, so the first look nearly
         * always finds it; three looks over 120 ms cover the rest without
         * holding the viewer's press for anything they would notice.
         */
        const val CAPTURE_READ_DELAY_MS = 60L
        const val CAPTURE_READ_ATTEMPTS = 2
    }
}
