@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's own audio downmix / dialogue enhancer, sitting in the PCM stream
 * between the decoder and the speaker.
 *
 * It is installed unconditionally (like [AudioDelayProcessor]) and reads its
 * settings from [PlayerAudioTuning] on every buffer, so a viewer dragging a
 * pill in the player panel hears the change on the next few milliseconds of
 * audio — no player rebuild, no interruption. With every knob at its default it
 * is a byte-for-byte pass-through.
 *
 * What it does, in order, per frame:
 *  1. **Fold** a multichannel stream to the requested layout (5.1/7.1 → stereo),
 *     lifting the centre channel — dialogue — and trimming the surrounds that
 *     carry score and effects (see [AudioDownmix]).
 *  2. **Balance in place** when nothing is folded. This is what makes the
 *     dialogue knob work at all with the downmix left on "Auto" (the default):
 *     a stereo stream has its phantom centre lifted (mid/side — voices up, wide
 *     effects untouched), and a multichannel stream keeps its layout but has the
 *     real centre channel lifted and its surrounds trimmed, so the device's own
 *     fold then works on an already-balanced mix rather than the untouched one.
 *  3. **Gain** by the configured volume boost, so content mixed too quietly is
 *     audible without the TV's volume rocker pinned and the amp clipping.
 *  4. **Limit** the result with a linked-channel peak limiter, which is what
 *     makes the extra level safe: the fold sums four to six channels of a
 *     near-full-scale mix, and a plain sum clips.
 *
 * Speaker order follows media3/Android (see [AudioDownmix.rolesFor]), which is
 * what the decoder hands us — this is decoder-agnostic, so it behaves the same
 * whether the audio came from the bundled FFmpeg decoder or a hardware codec.
 *
 * The dialogue and volume knobs need nothing but the next buffer. The *layout*
 * cannot be changed from in here — the sink builds its AudioTrack from the
 * channel count declared in [onConfigure] — so a layout change is applied by
 * re-configuring the sink, which [LiveDownmixAudioSink] watches for and drives
 * (using [inputChannelCount]/[outputChannelCount] to notice the change). Either
 * way the viewer hears it while the film keeps playing.
 */
