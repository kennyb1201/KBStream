@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The player's audio track label.
 *
 * Reported problem: the picker listed languages only, so two English tracks
 * ("5.1" and "stereo") were indistinguishable and nothing said what the codec
 * was. The codec came from [Format.codecs], which container audio tracks leave
 * empty — media3's Matroska and FFmpeg paths never set it for audio — so the
 * sample MIME type has to carry the answer.
 *
 * Assertions read `EN`, not `ENG`: media3's Format constructor runs the
 * language through Util.normalizeLanguageCode, so "eng" is stored (and shown)
 * as "en". That normalization is upstream of this label, so the test asserts
 * what the player actually renders.
 */
class AudioTrackLabelTest {

    private fun format(
        language: String? = "eng",
        mime: String? = "audio/eac3",
        codecs: String? = null,
        channels: Int = 6,
        bitrate: Int = 640_000
    ) = Format.Builder()
        .setLanguage(language)
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setChannelCount(channels)
        // media3's builder has no setBitrate: Format.bitrate is the deprecated
        // alias of averageBitrate, which is what extractors fill in.
        .setAverageBitrate(bitrate)
        .build()

    @Test
    fun `label carries language, codec, channels and bitrate`() {
        assertEquals("EN • EAC3 • 6ch • 640 kbps", audioTrackLabel(format()))
    }

    @Test
    fun `a container track with no codec string still names its codec`() {
        // What a Matroska/FFmpeg audio track actually looks like: no codecs,
        // so the MIME type is the only thing that knows.
        val expected = mapOf(
            // C.AUDIO_AAC — the one an earlier version printed raw as
            // "AUDIO/MP4A-LATM", because only "audio/aac" counted as AAC.
            "audio/mp4a-latm" to "AAC",
            "audio/ac3" to "AC-3",
            "audio/eac3" to "EAC3",
            "audio/vnd.dts" to "DTS",
            "audio/vnd.dts.hd" to "DTS-HD",
            // Space-free names, since the picker uppercases the codec.
            "audio/vnd.dolby.mlp" to "TRUEHD",
            "audio/flac" to "FLAC",
            "audio/opus" to "OPUS",
            "audio/vorbis" to "VORBIS",
            "audio/mpeg" to "MP3",
            "audio/mpeg-l2" to "MP2",
            "audio/alac" to "ALAC",
            "audio/raw" to "PCM",
            "audio/ac4" to "AC-4"
        )
        expected.forEach { (mime, codec) ->
            assertEquals(
                "$mime should label as $codec",
                "EN • $codec • 2ch",
                audioTrackLabel(format(mime = mime, channels = 2, bitrate = 0))
            )
        }
    }

    @Test
    fun `an explicit codec string wins over the MIME type`() {
        assertEquals(
            "EN • EAC3 • 6ch • 640 kbps",
            audioTrackLabel(format(mime = "audio/eac3", codecs = "ec-3"))
        )
        // E-AC-3 (HLS "ec-3") must not be reported as plain AC-3, and AC-3 must
        // not be reported as E-AC-3: "audio/eac3" contains "ac3".
        assertEquals(
            "EN • AC-3 • 6ch • 640 kbps",
            audioTrackLabel(format(mime = "audio/ac3"))
        )
    }

    @Test
    fun `two English tracks are told apart by codec and channels`() {
        val surround = audioTrackLabel(format(mime = "audio/eac3", channels = 6, bitrate = 640_000))
        val stereo = audioTrackLabel(format(mime = "audio/aac", channels = 2, bitrate = 128_000))

        assertEquals("EN • EAC3 • 6ch • 640 kbps", surround)
        assertEquals("EN • AAC • 2ch • 128 kbps", stereo)
        assertNotEquals(surround, stereo)
    }

    @Test
    fun `a track with no language still leads with its codec`() {
        assertEquals("EAC3 • 6ch • 640 kbps", audioTrackLabel(format(language = null)))
    }

    @Test
    fun `a track with nothing known is still listed`() {
        assertEquals("Track", audioTrackLabel(Format.Builder().build()))
    }

    @Test
    fun `an unknown MIME type is shown rather than dropped`() {
        assertEquals(
            "EN • AUDIO/X-CUSTOM • 2ch",
            audioTrackLabel(format(mime = "audio/x-custom", channels = 2, bitrate = 0))
        )
    }
}
