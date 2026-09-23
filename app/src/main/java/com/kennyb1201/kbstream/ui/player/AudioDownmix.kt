package com.kennyb1201.kbstream.ui.player

import kotlin.math.exp

/**
 * The channel arithmetic behind the app's audio downmix — pure Kotlin, no
 * Android types, so the coefficients and the limiter can be unit-tested
 * without a device.
 *
 * ## Why this exists at all
 *
 * A 5.1/7.1 film mix puts dialogue in the CENTRE channel and almost everything
 * else — score, explosions, ambience — in the front pair and the surrounds.
 * When the platform downmixes for a TV's own stereo speakers it does the
 * textbook thing: fold everything down with fixed coefficients and no
 * normalisation. Dialogue therefore arrives at roughly the level of the music
 * bed, which is why speech is unintelligible on a TV and the viewer ends up
 * holding the volume rocker.
 *
 * Doing the fold here instead buys three things a HAL downmix cannot give us:
 *  - the centre channel can be lifted (and the surrounds trimmed) so voices come
 *    up while score/effects stay where the mixer put them;
 *  - a stereo source gets the same treatment through its phantom centre;
 *  - the sum is limited, so a loud scene never clips into distortion.
 *
 * Channel order follows media3/Android conventions:
 * ```
 * 1: [FC]                     2: [FL, FR]
 * 3: [FL, FR, FC]             4: [FL, FR, BL, BR]
 * 5: [FL, FR, FC, BL, BR]     6: [FL, FR, FC, LFE, BL, BR]
 * 7: [FL, FR, FC, LFE, BC, SL, SR]
 * 8: [FL, FR, FC, LFE, BL, BR, SL, SR]   (7.1)
 * ```
 */
internal object AudioDownmix {

    // Speaker roles.
    const val FL = 1
    const val FR = 2
    const val FC = 3
    const val LFE = 4
    const val BL = 5
    const val BR = 6
    const val BC = 7
    const val SL = 8
    const val SR = 9

    /** Speaker for each decoded channel of a [channelCount]-channel layout, or null if unknown. */
    fun rolesFor(channelCount: Int): IntArray? =
        when (channelCount) {
            1 -> intArrayOf(FC)
            2 -> intArrayOf(FL, FR)
            3 -> intArrayOf(FL, FR, FC)
            4 -> intArrayOf(FL, FR, BL, BR)
            5 -> intArrayOf(FL, FR, FC, BL, BR)
            6 -> intArrayOf(FL, FR, FC, LFE, BL, BR)
            7 -> intArrayOf(FL, FR, FC, LFE, BC, SL, SR)
            8 -> intArrayOf(FL, FR, FC, LFE, BL, BR, SL, SR)
            else -> null
        }

