package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kennyb1201.kbstream.data.player.StreamDiskCache
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import java.io.IOException

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
 *  - **No addon stack.** The preview reads through the main player's
 *    `SimpleCache` and nothing else - no headers, and none of the rest of the
 *    player's stack (the DV re-write, the HDR10+ stripping, the YouTube chunked
 *    source). The cache is
 *    not an optimization here but a requirement: without it the preview opens a
 *    SECOND connection to a source that is already serving the main player, and
 *    the hosts this app plays from - debrid links, usenet - commonly allow a
 *    link exactly one, in which case the connection does not fail but hangs,
 *    and no frame is ever produced. See [buildPreview].
 *
 *    That requirement is enforced rather than intended: the preview decodes a
 *    bucket only once the main player has already read it
 *    ([trickplayServableFromCache]), so this pipeline opens no connection of
 *    its own, ever. A miss used to fall through to upstream, which is a second
 *    connection by definition - see that function for what it cost.
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
    private val resolvedMimeType: String? = null,
    /**
     * Where the main player has read to, sampled per call.
     *
     * A lambda rather than a snapshot because the answer changes underneath a
     * single scrub: the position the viewer pressed for is read by the main
     * player over the next second or two, and that arrival is exactly what the
     * deferred attempt in [deferForCache] is waiting for. Null means the main
     * player cannot be read at all right now, which is treated as "not yet".
     */
    private val cacheWindow: () -> TrickplayWindow?,
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

    /**
     * The bucket the pipeline is waiting for the cache to cover, if any, and
     * the deadline for that wait.
     *
     * Kept together because they are set and cleared together: a wait that
     * outlives its bucket would decode a frame for a position the viewer has
     * already left, and one that never expires would hold the retry timer for
     * the rest of the film.
     */
    private var deferredBucket: Long? = null
    private var deferUntilMs = 0L

    /** Whether the session has already reported waiting on the cache. */
    private var deferRecorded = false

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
        // Cache-only by construction: decode what the main player has already
        // read, and nothing else. Those bytes are on disk, so this read cannot
        // open the second connection the feature exists to avoid - and a bucket
        // that is not covered yet is waited for rather than fetched.
        val window = cacheWindow()
        if (window == null || !trickplayServableFromCache(bucket, window)) {
            deferForCache(bucket)
            return
        }
        cancelDefer()
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
        // Read through the SAME disk cache the main player is filling, and
        // NOTHING else.
        //
        // This is the difference between a preview that works and one that never
        // can. The preview is a second player over the same URL, and a plain
        // source means a SECOND CONNECTION to a source that is already serving
        // the first one - and the hosts this app plays from (debrid links,
        // usenet) routinely allow a link exactly one. That connection does not
        // fail, it hangs: no player error, no frame, nothing but the extraction
        // timeout - and, worse, it holds a read on the shared cache span the
        // main player is about to read, which is what leaves a scrub
        // "buffering forever" after the attempt.
        //
        // So the upstream does not merely go unused, it REFUSES: a cache miss
        // fails the extraction there and then instead of falling through to a
        // second connection the source may never give.
        // [trickplayServableFromCache] still admits a bucket only once the main
        // player has read past it, so an ordinary scrub near the playhead reads
        // bytes already on disk and never comes here at all.
        //
        // The cache key has to match the main player's or the entries are
        // missed rather than shared. Both wrap the same URL string with the
        // default, URI-keyed factory, which is why no CacheKeyFactory is set on
        // either side - see the matching CacheDataSource in
        // NativePlayerActivity.
        val cached = CacheDataSource.Factory()
            .setCache(StreamDiskCache.get(activity))
            .setUpstreamDataSourceFactory(CacheOnlyUpstream)

        // No capture surface yet. It is built in [ensureCaptureSurface] once the
        // stream's own size is known, because an ImageReader's buffers are
        // FIXED at creation: a decoder asked to render a 1080p frame into a
        // 480x270 reader never delivers a frame at all - the field report's
        // `player=buffering, loading=false, images=0` - and the reader's format
        // has to be the one a decoder surface is guaranteed to support
        // (YUV_420_888, not RGBA_8888). See [ensureCaptureSurface].
        return ExoPlayer.Builder(activity)
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
                addListener(listener)
            }
    }

    /**
     * Builds the capture surface for the stream's own video size, once.
     *
     * Two things about an ImageReader decide whether a decoder can render into
     * it, and both were wrong before this existed:
     *
     *  - **The format must be one a decoder surface supports.** Android's own
     *    decoder-to-ImageReader test uses [ImageFormat.YUV_420_888] and asserts
     *    the reader's format matches the codec's; RGBA_8888 is not a guaranteed
     *    decoder output anywhere. A reader in the wrong format is accepted by
     *    the codec and then never handed a buffer, which is a player stuck in
     *    `STATE_BUFFERING` with `isLoading == false`, no error, and
     *    `images=0` - exactly the report. So the reader is YUV_420_888 and
     *    [bitmapFrom] does the conversion itself.
     *  - **The size is fixed at creation.** The reader's buffers cannot be
     *    resized, so a reader smaller than the video gets no frames. It is
     *    therefore created at the video's decoded size (once media3 reports it)
     *    and [bitmapFrom] subsamples down to [TRICKPLAY_CAPTURE_WIDTH].
     */
    private fun ensureCaptureSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val existing = reader
        if (existing != null && existing.width == width && existing.height == height) {
            // Already the right size: just make sure the player running now is
            // rendering into it, since a player rebuilt after an idle teardown
            // arrives with no surface and would otherwise capture nothing.
            runCatching { preview?.setVideoSurface(existing.surface) }
            return
        }
        runCatching { reader?.close() }
        val imageReader = runCatching {
            ImageReader.newInstance(
                width,
                height,
                ImageFormat.YUV_420_888,
                TRICKPLAY_CAPTURE_IMAGES
            )
        }.getOrNull() ?: run {
            reader = null
            return
        }
        imageReader.setOnImageAvailableListener({ available -> onImageAvailable(available) }, handler)
        // The reader owns the surface it hands out; the player renders into it
        // and closing the reader is what releases the capture buffers.
        reader = imageReader
        // Swap it in under the running player, which reconfigures the decoder
        // onto the new surface; the next decoded frame lands in the reader.
        runCatching { preview?.setVideoSurface(imageReader.surface) }
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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // The stream's decoded size, known before the first frame, which is
            // the earliest a correctly sized capture surface can exist - see
            // [ensureCaptureSurface] for why it cannot be built any sooner.
            ensureCaptureSurface(videoSize.width, videoSize.height)
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
        cancelDefer()
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
        cancelDefer()
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
            onUnavailable(
                if (permanent) NO_DECODER_NOTICE else TRICKPLAY_NO_FRAMES_NOTICE
            )
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
     * The deferred check: the main player may have read past the bucket since the
     * last one, which is the only thing that can change the answer.
     *
     * It does not run through [startNext]'s guards by accident - the pipeline
     * being torn down, released or already busy all end it - and it cannot
     * outlive [deferUntilMs].
     */
    private val cacheRetry = Runnable { startNext() }

    /**
     * [bucket] is not decodable yet because the main player has not read it.
     *
     * Not a failure and not counted as one: nothing was attempted, nothing was
     * spent, and the bytes are usually a second away because the main player is
     * already fetching exactly that region - it seeked there when the viewer
     * pressed. So instead of failing the press, the pipeline waits, checking on
     * [TRICKPLAY_RETRY_MS] until [TRICKPLAY_WAIT_MS] has passed: the same
     * window the card is armed for, so a wait that outlives the viewer's
     * attention ends with it rather than decoding a frame nobody is waiting for.
     */
    private fun deferForCache(bucket: Long) {
        inFlightBucket = null
        awaitingFrame = false
        val now = SystemClock.uptimeMillis()
        if (deferredBucket != bucket) {
            deferredBucket = bucket
            deferUntilMs = now + TRICKPLAY_WAIT_MS
            // Once a session, not once per bucket and certainly not once per
            // check: this is re-evaluated several times a second for as long as
            // the viewer sits on a position the player has not read, and one
            // line per check would push every other sample out of the trace
            // ring. How far short the first one fell is the number a report
            // wants, so it goes in as the measured value - and a session whose
            // every bucket lands here is the map case for a preview that can
            // never be served locally.
            if (!deferRecorded) {
                deferRecorded = true
                val buffered = cacheWindow()?.bufferedMs ?: bucket
                PerfTrace.record(
                    "trickplay.defer:cold",
                    (bucket - buffered).coerceAtLeast(0L),
                    ok = false
                )
            }
        }
        handler.removeCallbacks(cacheRetry)
        if (now < deferUntilMs) handler.postDelayed(cacheRetry, TRICKPLAY_RETRY_MS)
    }

    /** Stops waiting for bytes: something else is happening now. */
    private fun cancelDefer() {
        deferredBucket = null
        deferUntilMs = 0L
        handler.removeCallbacks(cacheRetry)
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
        cancelDefer()
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
     * The captured image as a bitmap, subsampled down to the card's size.
     *
     * The reader is [ImageFormat.YUV_420_888] (see [ensureCaptureSurface]) - the
     * only format a decoder surface is guaranteed to accept - so this converts
     * the three planes itself rather than copying a packed buffer. A capture at
     * the video's own size is also far larger than a 480px card needs, so the
     * first pixel of every [sample]-th row and column is taken, the same
     * power-of-two rule the libmpv path decodes with.
     *
     * Each plane has its own strides, and the chroma planes are half resolution,
     * so the index of a pixel's U and V is derived from its (x/2, y/2) - not
     * from its index in the luma plane. [IntArray] output rather than a byte
     * buffer because there is no packed ARGB here to copy from.
     */
    private fun bitmapFrom(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val sample = trickplaySampleSize(width, height)
        val outWidth = (width / sample).coerceAtLeast(1)
        val outHeight = (height / sample).coerceAtLeast(1)
        val planes = image.planes
        if (planes.size < 3) return fallbackBitmap(image)
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val yRowStride = y.rowStride
        val yPixelStride = y.pixelStride
        val uRowStride = u.rowStride
        val uPixelStride = u.pixelStride
        val vRowStride = v.rowStride
        val vPixelStride = v.pixelStride
        val pixels = IntArray(outWidth * outHeight)
        var out = 0
        for (row in 0 until outHeight) {
            val sourceY = (row * sample).coerceAtMost(height - 1)
            val yBase = sourceY * yRowStride
            val chromaBase = (sourceY / 2)
            val uRowBase = chromaBase * uRowStride
            val vRowBase = chromaBase * vRowStride
            for (column in 0 until outWidth) {
                val sourceX = (column * sample).coerceAtMost(width - 1)
                val yValue = yBuffer.get(yBase + sourceX * yPixelStride).toInt() and 0xFF
                val chromaColumn = sourceX / 2
                val uValue =
                    (uBuffer.get(uRowBase + chromaColumn * uPixelStride).toInt() and 0xFF) - 128
                val vValue =
                    (vBuffer.get(vRowBase + chromaColumn * vPixelStride).toInt() and 0xFF) - 128
                val scaledY = 298 * yValue
                val red = ((scaledY + 409 * vValue + 128) shr 8).coerceIn(0, 255)
                val green = ((scaledY - 100 * uValue - 208 * vValue + 128) shr 8).coerceIn(0, 255)
                val blue = ((scaledY + 516 * uValue + 128) shr 8).coerceIn(0, 255)
                pixels[out++] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    /** An image with no readable planes, so a card is never left empty. */
    private fun fallbackBitmap(image: Image): Bitmap {
        val sample = trickplaySampleSize(image.width, image.height)
        val plane = image.planes.firstOrNull()
        return Bitmap.createBitmap(
            (image.width / sample).coerceAtLeast(1),
            (image.height / sample).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        ).apply {
            plane?.let { copyPixelsFromBuffer(it.buffer.duplicate()) }
        }
    }

    private fun mediaItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        trickplayMimeHint(url, resolvedMimeType)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    /**
     * The upstream a cache miss reaches: one that refuses to open.
     *
     * The preview must never open its own connection - see [buildPreview] for
     * why a second connection to a source already serving the main player is
     * worse than no preview at all. Rather than trusting the callers to keep it
     * cache-only, the source itself is a wall: a cache miss fails the
     * extraction there and then, instead of falling through to a connection the
     * host may never grant and leave the main player's own read blocked behind
     * it (the filed report's "buffers endlessly" after a scrub).
     */
    private object CacheOnlyUpstream : DataSource.Factory {
        override fun createDataSource(): DataSource = CacheOnlySource
    }

    private object CacheOnlySource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long =
            throw IOException("scrub previews read the shared cache only")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("scrub previews read the shared cache only")

        override fun getUri(): Uri? = null

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() = Unit
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

        /**
         * Two, so a frame that arrives while one is being read is not dropped.
         *
         * The capture SIZE is not here: it is [TRICKPLAY_CAPTURE_WIDTH] and
         * [TRICKPLAY_CAPTURE_HEIGHT], shared with the libmpv path because both
         * produce frames for the same card.
         */
        const val TRICKPLAY_CAPTURE_IMAGES = 2
    }
}
