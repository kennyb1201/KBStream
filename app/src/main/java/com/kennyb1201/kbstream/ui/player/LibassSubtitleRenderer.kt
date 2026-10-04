package com.kennyb1201.kbstream.ui.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities

/**
 * A media3 text renderer that consumes an embedded ASS/SSA track and feeds it to
 * libass, instead of letting media3's SSA parser flatten it into plain cues.
 *
 * It never draws anything itself: every sample it reads is appended to the
 * activity's shared [AssSubtitleRenderer], and the existing libass overlay tick
 * (see NativePlayerActivity's ASS section) paints the frame for the current
 * position. The reason it exists at all is selection: to keep a flattened copy
 * out of the cue pipeline, the SSA track has to be claimed by a renderer, and
 * only a renderer sees the raw samples.
 *
 * Only EMBEDDED tracks are claimed. An SSA track that carries no
 * `initializationData` is a sidecar (media3's SubtitleExtractor emits cues, not
 * a header), and sidecars keep their existing whole-script path on this engine
 * — claiming one here would feed per-cue samples into a header-based parser.
 * A format the renderer declines falls to media3's default text renderer, which
 * is exactly today's behavior.
 *
 * Streaming, not whole-file: an embedded track arrives as a header block
 * ([handleInputFormat] -> [LibassStreamingSink.processCodecPrivate]) followed by
 * one event block per subtitle ([handleSample] -> [LibassStreamingSink.processChunk]),
 * which is the same incremental feed mpv and VLC drive.
 */
@UnstableApi
internal class LibassSubtitleRenderer(
    private val sink: LibassStreamingSink,
    /** Invoked once the libass instance is up, so the overlay tick can start. */
    private val onActive: () -> Unit = {}
) : BaseRenderer(C.TRACK_TYPE_TEXT) {

    private val inputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

    /** The header block, kept so a seek can re-feed it after a flush. */
    private var codecPrivate: ByteArray? = null

    private var created = false
    private var ended = false

    override fun getName(): String = "LibassSubtitleRenderer"

    /**
     * Claims an embedded ASS/SSA track only. `initializationData` is what tells
     * an embedded track (Matroska's CodecPrivate carries the header) from a
     * sidecar (which carries none) — see the class doc.
     */
    override fun supportsFormat(format: Format): Int =
        if (sink.available &&
            format.sampleMimeType == MimeTypes.TEXT_SSA &&
            format.initializationData.isNotEmpty()
        ) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = ended

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val holder = formatHolder
        while (true) {
            when (val result = readSource(holder, inputBuffer, 0)) {
                C.RESULT_FORMAT_READ -> {
                    holder.format?.let { handleInputFormat(it) }
                    holder.clear()
                }

                C.RESULT_BUFFER_READ -> {
                    if (inputBuffer.isEndOfStream) {
                        ended = true
                        return
                    }
                    val data = inputBuffer.data
                    if (data != null && data.remaining() > 0) {
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        handleSample(bytes, inputBuffer.timeUs)
                    }
                    inputBuffer.clear()
                }

                C.RESULT_NOTHING_READ -> return

                else -> {
                    // RESULT_END_OF_INPUT: nothing more this stream.
                    ended = true
                    return
                }
            }
        }
    }

    /** A new input format: create the instance and feed its header block. */
    internal fun handleInputFormat(format: Format) {
        if (!ensureCreated()) return
        val header = format.initializationData.firstOrNull() ?: return
        if (header.isEmpty()) return
        codecPrivate = header
        sink.processCodecPrivate(header)
    }

    /** One subtitle sample: append it as an event at its own timestamp. */
    internal fun handleSample(data: ByteArray, timeUs: Long) {
        if (!ensureCreated()) return
        sink.processChunk(data, timeUs / 1000L, durationMs = 0L)
    }

    /**
     * Rebuilds the track after a seek: drop the events collected before the new
     * position, then re-feed the header so parsing continues from a known start.
     */
    internal fun seekFlush() {
        ended = false
        sink.flushEvents()
        codecPrivate?.let { sink.processCodecPrivate(it) }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, resetToKeyFrame: Boolean) {
        seekFlush()
    }

    override fun onDisabled() {
        // The shared libass instance may have been released while this track was
        // unselected (the activity drops an EMBEDDED overlay on deselect). A
        // re-selected track must rebuild it, so the next format re-creates.
        created = false
    }

    override fun onReset() {
        created = false
        ended = false
    }

    private fun ensureCreated(): Boolean {
        if (created) return true
        created = sink.create()
        if (!created) {
            Log.w(TAG, "libass instance unavailable; leaving the embedded track to media3")
            return false
        }
        onActive()
        return true
    }

    private companion object {
        const val TAG = "LibassText"
    }
}
