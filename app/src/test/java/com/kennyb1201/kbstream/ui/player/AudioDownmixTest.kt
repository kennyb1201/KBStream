package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio downmix arithmetic behind the dialogue enhancer.
 *
 * Reported problem: a single-audio-track stream was so quiet that the TV's
 * volume had to be pinned, and dialogue sat under the score on a 5.1 mix. The
 * fix folds the channels in the app (centre lifted, surrounds trimmed, output
 * limited) instead of leaving it to the platform's plain downmix.
 */
class AudioDownmixTest {

    // ── Layouts ────────────────────────────────────────────────────────────

    @Test
    fun `channel roles follow the media3 Android order`() {
        assertArrayEquals(intArrayOf(AudioDownmix.FL, AudioDownmix.FR), AudioDownmix.rolesFor(2))
        assertArrayEquals(
            intArrayOf(AudioDownmix.FL, AudioDownmix.FR, AudioDownmix.FC),
            AudioDownmix.rolesFor(3)
        )
        // 4 channels is quad, NOT 3 fronts plus an LFE.
        assertArrayEquals(
            intArrayOf(AudioDownmix.FL, AudioDownmix.FR, AudioDownmix.BL, AudioDownmix.BR),
            AudioDownmix.rolesFor(4)
        )
        assertArrayEquals(
            intArrayOf(
                AudioDownmix.FL,
                AudioDownmix.FR,
                AudioDownmix.FC,
                AudioDownmix.LFE,
                AudioDownmix.BL,
                AudioDownmix.BR
            ),
            AudioDownmix.rolesFor(6)
        )
        // 7.1: sides are the last pair, after the rears.
        assertArrayEquals(
            intArrayOf(
                AudioDownmix.FL,
                AudioDownmix.FR,
                AudioDownmix.FC,
                AudioDownmix.LFE,
                AudioDownmix.BL,
                AudioDownmix.BR,
                AudioDownmix.SL,
                AudioDownmix.SR
            ),
            AudioDownmix.rolesFor(8)
        )
    }

    @Test
    fun `an unknown channel count has no role map`() {
        assertNull(AudioDownmix.rolesFor(0))
        assertNull(AudioDownmix.rolesFor(9))
    }

    // ── The fold itself ────────────────────────────────────────────────────

    @Test
    fun `five point one folds to stereo with the platform's own bass and surrounds`() {
        val matrix = AudioDownmix.mixingMatrix(
            inputChannels = 6,
            outputChannels = 2,
            centerGain = PlayerAudioTuning.CENTER_BASE,
            surroundScale = 1f
        )
        assertNotNull(matrix)

        // FL -> left only, FR -> right only.
        assertEquals(1f, matrix!![0 * 2 + 0], 1e-4f)
        assertEquals(0f, matrix[0 * 2 + 1], 1e-4f)
        assertEquals(0f, matrix[1 * 2 + 0], 1e-4f)
        assertEquals(1f, matrix[1 * 2 + 1], 1e-4f)

        // Centre feeds both sides at the standard 1/sqrt(2).
        assertEquals(0.7071f, matrix[2 * 2 + 0], 1e-4f)
        assertEquals(0.7071f, matrix[2 * 2 + 1], 1e-4f)

        // LFE keeps the level the platform uses, so bass does not change.
        assertEquals(0.5f, matrix[3 * 2 + 0], 1e-4f)
        assertEquals(0.5f, matrix[3 * 2 + 1], 1e-4f)

        // Back left goes left, back right goes right.
        assertEquals(0.7071f, matrix[4 * 2 + 0], 1e-4f)
        assertEquals(0f, matrix[4 * 2 + 1], 1e-4f)
        assertEquals(0f, matrix[5 * 2 + 0], 1e-4f)
        assertEquals(0.7071f, matrix[5 * 2 + 1], 1e-4f)
    }

    @Test
    fun `the centre lift puts dialogue above the surround effects`() {
        val neutral = AudioDownmix.mixingMatrix(6, 2, PlayerAudioTuning.CENTER_BASE, 1f)!!
        val boosted = AudioDownmix.mixingMatrix(6, 2, PlayerAudioTuning.CENTER_BASE * 1.5f, 0.8f)!!

        val neutralCentre = neutral[2 * 2 + 0]
        val boostedCentre = boosted[2 * 2 + 0]
        assertTrue("centre must come up", boostedCentre > neutralCentre)

        // ...while the surrounds that carry explosions come DOWN.
        assertTrue(boosted[4 * 2 + 0] < neutral[4 * 2 + 0])

        // Fronts are left exactly where the mix put them.
        assertEquals(neutral[0 * 2 + 0], boosted[0 * 2 + 0], 1e-6f)
    }

