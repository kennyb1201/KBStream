package com.kennyb1201.kbstream.ui.player

import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [VideoDecoderOutputBuffer] carrying the two extra facts the P5 GLES path
 * needs to upload the raw planes correctly:
 *
 *  - [tenBit]: the planes hold 10-bit samples stored as little-endian 16-bit
 *    words (P010 / YUV420P10 style — value in the high 10 bits, i.e. the
 *    normalized 16-bit read already equals the 10-bit PQ sample / 1023).
 *  - [colorRangeLimited]: samples are limited (video) range — 64..940 luma /
 *    64..960 chroma for 10-bit, 16..235 / 16..240 for 8-bit — rather than
 *    full range. The shader expands to full range before the PQ decode.
 */
internal class P5OutputBuffer(
    owner: Owner<VideoDecoderOutputBuffer>
) : VideoDecoderOutputBuffer(owner) {

    var tenBit: Boolean = false
    var colorRangeLimited: Boolean = true
}

/**
 * MediaCodec video decoder running in ByteBuffer mode (no output Surface).
 *
 * This is the P5 (ICtCp) raw-plane source: in buffer mode the hardware
 * decoder hands back `Image` planes of the decoded samples *in process
 * memory, exactly as coded* — no Surface, no SurfaceTexture, no automatic
 * dataspace conversion in between. For Dolby Vision Profile 5 those samples
 * ARE the ICtCp values the P5ColorShader expects, which the Surface-bound
 * pipeline could never deliver (the display stack converts them first).
 *
 * Implements the same [Decoder] contract the libdav1d / libgav1 extensions
 * use with [androidx.media3.exoplayer.video.DecoderVideoRenderer], producing
 * [VideoDecoderOutputBuffer]s in [C.VIDEO_OUTPUT_MODE_YUV]. The renderer
 * forwards them to the attached [androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer]
 * (the P5 [P5VideoGlesView]); buffer ownership returns to this decoder's
 * pool via [VideoDecoderOutputBuffer.release] from whichever thread the
 * renderer finishes with them (the GL thread), so the output pool is
 * concurrent.
 */
