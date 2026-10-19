package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate that makes the bundled FFmpeg audio renderer DEVICE-first, exercised
 * without a device: both inputs to it are interfaces.
 *
 * The field case behind it (Onn 4K Plus, 2026-10-18) is an AAC 2.0 stereo track
 * played as "robotic computer noises" in "Prefer app decoders (FFmpeg)" mode,
 * because extension-prefer mode puts the FFmpeg renderer ahead of MediaCodec and
 * a renderer that supports a format is given it. What is pinned here is the
 * answer itself: a format a platform decoder claims is [C.FORMAT_UNSUPPORTED_TYPE]
 * (so MediaCodec, the next renderer in the list, takes the track), while a
 * format no platform decoder claims is handed on to the FFmpeg renderer exactly
 * as before - the DTS / DTS-HD / TrueHD case the setting exists for.
 */
class DeviceFirstAudioCapabilitiesTest {

    /** A stand-in for the FFmpeg audio renderer's own capabilities. */
    private class FakeCapabilities(
        private val answer: Int
    ) : RendererCapabilities {

        /** What the renderer was asked, or null when it was never consulted. */
        var asked: Format? = null

        override fun getName(): String = "FakeFfmpegAudioRenderer"

        override fun getTrackType(): Int = C.TRACK_TYPE_AUDIO

        override fun supportsFormat(format: Format): Int {
            asked = format
            return answer
        }

        override fun supportsMixedMimeTypeAdaptation(): Int =
            RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private fun format(mime: String?): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setChannelCount(2)
        .setSampleRate(48_000)
        .build()

    private fun gated(fake: FakeCapabilities, decodable: Set<String>) =
        DeviceFirstAudioCapabilities(fake) { mime -> mime in decodable }

    @Test
    fun `a format the device decodes is declined, not asked about`() {
        val fake = FakeCapabilities(C.FORMAT_HANDLED)
        val gated = gated(fake, decodable = setOf(MimeTypes.AUDIO_AAC))

        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            gated.supportsFormat(format(MimeTypes.AUDIO_AAC))
        )
        // The decline is the answer, not a delegation that happens to agree:
        // asking the renderer here is what handed AAC to FFmpeg in the field.
        assertNull("the renderer must not be consulted for a device-decodable format", fake.asked)
    }

    @Test
    fun `a format the device cannot decode still reaches the renderer`() {
        val fake = FakeCapabilities(C.FORMAT_HANDLED)
        val gated = gated(fake, decodable = setOf(MimeTypes.AUDIO_AAC))

        listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_TRUEHD).forEach { mime ->
            assertEquals(
                "$mime must keep the answer the FFmpeg renderer gave",
                C.FORMAT_HANDLED,
                gated.supportsFormat(format(mime))
            )
        }
        assertNotNull("the renderer's own answer is what is returned", fake.asked)
    }

    @Test
    fun `an unstated mime is left to the renderer`() {
        // Nothing for a platform decoder to match, so there is nothing to
        // decline: the track stays where it was.
        val fake = FakeCapabilities(C.FORMAT_HANDLED)
        val gated = DeviceFirstAudioCapabilities(fake) { true }

        listOf(null, "", "   ").forEach { mime ->
            assertEquals(C.FORMAT_HANDLED, gated.supportsFormat(format(mime)))
        }
    }

    @Test
    fun `a rough device answer leaves a playable format playable`() {
        // The delegate's answer is passed through untouched, whatever it is -
        // including a fallback answer, which is what a device decoder that
        // claims a codec and then fails at runtime leans on.
        val fallback = FakeCapabilities(C.FORMAT_UNSUPPORTED_SUBTYPE)
        val gated = gated(fallback, decodable = emptySet())

        assertEquals(
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            gated.supportsFormat(format(MimeTypes.AUDIO_AAC))
        )
    }

    @Test
    fun `everything that is not about a format is passed through`() {
        val fake = FakeCapabilities(C.FORMAT_HANDLED)
        val gated = gated(fake, decodable = setOf(MimeTypes.AUDIO_AAC))

        // The player reads these to place the renderer in the track-type map
        // and to log; the gate must not change who this renderer is.
        assertEquals("FakeFfmpegAudioRenderer", gated.name)
        assertEquals(C.TRACK_TYPE_AUDIO, gated.trackType)
        assertEquals(
            RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
            gated.supportsMixedMimeTypeAdaptation()
        )
    }
}
