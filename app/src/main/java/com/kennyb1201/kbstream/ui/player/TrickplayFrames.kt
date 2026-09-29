package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kennyb1201.kbstream.data.player.StreamDiskCache
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Decoded scrub-preview frames: a real frame of the stream the viewer is
 * dragging through, taken on demand.
 *
 * ## Why this is a second player
 *
 * There is no way to read the frame the main player is showing — it renders
 * into a `SurfaceView` the app cannot sample — so a preview has to be decoded
 * separately. That is the whole cost of the feature and the reason every rule
 * here is about giving the decoder back: a second video decoder plus a second
 * connection to the same source, held for as long as the viewer is scrubbing
 * and [TRICKPLAY_IDLE_RELEASE_MS] after they stop.
 *
 * Several boxes — Fire TV most of all, which is most of this app's installs —
 * have barely enough decoders to play one stream. So the pipeline is built to
 * lose quietly: a device that cannot spare the decoder is given up on at its
 * first refusal, a source that merely stumbled gets
 * [TRICKPLAY_MAX_TRANSIENT_FAILURES] presses before the session stops asking,
 * and either way it leaves the screen looking exactly as it did before (the
 * time bubble), because the frame is a nicety and playback is not.
 *
 * ## What it deliberately does not do
 *
 *  - **No addon stack.** The preview reads with the playback headers through the
 *    main player's `SimpleCache`, and none of the rest of its stack (the DV
 *    re-write, the HDR10+ stripping, the YouTube chunked source). The cache is
 *    not an optimization here but a requirement: without it the preview opens a
 *    SECOND connection to a source that is already serving the main player, and
 *    the hosts this app plays from - debrid links, usenet - commonly allow a
 *    link exactly one, in which case the connection does not fail but hangs,
 *    and no frame is ever produced. See [buildPreview].
 *  - **No audio.** Audio tracks are disabled in the track selection rather than
 *    muted, so the audio decoder is never instantiated at all.
 *  - **No HD.** Frames are captured at [TRICKPLAY_CAPTURE_WIDTH] and the
 *    decoder scales them down for us, so the capture costs a bitmap copy rather
 *    than a full-size frame at LAN-bitrate cost.
 *
 * Call [request] while the viewer scrubs and [idle] when they stop; [release]
 * on the way out of the activity. [onUnavailable] is called once if the pipeline
 * gives up for good, so the screen can say why rather than leaving the viewer
 * pressing RIGHT at nothing.
 */
