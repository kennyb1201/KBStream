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
    fun `auto leaves the decoded layout alone`() {
        PlayerAudioTuning.apply(PlayerAudioTuning.DOWNMIX_AUTO, 0, 0)
        assertEquals(6, AudioDownmix.desiredOutputChannels(6))
        assertEquals(2, AudioDownmix.desiredOutputChannels(2))
        assertTrue(PlayerAudioTuning.isNeutral)
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