    /**
     * Row-major mix matrix folding [inputChannels] into [outputChannels], or
     * null when there is nothing to fold (same layout, or a layout this code
     * does not model) — the caller then only applies gain and limiting.
     *
     * [centerGain] lifts dialogue (the centre channel); [surroundScale] trims
     * the surrounds that carry effects, which is the other half of making
     * speech audible without raising an explosion.
     */
    fun mixingMatrix(
        inputChannels: Int,
        outputChannels: Int,
        centerGain: Float,
        surroundScale: Float
    ): FloatArray? {

        if (inputChannels == outputChannels) return null

        if (outputChannels == 2) {
            val roles = rolesFor(inputChannels) ?: return null
            val leftGains = FloatArray(roles.size)
            val rightGains = FloatArray(roles.size)

            for (index in roles.indices) {
                when (roles[index]) {
                    FC -> {
                        leftGains[index] = centerGain
                        rightGains[index] = centerGain
                    }

                    FL -> leftGains[index] = 1f
                    FR -> rightGains[index] = 1f

                    // Standard surround coefficient, trimmed while a dialogue
                    // boost is active so effects give the voices room.
                    BL, SL ->
                        leftGains[index] = PlayerAudioTuning.SURROUND_BASE * surroundScale

                    BR, SR ->
                        rightGains[index] = PlayerAudioTuning.SURROUND_BASE * surroundScale

                    // A back-centre channel belongs to both sides equally.
                    BC -> {
                        leftGains[index] = PlayerAudioTuning.SURROUND_BASE * surroundScale
                        rightGains[index] = PlayerAudioTuning.SURROUND_BASE * surroundScale
                    }

                    // Kept at the platform's own LFE level: bass is not the
                    // problem this feature solves, and dropping it would make
                    // the downmix sound thin against every other player.
                    LFE -> {
                        leftGains[index] = PlayerAudioTuning.LFE_BASE
                        rightGains[index] = PlayerAudioTuning.LFE_BASE
                    }
                }
            }

            val matrix = FloatArray(inputChannels * 2)
            for (index in 0 until inputChannels) {
                matrix[index * 2] = leftGains[index]
                matrix[index * 2 + 1] = rightGains[index]
            }
            return matrix
        }

        if (outputChannels == 6) {
            // 7.1 (or more) → 5.1: the side channels fold into the rears.
            val roles = rolesFor(inputChannels) ?: return null
            val matrix = FloatArray(inputChannels * 6)

            fun set(input: Int, output: Int, gain: Float) {
                matrix[input * 6 + output] = gain
            }

            for (index in roles.indices) {
                when (roles[index]) {
                    FL -> set(index, 0, 1f)
                    FR -> set(index, 1, 1f)
                    FC -> set(index, 2, 1f)
                    LFE -> set(index, 3, 1f)
                    BL, SL -> set(index, 4, 1f)
                    BR, SR -> set(index, 5, 1f)
                    BC -> {
                        set(index, 4, PlayerAudioTuning.SURROUND_BASE)
                        set(index, 5, PlayerAudioTuning.SURROUND_BASE)
                    }
                }
            }
            return matrix
        }

        return null
    }

    /**
     * Per-channel gains for a stream that is NOT being folded: the same layout in
     * and out, with the centre channel (dialogue) lifted and the surrounds that
     * carry score and effects trimmed. Fronts and LFE are left exactly where the
     * mixer put them.
     *
     * This is what makes the dialogue knob worth something with the downmix set
     * to "Auto", which is the default and the case the feature exists for: the
     * device still folds the 5.1/7.1 stream, but it now folds one whose centre is
     * already lifted, instead of the untouched mix whose speech sits under the
     * music. Trimming the surrounds matters as much as the lift — it is what
     * stops "louder dialogue" from also meaning "louder explosions".
     *
     * Note the surrounds are scaled by [surroundScale] itself rather than the
     * standard fold coefficient: nothing is being folded away here, so the
     * figure is a balance between channels that all survive, not a share of a
     * summed output.
     *
     * Returns null when every gain is 1 — i.e. with the dialogue boost off — so
     * the default path stays a byte-for-byte pass-through.
     */
    fun gainMatrix(
        inputChannels: Int,
        centerGain: Float,
        surroundScale: Float
    ): FloatArray? {
        val roles = rolesFor(inputChannels) ?: return null

        var changed = false
        val matrix = FloatArray(inputChannels * inputChannels)
        for (index in roles.indices) {
            val gain =
                when (roles[index]) {
                    FC -> centerGain
                    BL, BR, BC, SL, SR -> surroundScale
                    else -> 1f
                }
            if (gain != 1f) changed = true
            matrix[index * inputChannels + index] = gain
        }
        return if (changed) matrix else null
    }

    /**
     * Output layout requested by the current setting for a stream decoded as
     * [inputChannels] channels. Never upmixes: a stereo stream asked to become
     * "5.1" stays stereo (inventing channels would only add silence).
     */
    fun desiredOutputChannels(inputChannels: Int): Int =
        when (PlayerAudioTuning.downmixTarget) {
            PlayerAudioTuning.DOWNMIX_STEREO ->
                if (inputChannels > 2) 2 else inputChannels

            PlayerAudioTuning.DOWNMIX_SURROUND ->
                if (inputChannels > 6) 6 else inputChannels

            else -> autoOutputChannels(inputChannels)
        }

