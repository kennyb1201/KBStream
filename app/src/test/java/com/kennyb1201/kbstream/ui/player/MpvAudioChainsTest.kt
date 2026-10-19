package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio strings MPV is handed: its `af` chain, its output layout and what it
 * may bitstream.
 *
 * These are the parity audit's audio findings, pinned where a JVM test can reach
 * them. A filter spec mpv rejects is SILENT - the chain simply does not apply and
 * the viewer hears the unchanged mix with no error anywhere - so the strings
 * themselves have to be checked, not just the call that writes them.
 *
 *  - M1: the volume boost now rides the `af` chain with a limiter BEHIND it,
 *    where before it was raw gain on mpv's `volume` property with nothing after
 *    it to catch a hot stream.
 *  - M2: passthrough follows the same Audio-output preference the main player
 *    honors, instead of mpv always decoding to PCM.
 *  - m1: the dialogue lift covers 5.0 as well as 5.1/7.1.
 *  - m2: Auto folds to what the device's output can actually carry, not to
 *    mpv's `auto-safe`.
 *  - m3: `af` has exactly one writer, so the limiter and the dialogue pan are
 *    composed into one chain rather than one erasing the other.
 */
class MpvAudioChainsTest {

    // ── the af chain ────────────────────────────────────────────────────────

    @Test
    fun `nothing is filtered while every knob is off`() {
        assertNull(MpvAudioChains.audioFilterSpec(dialogueLevel = 0, channels = 6, volumeBoostDb = 0))
    }

    @Test
    fun `the boost's gain and limiter are one chain, limiter last`() {
        val spec = MpvAudioChains.audioFilterSpec(dialogueLevel = 0, channels = 2, volumeBoostDb = 6)

        assertEquals("volume=6dB,alimiter=level=disabled:limit=0.97", spec)
        // The order IS the fix: a limiter ahead of the gain could not catch what
        // the gain does, which is exactly how the old raw-`volume` boost clipped.
        assertTrue(
            "the limiter must sit behind the gain",
            spec!!.indexOf("volume=") < spec.indexOf("alimiter=")
        )
    }

    @Test
    fun `the dialogue lift and the boost share the one af property`() {
        val spec = MpvAudioChains.audioFilterSpec(dialogueLevel = 1, channels = 6, volumeBoostDb = 3)

        assertTrue(
            "the pan comes first, the boost's chain after it: $spec",
            spec!!.startsWith("pan=5.1|") && spec.contains(",volume=3dB,alimiter=")
        )
        // One property, one write: two writers would leave whichever ran last.
        assertFalse("no second chain separator", spec.contains(",,"))
    }

    // ── the dialogue pan, per layout ────────────────────────────────────────

    @Test
    fun `the center is lifted on every layout that has one`() {
        assertEquals(
            "pan=5.1|c0=c0|c1=c1|c2=2.0500*c2|c3=c3|c4=c4|c5=c5",
            MpvAudioChains.dialogueFilterSpec(level = 3, channels = 6)
        )
        assertEquals(
            "5.0 is the audit's m1: FC is third there too, so it lifts the same way",
            "pan=5.0|c0=c0|c1=c1|c2=2.0500*c2|c3=c3|c4=c4",
            MpvAudioChains.dialogueFilterSpec(level = 3, channels = 5)
        )
        assertEquals(
            "pan=7.1|c0=c0|c1=c1|c2=2.0500*c2|c3=c3|c4=c4|c5=c5|c6=c6|c7=c7",
            MpvAudioChains.dialogueFilterSpec(level = 3, channels = 8)
        )
    }

    @Test
    fun `only the center carries the gain`() {
        val spec = MpvAudioChains.dialogueFilterSpec(level = 4, channels = 6)!!
        assertEquals("the gain must appear exactly once", 1, Regex("2\\.4000\\*").findAll(spec).count())
    }

    @Test
    fun `stereo lifts the phantom center instead`() {
        // gain 1.7 at level 2: mid 1.35, side +/-0.35. This is the same rule the
        // main player's midGain expresses, so a 2.0 track sounds the same on
        // both engines.
        assertEquals(
            "pan=stereo|c0=1.3500*c0+0.3500*c1|c1=0.3500*c0+1.3500*c1",
            MpvAudioChains.dialogueFilterSpec(level = 2, channels = 2)
        )
    }