internal class AudioDownmixProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * The instance the renderers factory installs. One per process, shared by
         * every player the app builds (they are rebuilt on source switches), so
         * what it reads is always the live tuning.
         */
        val instance = AudioDownmixProcessor()
    }

    private var inputChannels = 0
    private var outputChannels = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var sampleRate = 48_000

    /** Row-major input→output gains, or null when only gain/limiting applies. */
    private var matrix: FloatArray? = null

    /** Setting signature the current [matrix] was built for. */
    private var matrixSignature = Int.MIN_VALUE

    /** False for layouts/encodings we do not model: the processor stays out of the way. */
    private var processable = false

    /**
     * Decoded channel count of the stream in the pipeline (0 before the first
     * [onConfigure]). Read by [LiveDownmixAudioSink] on the same audio thread.
     */
    val inputChannelCount: Int
        get() = inputChannels

    /**
     * Channel count the sink is carrying for this stream (0 before the first
     * [onConfigure]). Equal to [inputChannelCount] unless a fold is in place.
     */
    val outputChannelCount: Int
        get() = outputChannels

    /**
     * True only while the processor is really folding/gaining this stream — i.e.
     * a layout it models, in an encoding it can rewrite. A stream in passthrough
     * or offload never reaches us, and one with an unmodelled channel count is
     * left untouched, so a caller must not try to re-fold either.
     */
    val isFoldingChannels: Boolean
        get() = processable

    private var limiter = AudioDownmix.Limiter(sampleRate = 48_000)

    private var channelScratch = FloatArray(0)
    private var mixScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputChannels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding

        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                C.ENCODING_PCM_32BIT -> 4
                else -> 0
            }

        limiter = AudioDownmix.Limiter(sampleRate = sampleRate)
        matrixSignature = Int.MIN_VALUE

        if (bytesPerSample == 0 || AudioDownmix.rolesFor(inputChannels) == null) {
            processable = false
            outputChannels = inputChannels
            matrix = null
            return inputAudioFormat
        }

        processable = true

        // The layout is decided here, once per stream — and again whenever the
        // sink is reconfigured because the setting changed mid-playback (see
        // [LiveDownmixAudioSink]): the sink builds its AudioTrack from this
        // output format, so the channel count can only change under it through
        // another configure() call, never from inside the sample stream.
        val desired = AudioDownmix.desiredOutputChannels(inputChannels)
        val foldable = desired != inputChannels
        outputChannels = if (foldable) desired else inputChannels

        channelScratch = FloatArray(inputChannels)
        mixScratch = FloatArray(outputChannels)
        rebuildCoefficients()

        return if (outputChannels == inputChannels) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                outputChannels,
                inputAudioFormat.encoding
            )
        }
    }

    override fun onFlush() {
        limiter.reset()
        matrixSignature = Int.MIN_VALUE
    }

    override fun onReset() {
        limiter.reset()
        matrixSignature = Int.MIN_VALUE
    }

    /**
     * Always true: the processor must stay in the sink's chain so a setting
     * changed mid-playback is picked up on the next buffer. (A processor that
     * reports inactive is dropped when the sink is built and would only come
     * back on a full rebuild.) At defaults [queueInput] is a pass-through.
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!processable) {
            passThrough(inputBuffer)
            return
        }

        refreshMatrixIfNeeded()

        val bytesPerInputFrame = bytesPerSample * inputChannels
        val frames = inputBuffer.remaining() / bytesPerInputFrame
        if (frames <= 0) {
            passThrough(inputBuffer)
            return
        }

        val gain = PlayerAudioTuning.linearGain
        val midLift = PlayerAudioTuning.midGain
        val liftPhantomCentre = inputChannels == 2 && outputChannels == 2 && midLift > 1f
        val mixing = matrix

        val output = replaceOutputBuffer(frames * bytesPerSample * outputChannels)
        val channels = channelScratch
        val mixed = mixScratch

        for (frame in 0 until frames) {
            for (channel in 0 until inputChannels) {
                channels[channel] = readSample(inputBuffer)
            }

            if (mixing != null) {
                java.util.Arrays.fill(mixed, 0f)
                for (channel in 0 until inputChannels) {
                    val sample = channels[channel]
                    if (sample == 0f) continue
                    val base = channel * outputChannels
                    for (out in 0 until outputChannels) {
                        val coefficient = mixing[base + out]
                        if (coefficient != 0f) mixed[out] += sample * coefficient
                    }
                }
            } else {
                for (out in 0 until outputChannels) mixed[out] = channels[out]

                if (liftPhantomCentre) {
                    // Voices live in the phantom centre of a 2.0 mix — the
                    // "mid" component. Raising it (and its twin below, so the
                    // side content is untouched) lifts dialogue over a wide
                    // music bed without touching bass or hard-panned effects.
                    val mid = (mixed[0] + mixed[1]) * 0.5f
                    val extra = mid * (midLift - 1f)
                    mixed[0] += extra
                    mixed[1] += extra
                }
            }

            var peak = 0f
            for (out in 0 until outputChannels) {
                val scaled = mixed[out] * gain
                mixed[out] = scaled
                val magnitude = abs(scaled)
                if (magnitude > peak) peak = magnitude
            }

            val limited = limiter.nextGain(peak)
            for (out in 0 until outputChannels) {
                writeSample(output, mixed[out] * limited)
            }
        }

        output.flip()
    }

    /**
     * Re-pins the pipeline to [channels] output channels after the sink refused a
     * live layout change (see [LiveDownmixAudioSink]).
     *
     * Media3 configures the audio processors before it checks the output layout,
     * so a rejected reconfigure leaves this processor already producing the
     * rejected width while the sink keeps writing the old one — buffers whose
     * frame size disagrees with the track. This puts the matrix, the scratch
     * buffer and the declared width back on what the sink is carrying. Must be
     * called on the audio thread.
     */
    fun rePinOutputChannels(channels: Int) {
        outputChannels = if (channels in 1..inputChannels) channels else inputChannels
        mixScratch = FloatArray(outputChannels)
        rebuildCoefficients()
    }

    /** Rebuilds the mix coefficients when a dialogue knob moved (buffer granularity). */
    private fun refreshMatrixIfNeeded() {
        val current = signature()
        if (current == matrixSignature) return
        rebuildCoefficients()
    }

    /**
     * The coefficients for the layout the sink is carrying: the fold matrix when
     * the output width differs from the decoded one, otherwise the in-place
     * balance (see [AudioDownmix.gainMatrix]) — which is what carries the
     * dialogue lift when the layout is left to the device.
     *
     * Must not change [outputChannels]: the sink is already built for that width,
     * so only a configure() may move it (see [LiveDownmixAudioSink]).
     */
    private fun rebuildCoefficients() {
        matrix =
            if (outputChannels != inputChannels) {
                AudioDownmix.mixingMatrix(
                    inputChannels = inputChannels,
                    outputChannels = outputChannels,
                    centerGain = PlayerAudioTuning.centerGain,
                    surroundScale = surroundScale()
                )
            } else {
                AudioDownmix.gainMatrix(
                    inputChannels = inputChannels,
                    centerGain = PlayerAudioTuning.inPlaceCenterGain,
                    surroundScale = surroundScale()
                )
            }
        matrixSignature = signature()
    }

    /** Surround trim that lets a lifted centre read as dialogue rather than as volume. */
    private fun surroundScale(): Float =
        1f - 0.2f * PlayerAudioTuning.dialogueBoost

    private fun signature(): Int =
        PlayerAudioTuning.downmixTarget * 16 + PlayerAudioTuning.dialogueBoost

    private fun passThrough(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    private fun readSample(buffer: ByteBuffer): Float =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buffer.short.toFloat() / 32_768f
            C.ENCODING_PCM_FLOAT -> buffer.float
            C.ENCODING_PCM_32BIT -> buffer.int.toFloat() / 2_147_483_648f
            else -> 0f
        }

    private fun writeSample(
        buffer: ByteBuffer,
        value: Float
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                buffer.putShort(
                    (value * 32_767f)
                        .roundToInt()
                        .coerceIn(-32_768, 32_767)
                        .toShort()
                )

            C.ENCODING_PCM_FLOAT ->
                buffer.putFloat(
                    value.coerceIn(-1f, 1f)
                )

            C.ENCODING_PCM_32BIT ->
                buffer.putInt(
                    (value * 2_147_483_647f)
                        .toLong()
                        .coerceIn(-2_147_483_648L, 2_147_483_647L)
                        .toInt()
                )
        }
    }
}