    @Test
    fun `seven point one folds into five point one through the rears`() {
        val matrix = AudioDownmix.mixingMatrix(8, 6, PlayerAudioTuning.CENTER_BASE, 1f)
        assertNotNull(matrix)

        // Side left -> rear left (index 4), side right -> rear right (index 5).
        assertEquals(1f, matrix!![6 * 6 + 4], 1e-4f)
        assertEquals(0f, matrix[6 * 6 + 5], 1e-4f)
        assertEquals(1f, matrix[7 * 6 + 5], 1e-4f)
        // Fronts, centre and LFE pass straight through.
        assertEquals(1f, matrix[0 * 6 + 0], 1e-4f)
        assertEquals(1f, matrix[1 * 6 + 1], 1e-4f)
        assertEquals(1f, matrix[2 * 6 + 2], 1e-4f)
        assertEquals(1f, matrix[3 * 6 + 3], 1e-4f)
    }

    @Test
    fun `a back centre channel is split across both rears`() {
        // 7 channels is 6.1: [FL, FR, FC, LFE, BC, SL, SR].
        val matrix = AudioDownmix.mixingMatrix(7, 6, PlayerAudioTuning.CENTER_BASE, 1f)!!
        assertEquals(0.7071f, matrix[4 * 6 + 4], 1e-4f)
        assertEquals(0.7071f, matrix[4 * 6 + 5], 1e-4f)
        // Its side pair still folds into the rears.
        assertEquals(1f, matrix[5 * 6 + 4], 1e-4f)
        assertEquals(1f, matrix[6 * 6 + 5], 1e-4f)
    }

    @Test
    fun `matching layouts have no matrix at all`() {
        // Nothing to fold: the processor then only applies gain and limiting,
        // and the sink keeps the layout it already has.
        assertNull(AudioDownmix.mixingMatrix(2, 2, PlayerAudioTuning.CENTER_BASE, 1f))
        assertNull(AudioDownmix.mixingMatrix(6, 6, PlayerAudioTuning.CENTER_BASE, 1f))
        // Unmodelled target layouts stay untouched rather than guessed at.
        assertNull(AudioDownmix.mixingMatrix(8, 3, PlayerAudioTuning.CENTER_BASE, 1f))
    }

    /**
     * The other half of the same idea: with the layout left to the device there
     * is nothing to fold, and the dialogue knob used to do nothing at all for a
     * 5.1/7.1 file in exactly that (default) configuration. It is a per-channel
     * balance instead — the device then folds an already-lifted centre.
     */
    @Test
    fun `a stream that keeps its layout is balanced in place`() {
        val balance = AudioDownmix.gainMatrix(inputChannels = 6, centerGain = 1.7f, surroundScale = 0.8f)
        assertNotNull(balance)

        // Centre up...
        assertEquals(1.7f, balance!![2 * 6 + 2], 1e-4f)
        // ...the surrounds that carry score and explosions down...
        assertEquals(0.8f, balance[4 * 6 + 4], 1e-4f)
        assertEquals(0.8f, balance[5 * 6 + 5], 1e-4f)
        // ...and the fronts and LFE exactly where the mixer put them.
        assertEquals(1f, balance[0 * 6 + 0], 1e-4f)
        assertEquals(1f, balance[1 * 6 + 1], 1e-4f)
        assertEquals(1f, balance[3 * 6 + 3], 1e-4f)
        // A gain, not a fold: nothing bleeds into another channel.
        assertEquals(0f, balance[0 * 6 + 1], 1e-4f)

        // 7.1's sides are trimmed too, and its back centre is not a surround.
        val sevenOne = AudioDownmix.gainMatrix(8, 1.7f, 0.6f)!!
        assertEquals(0.6f, sevenOne[6 * 8 + 6], 1e-4f)
        assertEquals(0.6f, sevenOne[7 * 8 + 7], 1e-4f)
        val sixOne = AudioDownmix.gainMatrix(7, 1.7f, 0.6f)!!
        assertEquals(0.6f, sixOne[4 * 7 + 4], 1e-4f)
    }

