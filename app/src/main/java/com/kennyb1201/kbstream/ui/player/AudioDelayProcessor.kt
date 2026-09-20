@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Shifts audio relative to video by [setDelayMs] milliseconds.
 *
 * Media3 has no A/V sync offset: the audio sink renders the PCM it is handed,
 * in order, against the player clock. So the offset is applied in the sample
 * stream itself — a POSITIVE delay emits that much silence once (per flush)
 * before the program audio, which makes the audio happen later than the video;
 * a NEGATIVE delay drops that much audio, which makes it happen earlier. Both
 * directions are re-applied after every flush (seek / track change), so the
 * offset stays constant for the whole session instead of accumulating.
 *
 * Safety: [isActive] is deliberately always true so the processor stays in the
 * sink's chain and a change takes effect immediately (a processor that reports
 * inactive is dropped when the sink is built, and only a later rebuild would
 * re-include it). With a 0 ms delay [queueInput] is a pure pass-through, so the
 * default state is byte-identical to having no processor at all.
 *
 * Not a continuous resampler: a constant offset is inserted once at the head of
 * each flushed stream, which is what a sync correction needs and what cannot
 * cause drift.
 */
internal class AudioDelayProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "PLAYER_AUDIO_DELAY"

        /** Matches the subtitle offset range, so both sliders feel the same. */
        const val MAX_MS = 5_000

        /** Shared instance installed by the renderers factory. */
        val instance = AudioDelayProcessor()
    }

    private var bytesPerMs = 0
    private var bytesPerFrame = 4
    private var appliedMs = 0

    /**
     * Bytes still to shift in this stream: > 0 emits silence, < 0 drops audio.
     * Only touched on the audio thread apart from the small synchronized
     * updates from [setDelayMs] on the main thread.
     */
    private var pendingBytes = 0

    private val silenceChunk = ByteArray(4 * 1024)

    @Volatile
    private var targetMs = 0

    /** Current offset in ms (0 = untouched audio). */
    val delayMs: Int
        get() = targetMs

    /**
     * Sets the offset. The *difference* is applied from the next buffer on, so
     * dragging the slider while playing adjusts instead of restarting the
     * offset from scratch.
     */
    fun setDelayMs(ms: Int) {
        val clamped = ms.coerceIn(-MAX_MS, MAX_MS)
        if (clamped == targetMs) return
        Log.i(TAG, "audio delay ${targetMs}ms -> ${clamped}ms")
        targetMs = clamped
        synchronized(this) {
            val delta = clamped - appliedMs
            appliedMs = clamped
            pendingBytes += bytesFor(delta)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        bytesPerMs = (bytesPerFrame * inputAudioFormat.sampleRate / 1000).coerceAtLeast(1)
        synchronized(this) { pendingBytes = bytesFor(appliedMs) }
        return inputAudioFormat
    }

    override fun onFlush() {
        // A seek/track change starts a fresh stream: re-apply the whole offset
        // so it stays constant for the session.
        synchronized(this) { pendingBytes = bytesFor(appliedMs) }
    }

    override fun onReset() {
        synchronized(this) { pendingBytes = 0 }
    }

    /** Always true — see the class doc; the 0 ms path is a pass-through. */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        var pending: Int
        synchronized(this) { pending = pendingBytes }

        // Negative offset: swallow the start of the audio.
        if (pending < 0) {
            val drop = minOf(-pending, inputBuffer.remaining())
            if (drop > 0) {
                inputBuffer.position(inputBuffer.position() + drop)
                synchronized(this) { pendingBytes = pending + drop }
                pending += drop
            }
        }

        val inputBytes = inputBuffer.remaining()
        val silenceBytes = if (pending > 0) pending else 0

        if (inputBytes == 0 && silenceBytes == 0) {
            replaceOutputBuffer(0).flip()
            return
        }

        val out = replaceOutputBuffer(silenceBytes + inputBytes)
        var written = 0
        while (written < silenceBytes) {
            val chunk = minOf(silenceBytes - written, silenceChunk.size)
            out.put(silenceChunk, 0, chunk)
            written += chunk
        }
        out.put(inputBuffer)
        out.flip()

        if (silenceBytes > 0) {
            synchronized(this) { pendingBytes = (pendingBytes - silenceBytes).coerceAtLeast(0) }
        }
    }

    /** Frame-aligned byte count for [ms]; negative for a negative offset. */
    private fun bytesFor(ms: Int): Int {
        if (ms == 0 || bytesPerMs <= 0) return 0
        val frames = (ms.toLong() * bytesPerMs) / bytesPerFrame
        return (frames * bytesPerFrame).toInt()
    }
}
