package com.kennyb1201.kbstream.ui.player

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode-vs-passthrough decision behind Settings -> Video & Audio -> Audio
 * Output. It is the one place that decides whether the app must hand the sink
 * PCM (so [AudioDownmixProcessor] can run) or may bitstream the original format
 * to a receiver.
 */
class PlayerAudioOutputTest {

    private val savedDownmix = PlayerAudioTuning.downmixTarget
    private val savedDialogue = PlayerAudioTuning.dialogueBoost
    private val savedVolume = PlayerAudioTuning.volumeBoostDb

    @After
    fun restore() {
        PlayerAudioTuning.downmixTarget = savedDownmix
        PlayerAudioTuning.dialogueBoost = savedDialogue
        PlayerAudioTuning.volumeBoostDb = savedVolume
    }

    @Test
    fun `decode mode always decodes, even with the tuning neutral`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_AUTO
        PlayerAudioTuning.dialogueBoost = 0
        PlayerAudioTuning.volumeBoostDb = 0
        assertTrue(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_DECODE))
    }

    @Test
    fun `passthrough never decodes, even when a downmix is set`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_STEREO
        PlayerAudioTuning.dialogueBoost = 2
        PlayerAudioTuning.volumeBoostDb = 6
        assertFalse(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH))
    }

    @Test
    fun `auto passes through when nothing is being tuned`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_AUTO
        PlayerAudioTuning.dialogueBoost = 0
        PlayerAudioTuning.volumeBoostDb = 0
        assertFalse(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_AUTO))
    }

    @Test
    fun `auto decodes when a downmix is chosen`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_STEREO
        PlayerAudioTuning.dialogueBoost = 0
        PlayerAudioTuning.volumeBoostDb = 0
        assertTrue(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_AUTO))
    }

    @Test
    fun `auto decodes when only the dialogue boost is up`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_AUTO
        PlayerAudioTuning.dialogueBoost = 1
        PlayerAudioTuning.volumeBoostDb = 0
        assertTrue(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_AUTO))
    }

    @Test
    fun `auto decodes when only the volume boost is up`() {
        PlayerAudioTuning.downmixTarget = PlayerAudioTuning.DOWNMIX_AUTO
        PlayerAudioTuning.dialogueBoost = 0
        PlayerAudioTuning.volumeBoostDb = 3
        assertTrue(PlayerAudioTuning.requiresDecode(PlayerAudioTuning.AUDIO_OUTPUT_AUTO))
    }
}
