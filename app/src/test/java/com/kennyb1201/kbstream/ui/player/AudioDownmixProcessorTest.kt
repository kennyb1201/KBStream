@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PCM plumbing in front of the downmix, driven through media3's own
 * [AudioProcessingPipeline] in the order the audio sink drives it.
 *
 * Reported problem: playback died on the first buffer of every stream with
 * `java.lang.IllegalArgumentException` out of `ByteBuffer.put` (via
 * `DirectByteBuffer.put`) inside `AudioDownmixProcessor`. The sink flushes the
 * pipeline as soon as it configures it and then drains before it feeds, so the
 * very first thing a stage sees is `AudioProcessor.EMPTY_BUFFER` — the same
 * process-wide object [androidx.media3.common.audio.BaseAudioProcessor] starts
 * the stage's own output buffer on. A stage that copied its input into
 * `replaceOutputBuffer(0)` was asking `ByteBuffer.put` to copy a buffer onto
 * itself, which it refuses; and because that failing call never allocated, the
 * alias survived and every later attempt failed identically.
 */
class AudioDownmixProcessorTest {

    private val stereo =
        AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @After
    fun restoreTuning() {
        // PlayerAudioTuning is process-wide state shared with the player panel
        // and the settings screen; leave it as the tests found it.
        PlayerAudioTuning.apply(
            PlayerAudioTuning.DOWNMIX_AUTO,
            dialogueBoost = 0,
            volumeBoostDb = 0
        )
        PlayerAudioTuning.deviceMaxChannels = 2
    }

    /**
     * Quiet PCM: every sample well under 0.5 of full scale, where the
     * 16-bit → float → 16-bit round trip is exact, so a neutral processor can be
     * compared byte for byte.
     */
    private fun pcm(frames: Int, channels: Int = 2): ByteBuffer {
        val buffer =
            ByteBuffer.allocateDirect(frames * 2 * channels).order(ByteOrder.nativeOrder())
        for (sample in 0 until frames * channels) {
            buffer.putShort(((sample * 7) and 0x3FF).toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun pipeline(): AudioProcessingPipeline =
        AudioProcessingPipeline(
            ImmutableList.of<AudioProcessor>(AudioDelayProcessor(), AudioDownmixProcessor())
        )

    private fun ByteBuffer.bytes(): ByteArray {
        val copy = duplicate()
        val out = ByteArray(copy.remaining())
        copy.get(out)
        return out
    }

    // ── The crash ──────────────────────────────────────────────────────────

    @Test
    fun `an empty feed is not copied onto itself`() {
        val processor = AudioDownmixProcessor()
        processor.configure(stereo)

        // Exactly what the sink hands a stage that has no output of its own yet:
        // the shared empty buffer, which is also this processor's first output
        // buffer.
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)

        assertEquals(0, processor.getOutput().remaining())
    }

    @Test
    fun `the pipeline can be drained before the first buffer`() {
        val pipeline = pipeline()
        pipeline.configure(stereo)
        // The sink flushes the pipeline as soon as it has configured it, which is
        // what leaves every stage holding the shared empty buffer as its output.
        pipeline.flush()

        // DefaultAudioSink.processBuffers() drains first and feeds second — the
        // empty-buffer pass that used to throw here.
        assertEquals(0, pipeline.getOutput().remaining())

        pipeline.queueInput(pcm(frames = 128))

        assertEquals(128 * 2 * 2, pipeline.getOutput().remaining())
    }

    @Test
    fun `the empty feed does not break the buffers after it`() {
        val pipeline = pipeline()
        pipeline.configure(stereo)
        pipeline.flush()
        pipeline.getOutput()

        // Defaults are neutral, so a 2.0 stream must reach the output untouched.
        val input = pcm(frames = 128)
        val expected = input.bytes()
        pipeline.queueInput(input)

        assertArrayEquals(expected, pipeline.getOutput().bytes())
    }

    @Test
    fun `a stereo fold still runs after an empty feed`() {
        PlayerAudioTuning.apply(
            PlayerAudioTuning.DOWNMIX_STEREO,
            dialogueBoost = 0,
            volumeBoostDb = 0
        )
        val pipeline = pipeline()
        pipeline.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        pipeline.flush()
        pipeline.getOutput()

        pipeline.queueInput(pcm(frames = 128, channels = 6))

        // 5.1 in, stereo out.
        assertEquals(128 * 2 * 2, pipeline.getOutput().remaining())
    }
}