@UnstableApi
internal class TrickplayFrames(
    private val activity: Activity,
    private val url: String,
    private val headers: Map<String, String>,
    private val resolvedMimeType: String? = null,
    private val onUnavailable: (reason: String) -> Unit = {},
    private val onFrame: (bucketMs: Long, frame: Bitmap) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val cache = TrickplayFrameCache<Bitmap>(TRICKPLAY_CACHE_FRAMES)

    private var reader: ImageReader? = null
    private var preview: ExoPlayer? = null

    /**
     * The bucket being dragged over now. A frame that arrives for anything else
     * is still worth keeping (the viewer may drag back), but it is not what they
     * are looking at, so it is not delivered.
     */
    private var wantedBucket: Long? = null

    /** The bucket an extraction is running for, if any. */
    private var inFlightBucket: Long? = null

    /** True between asking the player for a position and either frame or failure. */
    private var awaitingFrame = false

    /** Uptime when the extraction now in flight was asked for, for its latency. */
    private var askedAtMs = 0L

    private var failures = 0
    private var disabled = false
    private var released = false

    /**
     * How many capture images the reader has handed back this session.
     *
     * The one number that separates the two halves of "no thumbnail": a capture
     * that never arrived at all, meaning the surface the decoder was given
     * rendered nothing, versus a capture that did arrive and was refused by the
     * gates in [onImageAvailable]. From outside the app those two are the same
     * sentence, and they want opposite fixes.
     */
    private var imagesSeen = 0

    /** The last player error this session, for [timeoutReason]. */
    private var lastPlayerError: String? = null

    /** False once the session has given up, so callers can stop asking. */
    val isUsable: Boolean get() = !disabled && !released && url.isNotBlank()

    /**
     * Asks for the frame covering [positionMs]. A frame already decoded for that
     * bucket is delivered immediately and for no decoder cost at all, which is
     * what makes dragging back over ground already covered free.
     */
    fun request(positionMs: Long, durationMs: Long) {
        if (disabled || released) return
        if (url.isBlank() || durationMs <= 0L) return
        val bucket = trickplayBucket(positionMs).coerceAtMost(durationMs - 1L)
        wantedBucket = bucket
        cancelIdleRelease()
        cache.get(bucket)?.let { frame ->
            onFrame(bucket, frame)
            return
        }
        startNext()
    }

    /**
     * The scrub is over. The pipeline is kept warm for
     * [TRICKPLAY_IDLE_RELEASE_MS] first: scrubbing is a series of presses with
     * gaps between them, and rebuilding the player between each one would cost
     * a connection per press and show nothing.
     */
    fun idle() {
        handler.removeCallbacks(idleRelease)
        handler.postDelayed(idleRelease, TRICKPLAY_IDLE_RELEASE_MS)
    }

    /** Tears the pipeline down for good. Idempotent. */
    fun release() {
        released = true
        handler.removeCallbacks(idleRelease)
        handler.removeCallbacks(timeoutRunnable)
        teardown()
    }

    // --- Extraction ---------------------------------------------------------

    private fun startNext() {
        if (disabled || released || awaitingFrame) return
        val bucket = wantedBucket ?: return
        if (cache.get(bucket) != null) return
        inFlightBucket = bucket
        awaitingFrame = true
        askedAtMs = SystemClock.uptimeMillis()
        val player = runCatching { preview ?: buildPreview().also { preview = it } }
            .getOrElse { error ->
                fail(bucket, "could not build the preview player: ${error.message}")
                return
            }
        // Frames the reader is still holding are from the position BEFORE this
        // seek; capturing one would file the old moment under the new bucket.
        drainReader()
        if (player.currentMediaItem == null) {
            player.setMediaItem(mediaItem(), bucket)
            player.prepare()
        } else {
            // Same source: seek, do not re-open. During a drag this is the
            // difference between a frame per position and a reconnect per
            // position.
            player.seekTo(bucket)
        }
        player.play()
        handler.postDelayed(timeoutRunnable, TRICKPLAY_TIMEOUT_MS)
    }

    private fun buildPreview(): ExoPlayer {
        val agent = headers["User-Agent"] ?: headers["user-agent"] ?: DEFAULT_USER_AGENT
        val client = OkHttpClient.Builder()
            // Tighter than playback's: a frame that is not here in a couple of
            // seconds is not going to be here in time to be worth showing.
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(15L, TimeUnit.SECONDS)
            .build()
        val http = OkHttpDataSource.Factory(client).setUserAgent(agent)
        val extraHeaders = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .filterValues { it.isNotBlank() }
        if (extraHeaders.isNotEmpty()) http.setDefaultRequestProperties(extraHeaders)

        // Read through the SAME disk cache the main player is filling.
        //
        // This is the difference between a preview that works and one that never
        // can. The preview is a second player over the same URL, and a plain
        // OkHttp source means a SECOND CONNECTION to a source that is already
        // serving the first one - and the hosts this app plays from (debrid
        // links, usenet) routinely allow a link exactly one. That connection
        // does not fail, it hangs: no player error, no frame, nothing but the
        // extraction timeout. Which is exactly the field report - frames=0, two
        // failures ending at 6004ms and 6008ms against a 6000ms budget - while
        // the main player opened the same stream in 2058ms beside it.
        //
        // Through the cache, scrubbing near the playhead (which is what
        // scrubbing is) reads bytes the main player has already stored. No
        // second connection is opened at all, so there is nothing to contend
        // for; and outside the cached range the upstream still serves it, with
        // FLAG_IGNORE_CACHE_ON_ERROR so a cache problem can never be what stops
        // the frame.
        //
        // The cache key has to match the main player's or the entries are
        // missed rather than shared. Both wrap the same URL string with the
        // default, URI-keyed factory, which is why no CacheKeyFactory is set on
        // either side - see the matching CacheDataSource in
        // NativePlayerActivity.
        val cached = CacheDataSource.Factory()
            .setCache(StreamDiskCache.get(activity))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val imageReader = ImageReader.newInstance(
            TRICKPLAY_CAPTURE_WIDTH,
            TRICKPLAY_CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888,
            TRICKPLAY_CAPTURE_IMAGES
        )
        imageReader.setOnImageAvailableListener({ available -> onImageAvailable(available) }, handler)
        // The reader owns the surface it hands out; the player renders into it
        // and closing the reader is what releases the capture buffers.
        val frameSurface = imageReader.surface
        reader = imageReader

        // A player that fails to build must not leave the reader (and its two
        // capture buffers) behind: this is the path taken by the box that has
        // no decoder left, which is exactly the case that then tries again.
        return runCatching {
            ExoPlayer.Builder(activity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cached))
                // A preview needs the buffer at ONE position, not a runway: the
                // defaults would spend megabytes and seconds filling ahead of a
                // frame nobody is going to watch.
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                .build()
                .apply {
                    // The nearest keyframe, not the exact millisecond: decoding
                    // a whole GOP to land on a frame the viewer will look at
                    // for as long as they hold the button is work with no
                    // visible result.
                    setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        // Disabling the track keeps the audio decoder from being
                        // created at all, which is one fewer codec than muting.
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    setVideoSurface(frameSurface)
                    addListener(listener)
                }
        }.getOrElse { error ->
            runCatching { imageReader.close() }
            reader = null
            throw error
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            lastPlayerError = error.errorCodeName
            fail(
                inFlightBucket,
                "player error ${error.errorCodeName}",
                permanent = trickplayPermanentError(error.errorCode)
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Nothing to wait for: an end-of-stream while seeking means the
            // position asked for is not in this stream.
            if (playbackState == Player.STATE_ENDED) fail(inFlightBucket, "end of stream")
        }
    }

    /**
     * The one capture point.
     *
     * Every rendered frame arrives here, and the three gates are what make the
     * image trustworthy: the player has to have reached the bucket
     * ([trickplayFrameFits]), it has to have finished filling the buffer there
     * (not `isLoading`), and it has to be ready at all. Without them the first
     * image after a seek is the frame from *before* it — the position the viewer
     * just left — and caching that under the new bucket would show the wrong
     * moment for the rest of the session.
     */
    private fun onImageAvailable(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        imagesSeen++
        try {
            val bucket = inFlightBucket
            if (!awaitingFrame || bucket == null || !settledAt(bucket)) return
            val frame = runCatching { bitmapFrom(image) }.getOrNull() ?: return
            deliver(bucket, frame)
        } finally {
            image.close()
        }
    }

    private fun settledAt(bucket: Long): Boolean {
        val player = preview ?: return false
        if (player.playbackState != Player.STATE_READY) return false
        if (player.isLoading) return false
        return trickplayFrameFits(bucket, player.currentPosition)
    }

    private fun deliver(bucket: Long, frame: Bitmap) {
        awaitingFrame = false
        inFlightBucket = null
        handler.removeCallbacks(timeoutRunnable)
        // Into the diagnostics trace as well as onto the screen: "I never see a
        // thumbnail" is answered differently by "none was ever decoded" and
        // "they were decoded and never shown", and neither is visible from the
        // outside of the app.
        PerfTrace.record("trickplay.decode", SystemClock.uptimeMillis() - askedAtMs)
        // A frame is proof the second decoder exists and works, so whatever
        // failed earlier was transient and the budget starts over.
        failures = 0
        cache.put(bucket, frame)
        // Held, not playing: the decoder keeps this frame, and no more frames
        // (and so no more bandwidth) are spent until the viewer asks for
        // another position.
        preview?.pause()
        if (bucket == wantedBucket) onFrame(bucket, frame)
        startNext()
    }

    /**
     * Records a failed extraction for [bucket]. Nothing is in flight afterwards,
     * which is what lets the next request (or the retry below) start one.
     *
     * [permanent] is the difference between "this device will never hand the
     * preview a decoder" and "this attempt did not work": the first ends the
     * session's previews there and then, the second is left to the viewer's next
     * press.
     */
    private fun fail(bucket: Long?, reason: String, permanent: Boolean = false) {
        // Only a failure for something that was actually asked for counts. An
        // error surfacing while the pipeline is idle — a source that gave up on
        // its own during the idle window — is not the viewer's scrub failing.
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
        // is answered differently by a decoder the box would not hand out, a
        // source that refused the second connection, and a seek that never
        // settled, and none of those three is visible from outside the app.
        PerfTrace.record("trickplay.reason:$reason", 0L, ok = false)
        Log.w(TAG, "no preview frame: $reason (failure $failures)")
        if (permanent || trickplayGivesUp(failures)) {
            Log.i(TAG, "scrub previews off for this session: $reason")
            PerfTrace.record("trickplay.off", 0L, ok = false)
            disabled = true
            teardown()
            onUnavailable(if (permanent) NO_DECODER_NOTICE else NO_FRAMES_NOTICE)
            return
        }
        // Retried only when the viewer has already dragged somewhere else. A
        // failure on the bucket still being asked for is left to their next
        // press, so one slow position cannot spend the whole budget on its own.
        if (wantedBucket != bucket) startNext()
    }

    private val timeoutRunnable = Runnable {
        if (awaitingFrame) fail(inFlightBucket, timeoutReason())
    }

    /**
     * Why the extraction in flight has not produced a frame, in the shape
     * [trickplayTimeoutReason] documents.
     *
     * This string reaches the diagnostics line (as trickplay.reason), and it is
     * the only place that can tell the three cases apart: a player that never
     * settled at the position it was asked for, a capture surface that never
     * rendered anything ([imagesSeen] zero), and a source that never gave the
     * second player a frame to decode. A bare "timed out" leaves all three
     * looking identical in a report, which is what every scrub-preview report so
     * far has looked like.
     */
    private fun timeoutReason(): String = trickplayTimeoutReason(
        timeoutMs = TRICKPLAY_TIMEOUT_MS,
        playerState = trickplayPlayerState(preview?.playbackState),
        loading = preview?.isLoading == true,
        positionMs = preview?.currentPosition,
        bucketMs = inFlightBucket,
        imagesSeen = imagesSeen,
        playerError = lastPlayerError
    )

    /**
     * Gives the decoder back once the viewer has stopped scrubbing.
     *
     * Only when the pipeline is genuinely idle: the rule itself lives in
     * [releaseIfIdle], which is the one place that can be read for it.
     */
    private val idleRelease = Runnable { releaseIfIdle() }

    /**
     * Gives the decoder back - but never while a frame is still being decoded for
     * the viewer.
     *
     * Re-armed rather than dropped when an extraction is in flight: a release
     * that lands on one cancels the request and its timeout together, so the
     * frame is neither delivered nor counted, and the press leaves no card, no
     * notice and no line in the report anywhere (see [trickplayMayRelease]). The
     * extraction's own timeout bounds the wait, so this can hold the decoder for
     * no longer than one decode plus one idle window.
     */
    private fun releaseIfIdle() {
        if (!trickplayMayRelease(awaitingFrame)) {
            handler.postDelayed(idleRelease, TRICKPLAY_IDLE_RELEASE_MS)
            return
        }
        teardown()
    }

    private fun cancelIdleRelease() = handler.removeCallbacks(idleRelease)

    private fun teardown() {
        awaitingFrame = false
        inFlightBucket = null
        handler.removeCallbacks(timeoutRunnable)
        runCatching { preview?.setVideoSurface(null) }
        runCatching { preview?.release() }
        preview = null
        runCatching { reader?.close() }
        reader = null
    }

    /** Drops frames the reader is still holding, so a stale one cannot be used. */
    private fun drainReader() {
        val source = reader ?: return
        while (true) {
            val stale = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
            stale.close()
        }
    }

    /**
     * The captured image as a bitmap, cropping the row padding the capture
     * buffer comes with (its stride is padded to an alignment, and a bitmap
     * built from the raw buffer without accounting for it comes out skewed).
     */
    private fun bitmapFrom(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 4
        val padding = plane.rowStride - pixelStride * image.width
        if (padding <= 0) {
            return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(plane.buffer)
            }
        }
        val padded = Bitmap.createBitmap(
            image.width + padding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun mediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        trickplayMimeHint(url, resolvedMimeType)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private companion object {
        const val TAG = "PLAYER_TRICKPLAY"

        /**
         * Said in the app, once, when the previews cannot happen at all.
         *
         * A silent feature and a broken one look identical on a TV, and the two
         * reasons a frame cannot be produced here — no decoder to spare, or a
         * source that will not serve one — want opposite fixes. So the screen is
         * told, and not only the log.
         */
        const val NO_DECODER_NOTICE =
            "Scrub previews need a second video decoder and this device has none spare"
        const val NO_FRAMES_NOTICE =
            "No scrub preview thumbnails were available for this video"

        /** Capture size: 16:9, a sixth of a 1080p screen, ~0.5 MB a frame. */
        const val TRICKPLAY_CAPTURE_WIDTH = 480
        const val TRICKPLAY_CAPTURE_HEIGHT = 270

        /** Two, so a frame that arrives while one is being read is not dropped. */
        const val TRICKPLAY_CAPTURE_IMAGES = 2

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