    @Test
    fun `layouts with no center are left alone`() {
        // Mono has no center and nothing to fold against; 4.0 (quad) is
        // FL/FR/BL/BR, so "the center" there would have to be invented - and an
        // invented center is how a surround field gets mangled. Both get no
        // filter rather than a spec mpv could reject.
        assertNull(MpvAudioChains.dialogueFilterSpec(level = 2, channels = 1))
        assertNull(MpvAudioChains.dialogueFilterSpec(level = 2, channels = 4))
        assertNull(MpvAudioChains.dialogueFilterSpec(level = 2, channels = 7))
        assertNull(MpvAudioChains.dialogueFilterSpec(level = 2, channels = 0))
    }

    @Test
    fun `an off dialogue boost adds no pan`() {
        assertNull(MpvAudioChains.dialogueFilterSpec(level = 0, channels = 6))
    }

    // ── output layout ───────────────────────────────────────────────────────

    @Test
    fun `Auto folds to what the device's output can carry`() {
        assertEquals(
            "a stereo TV must not be handed 5.1 - the case Exo's Auto exists for",
            "stereo",
            MpvAudioChains.downmixLayout(PlayerAudioTuning.DOWNMIX_AUTO, deviceMaxChannels = 2)
        )
        assertEquals(
            "an AVR keeps six channels",
            "5.1",
            MpvAudioChains.downmixLayout(PlayerAudioTuning.DOWNMIX_AUTO, deviceMaxChannels = 6)
        )
        assertEquals(
            "7.1 is trimmed to 5.1, as the main player's own Auto does",
            "5.1",
            MpvAudioChains.downmixLayout(PlayerAudioTuning.DOWNMIX_AUTO, deviceMaxChannels = 8)
        )
    }

    @Test
    fun `the explicit choices stay explicit`() {
        assertEquals(
            "Stereo folds whatever the device could carry",
            "stereo",
            MpvAudioChains.downmixLayout(PlayerAudioTuning.DOWNMIX_STEREO, deviceMaxChannels = 8)
        )
        assertEquals(
            "5.1 keeps six channels even on a stereo output",
            "5.1",
            MpvAudioChains.downmixLayout(PlayerAudioTuning.DOWNMIX_SURROUND, deviceMaxChannels = 2)
        )
    }

    // ── passthrough ─────────────────────────────────────────────────────────

    @Test
    fun `decode never bitstreams, passthrough always does`() {
        assertEquals("", MpvAudioChains.audioSpdifSpec(PlayerAudioTuning.AUDIO_OUTPUT_DECODE, true))
        assertEquals("", MpvAudioChains.audioSpdifSpec(PlayerAudioTuning.AUDIO_OUTPUT_DECODE, false))
        assertEquals(
            MpvAudioChains.SPDIF_CODECS,
            MpvAudioChains.audioSpdifSpec(PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH, true)
        )
    }

    @Test
    fun `Auto bitstreams only while the tuning is doing nothing`() {
        // Otherwise the receiver would decode a stream the viewer just asked the
        // app to fold / lift / boost, and the change would vanish silently.
        assertEquals(
            MpvAudioChains.SPDIF_CODECS,
            MpvAudioChains.audioSpdifSpec(PlayerAudioTuning.AUDIO_OUTPUT_AUTO, tuningNeutral = true)
        )
        assertEquals(
            "",
            MpvAudioChains.audioSpdifSpec(PlayerAudioTuning.AUDIO_OUTPUT_AUTO, tuningNeutral = false)
        )
    }

    @Test
    fun `the bitstream list is the set the FFmpeg extension exists for`() {
        listOf("ac3", "eac3", "dts", "dts-hd", "truehd").forEach { codec ->
            assertTrue(
                "$codec must be bitstreamable",
                MpvAudioChains.SPDIF_CODECS.split(",").contains(codec)
            )
        }
    }
}