    /**
     * "Auto": fold down only as far as the output actually needs. An output
     * that can take six channels keeps 5.1 (7.1 folds into it); anything
     * narrower gets the same 2.0 the platform's own downmix would have produced
     * — except that it is made HERE, with the centre lifted, instead of by the
     * device with nothing.
     *
     * Auto used to hand every multichannel stream to the device untouched. That
     * is why the dialogue knob did nothing for the one stream a viewer actually
     * complains about — a 5.1 film on the living-room TV — while it worked for
     * the stereo tracks that needed it least. Layouts the fold matrix does not
     * model (4-channel quad, 3-channel) are still left alone, so this never
     * invents a mix, and 7.1 beyond a 6-channel output keeps its side pair
     * folded into the rears exactly as the explicit 5.1 option does.
     */
    private fun autoOutputChannels(inputChannels: Int): Int {
        if (inputChannels <= 2) return inputChannels
        if (PlayerAudioTuning.deviceMaxChannels < 6) return 2
        return if (inputChannels > 6) 6 else inputChannels
    }

    /**
     * Whether the layout setting now asks for a different number of channels
     * than the one the sink is carrying ([currentOutputChannels]) for a stream
     * decoded as [inputChannels] channels — i.e. whether the sink has to be
     * reconfigured to honour it.
     *
     * The sink builds its AudioTrack from the processed channel count, so the
     * one thing that cannot be changed from inside an
     * [androidx.media3.common.audio.AudioProcessor] is the number of channels:
     * this is the test [LiveDownmixAudioSink] runs on every buffer on the audio
     * thread before driving Media3's own reconfigure path. Everything else — the
     * centre lift, the surround trim, the volume gain — is per-sample and needs
     * no rebuild at all.
     *
     * Notably this is false for the common "nothing to fold" cases: a stereo
     * stream stays stereo whichever option is picked, and asking 5.1 for a 5.1
     * stream is the layout it already has, so those switches are free.
     */
    fun layoutChangeNeedsReconfigure(
        inputChannels: Int,
        currentOutputChannels: Int
    ): Boolean {
        if (inputChannels <= 0) return false
        return desiredOutputChannels(inputChannels) != currentOutputChannels
    }

    /**
     * Linked-channel peak limiter: ONE gain for every output channel, so the
     * stereo image never shifts when it engages (independent per-channel
     * limiting pulls the loud side down and walks the phantom centre around).
     *
     * Fast attack (a hard transient is caught inside ~2 ms), slow release
     * (~150 ms) so music does not pump. A downmix sums four to six channels of
     * a mix that is already near full scale, so without this the fold would
     * clip — which is exactly the crunchy sound a naive 5.1→2.0 fold is known
     * for; with it, the level can be pushed up instead.
     */
    class Limiter(
        private val ceiling: Float = 0.985f,
        attackMs: Float = 1.5f,
        releaseMs: Float = 150f,
        sampleRate: Int = 48_000
    ) {

        private val attackCoef =
            exp(
                -1f /
                    ((attackMs / 1000f) * sampleRate)
                        .coerceAtLeast(1f)
            )

        private val releaseCoef =
            exp(
                -1f /
                    ((releaseMs / 1000f) * sampleRate)
                        .coerceAtLeast(1f)
            )

        private var envelope = 1f

        /** Current gain reduction (1 = untouched). Visible for tests/diagnostics. */
        val gain: Float
            get() = envelope

        /** New stream (seek/track change): start clean instead of dragging a stale reduction in. */
        fun reset() {
            envelope = 1f
        }

        /** Gain to apply to every channel of the next frame, given its peak magnitude. */
        fun nextGain(peak: Float): Float {
            val magnitude = if (peak < 0f) -peak else peak
            val target = if (magnitude > ceiling) ceiling / magnitude else 1f
            envelope =
                if (target < envelope) {
                    attackCoef * envelope + (1f - attackCoef) * target
                } else {
                    releaseCoef * envelope + (1f - releaseCoef) * target
                }
            return envelope
        }
    }
}