@UnstableApi
internal class P5PlaneDecoder(
    format: Format
) : Decoder<DecoderInputBuffer, P5OutputBuffer, DecoderException> {

    companion object {
        private const val TAG = "P5_PLANE_DECODER"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val MIN_INPUT_SIZE = 786_432
        private const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        private const val MAX_OUTPUT_BUFFERS = 8
        private const val MAX_OUTPUT_DRAIN_PER_CALL = 64
    }

    private val codec: MediaCodec

    /** Max sample size the input pool buffers are sized for. */
    private val maxInputSize: Int

    /** Free input buffers — playback thread only. */
    private val freeInputBuffers = ArrayDeque<DecoderInputBuffer>()

    /** Inputs queued before a codec input buffer was free — playback thread only. */
    private val pendingInputs = ArrayDeque<DecoderInputBuffer>()

    /** Free output buffers — touched from the playback thread AND the GL thread. */
    private val freeOutputBuffers = ConcurrentLinkedQueue<P5OutputBuffer>()

    private var outputsCreated = 0
    private var outputMode: Int = C.VIDEO_OUTPUT_MODE_YUV
    private var outputStartTimeUs: Long = 0L
    private var endOfStreamQueued = false
    private var endOfStreamDequeued = false
    private var colorRangeLimited = true
    private var released = false

    init {
        val mime = format.sampleMimeType ?: MimeTypes.VIDEO_H265
        val width = if (format.width > 0) format.width else DEFAULT_WIDTH
        val height = if (format.height > 0) format.height else DEFAULT_HEIGHT
        maxInputSize = (if (format.maxInputSize > 0) format.maxInputSize else width * height * 3 / 2)
            .coerceIn(MIN_INPUT_SIZE, MAX_INPUT_SIZE)

        val mediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            val initData = format.initializationData
            if (initData.isNotEmpty()) {
                setByteBuffer("csd-0", ByteBuffer.wrap(initData[0]))
                if (initData.size > 1) setByteBuffer("csd-1", ByteBuffer.wrap(initData[1]))
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        }
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(mediaFormat, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
            codec.start()
            Log.i(TAG, "Buffer-mode MediaCodec started: $mime ${width}x$height")
        } catch (e: Exception) {
            throw DecoderException("P5PlaneDecoder: failed to start MediaCodec for $mime", e)
        }
    }

    override fun getName(): String = "P5PlaneDecoder"

    override fun setOutputMode(outputMode: Int) {
        // Buffers are always produced in YUV mode; the renderer's own output
        // mode (Surface vs VideoDecoderOutputBufferRenderer) is what decides
        // whether they get rendered or dropped.
        this.outputMode = outputMode
    }

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer {
        val buffer = freeInputBuffers.removeFirstOrNull() ?: DecoderInputBuffer(
            DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT
        ).also {
            // One-time sizing; the playback thread reads samples into data.
            it.ensureSpaceForWrite(maxInputSize)
        }
        return buffer
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        pendingInputs.addLast(inputBuffer)
        drainPendingInputs()
    }

    override fun dequeueOutputBuffer(): P5OutputBuffer? {
        drainPendingInputs()
        var drained = 0
        while (!released && drained < MAX_OUTPUT_DRAIN_PER_CALL) {
            val info = MediaCodec.BufferInfo()
            val index = try {
                codec.dequeueOutputBuffer(info, /* timeoutUs = */ 0)
            } catch (e: Exception) {
                throw DecoderException("P5PlaneDecoder: dequeueOutputBuffer failed", e)
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return null

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    readOutputColorRange()
                }

                // Legacy notification (API < 21 decoders); nothing to do —
                // the next dequeue returns a real result.
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                }

                index >= 0 -> {
                    drained++
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        endOfStreamDequeued = true
                        releaseCodecBuffer(index)
                        val eos = obtainOutputBuffer() ?: return null
                        eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                        return eos
                    }

                    val image = try {
                        codec.getOutputImage(index)
                    } catch (e: Exception) {
                        Log.w(TAG, "getOutputImage failed; dropping frame", e)
                        null
                    }
                    if (image == null) {
                        // Non-image output buffer (e.g. codec config); skip it.
                        releaseCodecBuffer(index)
                        continue
                    }

                    val out = obtainOutputBuffer()
                    var copyOk = false
                    if (out != null) {
                        try {
                            copyOk = copyImagePlanes(image, out)
                        } catch (e: Exception) {
                            Log.w(TAG, "Plane copy failed; dropping frame", e)
                            copyOk = false
                        }
                    }
                    image.close()
                    releaseCodecBuffer(index)

                    if (out == null || !copyOk) {
                        out?.let { recycleOutputBuffer(it) }
                        continue
                    }

                    out.timeUs = info.presentationTimeUs
                    if (out.timeUs < outputStartTimeUs) {
                        // Skip output before the requested start time.
                        recycleOutputBuffer(out)
                        continue
                    }
                    return out
                }
            }
        }
        return null
    }

    override fun flush() {
        if (released) return
        try {
            codec.flush()
        } catch (e: Exception) {
            Log.w(TAG, "codec.flush() failed", e)
        }
        while (pendingInputs.isNotEmpty()) {
            freeInputBuffers.addLast(pendingInputs.removeFirst())
        }
        endOfStreamQueued = false
        endOfStreamDequeued = false
        // Output buffers held by the renderer/GL view return through the
        // owner callback; freeOutputBuffers is concurrent so that is safe.
    }

    override fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() failed", e)
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec.release() failed", e)
        }
        freeInputBuffers.clear()
        pendingInputs.clear()
        freeOutputBuffers.clear()
    }

    /**
     * Owner callback for [VideoDecoderOutputBuffer.release] — invoked from the
     * GL thread once the view has uploaded a frame. Concurrent queue, so
     * playback-thread polling races are safe.
     */
    fun recycleOutputBuffer(buffer: VideoDecoderOutputBuffer) {
        buffer.clear()
        freeOutputBuffers.add(buffer as P5OutputBuffer)
    }

    private fun obtainOutputBuffer(): P5OutputBuffer? {
        freeOutputBuffers.poll()?.let { return it }
        if (outputsCreated >= MAX_OUTPUT_BUFFERS) return null
        outputsCreated++
        val buffer = P5OutputBuffer { recycleOutputBuffer(it) }
        buffer.mode = if (outputMode != C.VIDEO_OUTPUT_MODE_NONE) {
            outputMode
        } else {
            C.VIDEO_OUTPUT_MODE_YUV
        }
        return buffer
    }

    /** Copies queued inputs into codec input buffers while capacity allows. */
    private fun drainPendingInputs() {
        if (released || endOfStreamQueued) return
        while (pendingInputs.isNotEmpty()) {
            val index = try {
                codec.dequeueInputBuffer(/* timeoutUs = */ 0)
            } catch (e: Exception) {
                throw DecoderException("P5PlaneDecoder: dequeueInputBuffer failed", e)
            }
            if (index < 0) return

            val input = pendingInputs.removeFirst()
            val buffer = try {
                codec.getInputBuffer(index)
            } catch (e: Exception) {
                freeInputBuffers.addLast(input)
                throw DecoderException("P5PlaneDecoder: getInputBuffer failed", e)
            }
            if (buffer == null) {
                freeInputBuffers.addLast(input)
                continue
            }

            if (input.isEndOfStream()) {
                endOfStreamQueued = true
                try {
                    codec.queueInputBuffer(
                        index, 0, 0, input.timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } catch (e: Exception) {
                    throw DecoderException("P5PlaneDecoder: queueInputBuffer (EOS) failed", e)
                }
            } else {
                val data = checkNotNull(input.data)
                buffer.clear()
                buffer.put(data)
                var flags = 0
                if (input.isKeyFrame()) flags = flags or MediaCodec.BUFFER_FLAG_SYNC_FRAME
                try {
                    codec.queueInputBuffer(index, 0, buffer.position(), input.timeUs, flags)
                } catch (e: Exception) {
                    throw DecoderException("P5PlaneDecoder: queueInputBuffer failed", e)
                }
            }
            freeInputBuffers.addLast(input)
        }
    }

    private fun readOutputColorRange() {
        val outputFormat = try {
            codec.outputFormat
        } catch (e: Exception) {
            return
        }
        // MediaFormat.getInteger(key, default) needs API 29; containsKey is
        // available since the key existed.
        colorRangeLimited = if (outputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
            outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE) != MediaFormat.COLOR_RANGE_FULL
        } else {
            // Unspecified: HEVC content is virtually always limited range.
            true
        }
        Log.i(
            TAG,
            "Output format: range=" +
                (if (colorRangeLimited) "limited" else "full")
        )
    }

    private fun releaseCodecBuffer(index: Int) {
        try {
            codec.releaseOutputBuffer(index, /* render = */ false)
        } catch (e: Exception) {
            Log.w(TAG, "releaseOutputBuffer failed", e)
        }
    }

    /**
     * Copies the [Image]'s Y/U/V planes into [out]'s packed plane slices.
     *
     * Layout produced (matches what [P5ColorShader.uploadOutputBuffer] uploads):
     *  - Y plane: rows copied verbatim, stride = [Image.Plane.getRowStride]
     *    bytes (padding included; the shader crops via texture coordinates).
     *  - U/V planes, 10-bit (pixelStride 2 → P010/16-LE): deinterleaved into
     *    packed 16-bit rows, stride = ceil(w/2) * 2 bytes.
     *  - U/V planes, 8-bit planar (pixelStride 1): rows copied verbatim,
     *    stride = rowStride.
     *  - U/V planes, 8-bit semiplanar (pixelStride 2, NV12): deinterleaved
     *    into packed 8-bit rows, stride = ceil(w/2).
     */
    private fun copyImagePlanes(image: Image, out: P5OutputBuffer): Boolean {
        val planes = image.planes
        val w = image.width
        val h = image.height
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        // Main10 decoders output 16-bit-per-sample planes (P010 / planar
        // 10-bit); Main 8-bit outputs 8-bit. This decoder only runs for
        // Dolby Vision Profile 5 sessions, which are always Main10.
        val tenBit = yPlane.pixelStride == 2
        val yStride = yPlane.rowStride
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val uvStride = when {
            tenBit -> cw * 2
            uPlane.pixelStride == 1 -> uPlane.rowStride
            else -> cw
        }
        if (!out.initForYuvFrame(w, h, yStride, uvStride, VideoDecoderOutputBuffer.COLORSPACE_BT2020)) {
            return false
        }
        val yuvPlanes = checkNotNull(out.yuvPlanes)
        val yDst = checkNotNull(yuvPlanes[0])
        val uDst = checkNotNull(yuvPlanes[1])
        val vDst = checkNotNull(yuvPlanes[2])

        // Y rows are single-channel (no interleave) in every layout.
        copyRows(yPlane, yDst, h)
        when {
            tenBit -> {
                copyChroma16(uPlane, uDst, cw, ch)
                copyChroma16(vPlane, vDst, cw, ch)
            }

            uPlane.pixelStride == 1 -> {
                copyRows(uPlane, uDst, ch)
                copyRows(vPlane, vDst, ch)
            }

            else -> {
                copyChroma8(uPlane, uDst, cw, ch)
                copyChroma8(vPlane, vDst, cw, ch)
            }
        }
        out.tenBit = tenBit
        out.colorRangeLimited = colorRangeLimited
        return true
    }

    /** Verbatim row copy of [src] into [dst] (byte stride = rowStride). */
    private fun copyRows(src: Image.Plane, dst: ByteBuffer, rows: Int) {
        val rowStride = src.rowStride
        val buf = src.buffer
        dst.position(0)
        for (r in 0 until rows) {
            buf.position(r * rowStride)
            buf.limit(minOf(r * rowStride + rowStride, buf.capacity()))
            dst.put(buf)
        }
        dst.clear()
        buf.clear()
    }

    /** Deinterleaves 16-bit LE samples (P010-style) into a packed plane. */
    private fun copyChroma16(src: Image.Plane, dst: ByteBuffer, cw: Int, ch: Int) {
        val rowStride = src.rowStride
        val pixelStride = src.pixelStride
        val buf = src.buffer.duplicate()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        dst.order(ByteOrder.LITTLE_ENDIAN)
        dst.position(0)
        for (r in 0 until ch) {
            val base = r * rowStride / 2
            for (c in 0 until cw) {
                dst.putShort(shorts.get(base + c * pixelStride))
            }
        }
        dst.clear()
        dst.order(ByteOrder.BIG_ENDIAN)
    }

    /** Deinterleaves 8-bit samples (NV12-style) into a packed plane. */
    private fun copyChroma8(src: Image.Plane, dst: ByteBuffer, cw: Int, ch: Int) {
        val rowStride = src.rowStride
        val pixelStride = src.pixelStride
        val buf = src.buffer
        dst.position(0)
        for (r in 0 until ch) {
            val rowBase = r * rowStride
            for (c in 0 until cw) {
                dst.put(buf.get(rowBase + c * pixelStride))
            }
        }
        dst.clear()
    }
}