    @Test
    fun `nothing is balanced while the dialogue boost is off`() {
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        try {
            // 1.0 everywhere means no matrix at all: the default path stays a
            // byte-for-byte pass-through.
            assertEquals(1f, PlayerAudioTuning.inPlaceCenterGain, 1e-4f)
            assertNull(AudioDownmix.gainMatrix(6, PlayerAudioTuning.inPlaceCenterGain, 1f))
            assertNull(AudioDownmix.gainMatrix(8, PlayerAudioTuning.inPlaceCenterGain, 1f))
            assertNull(AudioDownmix.gainMatrix(1, PlayerAudioTuning.inPlaceCenterGain, 1f))
        } finally {
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }

        // Stereo has no centre channel: it is lifted through mid/side instead.
        assertNull(AudioDownmix.gainMatrix(2, 1.7f, 0.6f))
        // An unmodelled layout is left alone rather than guessed at.
        assertNull(AudioDownmix.gainMatrix(9, 1.7f, 0.6f))

        // And with the boost on, the in-place centre gain really is a lift.
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 2, 0)
        try {
            assertTrue(PlayerAudioTuning.inPlaceCenterGain > 1f)
            assertNotNull(
                AudioDownmix.gainMatrix(6, PlayerAudioTuning.inPlaceCenterGain, 0.6f)
            )
        } finally {
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }
    }

    // ── Which layout a setting asks for ────────────────────────────────────

    @Test
    fun `stereo folds what can be folded and never upmixes`() {
        PlayerAudioTuning.apply(
            PlayerAudioTuning.DOWNMIX_STEREO,
            dialogueBoost = 0,
            volumeBoostDb = 0
        )
        try {
            assertEquals(2, AudioDownmix.desiredOutputChannels(6))
            assertEquals(2, AudioDownmix.desiredOutputChannels(8))
            // Already stereo (or mono): leave it alone.
            assertEquals(2, AudioDownmix.desiredOutputChannels(2))
            assertEquals(1, AudioDownmix.desiredOutputChannels(1))
            // And a stereo stream asked for 5.1 the same way.
            assertEquals(2, AudioDownmix.desiredOutputChannels(2))
        } finally {
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }
    }

    @Test
    fun `surround keeps five point one and folds seven point one into it`() {
        PlayerAudioTuning.apply(
            PlayerAudioTuning.DOWNMIX_SURROUND,
            dialogueBoost = 0,
            volumeBoostDb = 0
        )
        try {
            assertEquals(6, AudioDownmix.desiredOutputChannels(8))
            assertEquals(6, AudioDownmix.desiredOutputChannels(6))
            assertEquals(2, AudioDownmix.desiredOutputChannels(2))
        } finally {
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }
    }

    @Test
    fun `auto folds to the width the output can actually carry`() {
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        try {
            // A stereo output — the default until a sink has reported its
            // width, and what a TV's own speakers are: multichannel folds
            // down there. It is done HERE rather than left to the device, so
            // the centre lift comes with it (the whole point of Auto).
            PlayerAudioTuning.deviceMaxChannels = 2
            assertEquals(2, AudioDownmix.desiredOutputChannels(6))
            assertEquals(2, AudioDownmix.desiredOutputChannels(8))
            assertEquals(2, AudioDownmix.desiredOutputChannels(2))

            // An AVR that really carries six channels keeps 5.1, and 7.1
            // trims into it instead of being handed over untouched.
            PlayerAudioTuning.deviceMaxChannels = 6
            assertEquals(6, AudioDownmix.desiredOutputChannels(6))
            assertEquals(6, AudioDownmix.desiredOutputChannels(8))
            assertEquals(2, AudioDownmix.desiredOutputChannels(2))
        } finally {
            PlayerAudioTuning.deviceMaxChannels = 2
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }
        assertTrue(PlayerAudioTuning.isNeutral)
    }

    /**
     * The one knob that cannot be applied from inside the sample stream: the sink
     * carries a fixed channel count, so switching layouts means reconfiguring it
     * ([LiveDownmixAudioSink]). These are the cases where that is — and is not —
     * worth tearing the running output down for.
     */
    @Test
    fun `the sink is reconfigured only when the setting really changes the width`() {
        try {
            // A 5.1 stream, asked for stereo while it is still carrying six
            // channels: the fold needs the output rebuilt.
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_STEREO, 0, 0)
            assertTrue(AudioDownmix.layoutChangeNeedsReconfigure(6, 6))
            assertTrue(AudioDownmix.layoutChangeNeedsReconfigure(8, 8))
            // Already folded: nothing would change, so nothing is rebuilt.
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(6, 2))
            // A stereo (or mono) source stays as it is whichever option is
            // picked, so those switches are free.
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(2, 2))
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(1, 1))

            // Driving the other way: back to Auto. Auto follows the width the
            // OUTPUT can carry, so whether the sink has to be rebuilt depends
            // on the device, not only on the stream.
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
            PlayerAudioTuning.deviceMaxChannels = 2
            // A stereo output with the fold already in place: nothing to do.
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(6, 2))
            // The same stereo output while the sink still carries 5.1: now it
            // has to fold.
            assertTrue(AudioDownmix.layoutChangeNeedsReconfigure(6, 6))
            // A six-channel output already carrying 5.1 keeps it as it is.
            PlayerAudioTuning.deviceMaxChannels = 6
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(6, 6))

            // 7.1 to 5.1 is a real fold too, and 5.1 is already there.
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_SURROUND, 0, 0)
            assertTrue(AudioDownmix.layoutChangeNeedsReconfigure(8, 8))
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(8, 6))
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(6, 6))

            // No decoded width to reason about (the processor stands aside).
            assertFalse(AudioDownmix.layoutChangeNeedsReconfigure(0, 0))
        } finally {
            PlayerAudioTuning.deviceMaxChannels = 2
            PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        }
    }

    // ── Gains ─────────────────────────────────────────────────────────────

    @Test
    fun `dialogue boost lifts the centre and the stereo mid channel`() {
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        val offCentre = PlayerAudioTuning.centerGain
        val offMid = PlayerAudioTuning.midGain

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 1, 0)
        val lowCentre = PlayerAudioTuning.centerGain
        val lowMid = PlayerAudioTuning.midGain

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 2, 0)
        val highCentre = PlayerAudioTuning.centerGain

        assertTrue(lowCentre > offCentre)
        assertTrue(highCentre > lowCentre)
        assertTrue(lowMid > offMid)

        // Untouched at Off, so the default path is exactly the old behaviour.
        assertEquals(PlayerAudioTuning.CENTER_BASE, offCentre, 1e-4f)
        assertEquals(1f, offMid, 1e-4f)

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
    }

    @Test
    fun `volume boost converts dB to a linear gain`() {
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        assertEquals(1f, PlayerAudioTuning.linearGain, 1e-4f)

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 6)
        assertEquals(1.995f, PlayerAudioTuning.linearGain, 1e-2f)

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 12)
        assertEquals(3.981f, PlayerAudioTuning.linearGain, 1e-2f)

        assertFalse(PlayerAudioTuning.isNeutral)

        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
    }

    // ── Limiter ───────────────────────────────────────────────────────────

    @Test
    fun `quiet audio passes through the limiter untouched`() {
        val limiter = AudioDownmix.Limiter(sampleRate = 48_000)
        repeat(200) {
            assertEquals(1f, limiter.nextGain(0.4f), 1e-4f)
        }
    }

    @Test
    fun `a peak above the ceiling pulls the gain down`() {
        val limiter = AudioDownmix.Limiter(sampleRate = 48_000)
        // 1.8 into a 0.985 ceiling must come down, and do it over the next
        // handful of samples rather than in one step.
        val first = limiter.nextGain(1.8f)
        assertTrue("limiter must engage", first < 1f)
        repeat(2_000) { limiter.nextGain(1.8f) }
        assertTrue("must converge on the ceiling", limiter.gain < 0.6f)
        assertTrue(limiter.gain * 1.8f <= 0.985f + 0.01f)
    }

    @Test
    fun `the limiter releases once the peak is gone`() {
        val limiter = AudioDownmix.Limiter(sampleRate = 48_000)
        repeat(2_000) { limiter.nextGain(1.8f) }
        val reduced = limiter.gain
        assertTrue(reduced < 0.7f)

        repeat(48_000) { limiter.nextGain(0.2f) }
        assertTrue("gain must come back", limiter.gain > 0.99f)
    }

    @Test
    fun `reset clears a stale reduction for a new stream`() {
        val limiter = AudioDownmix.Limiter(sampleRate = 48_000)
        repeat(5_000) { limiter.nextGain(2f) }
        assertTrue(limiter.gain < 0.7f)

        limiter.reset()
        assertEquals(1f, limiter.gain, 1e-4f)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun assertArrayEquals(
        expected: IntArray,
        actual: IntArray?
    ) {
        assertEquals(expected.toList(), actual?.toList())
    }
}
