package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions behind the ANDROID-Z fix, exercised directly: the ceiling
 * the bundled software (FFmpeg) audio decoder is never offered a track past,
 * and the shape in which a session with no audio renderer at all belongs to the
 * backup engine.
 *
 * The crash itself is native and unreachable from the JVM (see
 * [SoftwareAudioRules]); what IS reachable is the arithmetic on the format that
 * keeps it from being called, which is what these cover.
 */
class SoftwareAudioRulesTest {

    private fun pcm(mime: String, channels: Int): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(channels)
        .setSampleRate(48_000)
        .build()

    private fun group(type: Int, supported: Boolean, channels: Int): Tracks.Group {
        val mime = when (type) {
            C.TRACK_TYPE_AUDIO -> MimeTypes.AUDIO_AC3
            else -> MimeTypes.VIDEO_H264
        }
        return Tracks.Group(
            TrackGroup(
                Format.Builder()
                    .setSampleMimeType(mime)
                    .setChannelCount(channels)
                    .setSampleRate(48_000)
                    .build()
            ),
            /* adaptiveSupported= */ false,
            intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE),
            booleanArrayOf(/* selected= */ false)
        )
    }

    private fun tracks(vararg groups: Tracks.Group): Tracks = Tracks(groups.toList())

    // ── the software decoder's ceiling ────────────────────────────────────────

    @Test
    fun `a stereo or surround track is handed to the software decoder as before`() {
        listOf(1, 2, 6, 8).forEach { channels ->
            assertNull(
                "a ${channels}ch track has as many planes as an AVFrame can address",
                SoftwareAudioRules.refusesSoftwareDecode(pcm(MimeTypes.AUDIO_RAW, channels))
            )
        }
    }

    @Test
    fun `a track wider than the frame's plane array is refused`() {
        // The conversion that crashed: FLTP -> S16 with more than two channels,
        // and more than AV_NUM_DATA_POINTERS planes for media3's `frame->data`.
        listOf(9, 12, 16).forEach { channels ->
            val reason = SoftwareAudioRules.refusesSoftwareDecode(pcm(MimeTypes.AUDIO_RAW, channels))
            assertNotNull("a ${channels}ch track must be refused", reason)
            assertTrue(
                "the reason names both numbers: $reason",
                reason!!.contains("$channels") &&
                    reason.contains("${SoftwareAudioRules.FFMPEG_PLANE_LIMIT}")
            )
        }
    }

    @Test
    fun `the ceiling is the frame's plane array, not a device limit`() {
        assertEquals(8, SoftwareAudioRules.FFMPEG_PLANE_LIMIT)
    }

    @Test
    fun `a compressed format is not this question at all`() {
        // Passthrough is answered by the inner sink and never reaches
        // libswresample, so a wide bitstream is left alone here.
        val wide = pcm(MimeTypes.AUDIO_TRUEHD, 12)
        assertNull(SoftwareAudioRules.refusesSoftwareDecode(wide))
    }

    // ── what FFmpeg declines because the device can decode it ────────────────

    @Test
    fun `a codec the device decodes is declined, even for FFmpeg`() {
        // The Onn 4K Plus report: AAC 2.0 stereo through the FFmpeg audio
        // renderer in "prefer app decoders" mode came out as robotic noise,
        // while the same file was fine on MPV. AAC is exactly what MediaCodec
        // does well, so FFmpeg must not be handed it.
        assertTrue(
            SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(
                sampleMimeType = MimeTypes.AUDIO_AAC,
                deviceDecoderPresent = true
            )
        )
        listOf(
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3
        ).forEach { mime ->
            assertTrue(
                "$mime is decodable by the platform decoder that claimed it",
                SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(mime, true)
            )
        }
    }

    @Test
    fun `the bundle codecs keep FFmpeg even when the device lists a decoder`() {
        // Field log (Onn 4K Plus, 2026-10-19): the firmware lists
        // c2.amlogic.audio.decoder.dtshd for audio/vnd.dts, so the old rule
        // declined and the platform decoder took the DTS track — then starved
        // AudioTrack into silence. These three are the codecs the FFmpeg
        // bundle exists for; a listed decoder must not take them away.
        listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)
            .forEach { mime ->
                assertFalse(
                    "$mime must never decline, even with a listed platform decoder",
                    SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(mime, true)
                )
            }
    }

    @Test
    fun `a codec the device cannot decode is still FFmpeg's`() {
        // No platform decoder at all: the ordinary path, unchanged by the
        // never-decline set.
        listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD)
            .forEach { mime ->
                assertFalse(
                    "$mime has no platform decoder on these sticks",
                    SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(mime, false)
                )
            }
    }

    @Test
    fun `an unstated codec is never declined`() {
        // Nothing for a platform decoder to match: declining would take the
        // track from FFmpeg and hand it to nobody.
        assertFalse(SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(null, true))
        assertFalse(SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder("", true))
        assertFalse(SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder("   ", true))
    }

    @Test
    fun `a format with no channel count at all is left to media3`() {
        val unknown = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(48_000)
            .build()
        assertNull(SoftwareAudioRules.refusesSoftwareDecode(unknown))
    }

    // ── when the session belongs to the backup engine ────────────────────────

    @Test
    fun `a file whose every audio track is unsupported moves to the backup engine`() {
        val verdict = SoftwareAudioRules.usesBackupEngine(
            tracks(
                group(C.TRACK_TYPE_VIDEO, supported = true, channels = 0),
                group(C.TRACK_TYPE_AUDIO, supported = false, channels = 12)
            )
        )
        assertNotNull("silent playback with a supported picture is the handoff shape", verdict)
    }

    @Test
    fun `a file with one playable audio track among several stays where it is`() {
        // 16-channel TrueHD refused, 5.1 AC-3 taken: media3 has already
        // switched to the supported group, so there is nothing to rescue.
        val verdict = SoftwareAudioRules.usesBackupEngine(
            tracks(
                group(C.TRACK_TYPE_VIDEO, supported = true, channels = 0),
                group(C.TRACK_TYPE_AUDIO, supported = false, channels = 16),
                group(C.TRACK_TYPE_AUDIO, supported = true, channels = 6)
            )
        )
        assertNull(verdict)
    }

    @Test
    fun `a file with no audio track at all is not a handoff`() {
        // A silent film, a music video the extractor has not opened yet: with
        // no audio group reported there is no audio decision to make.
        assertNull(
            SoftwareAudioRules.usesBackupEngine(
                tracks(group(C.TRACK_TYPE_VIDEO, supported = true, channels = 0))
            )
        )
    }

    @Test
    fun `a file whose picture is unsupported too is left to the error ladder`() {
        // Nothing plays here, and the ordinary decoder ladder (and the failure
        // card behind it) is what answers that - not a handoff taken away from
        // it before it can run.
        assertNull(
            SoftwareAudioRules.usesBackupEngine(
                tracks(
                    group(C.TRACK_TYPE_VIDEO, supported = false, channels = 0),
                    group(C.TRACK_TYPE_AUDIO, supported = false, channels = 12)
                )
            )
        )
    }

    @Test
    fun `an audio-only file with a supported track is left alone`() {
        assertNull(
            SoftwareAudioRules.usesBackupEngine(
                tracks(group(C.TRACK_TYPE_AUDIO, supported = true, channels = 2))
            )
        )
    }
}
