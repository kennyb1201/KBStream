package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.ui.player.MpvPlayerView.FileReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's INFO panel rows, as text.
 *
 * The panel exists so a viewer can read one file's numbers on both engines, and
 * every row here is a mapping from an mpv property that is allowed to be
 * missing. Both failure modes are pinned: a row that comes out empty (or says
 * "null") tells the viewer nothing, and a row that invents a value - "software"
 * decode where mpv simply has not answered yet, "HDR" over an SDR file that
 * reported a peak - is worse, because it reads as an answer.
 */
class MpvInfoLinesTest {

    private fun report(
        videoFormat: String? = "h264",
        width: Int = 1920,
        height: Int = 800,
        hwdec: String? = "mediacodec",
        pixelFormat: String? = null,
        primaries: String? = null,
        gamma: String? = null,
        sigPeak: Double? = null,
        videoBitrate: Int = 0,
        audioCodec: String? = "eac3",
        audioChannels: Int? = 6,
        audioSampleRate: Int? = 48_000,
        audioBitrate: Int? = null,
        subtitle: String? = null
    ) = FileReport(
        videoFormat = videoFormat,
        width = width,
        height = height,
        hwdec = hwdec,
        pixelFormat = pixelFormat,
        primaries = primaries,
        gamma = gamma,
        sigPeak = sigPeak,
        videoBitrate = videoBitrate,
        audioCodec = audioCodec,
        audioChannels = audioChannels,
        audioSampleRate = audioSampleRate,
        audioBitrate = audioBitrate,
        subtitle = subtitle
    )

    // ── the engine row ────────────────────────────────────────────────────

    @Test
    fun `the engine row names this engine`() {
        // The whole point of the row: it is the only place on screen that says
        // whether the picture is coming from mpv or whether the session landed
        // here because the other engine handed the file over.
        assertEquals("MPV (libmpv)", MpvInfoLines.ENGINE_LABEL)
    }

    // ── VIDEO ─────────────────────────────────────────────────────────────

    @Test
    fun `the resolution line carries the pixel size and the main player's tier`() {
        // The tier comes from the shared normalizeResolution, so the same file
        // reads "1080p" on both engines rather than "1080p" on one and
        // "1920x800" on the other.
        assertEquals(
            "Resolution: 1920×800 (1080p)",
            MpvInfoLines.resolutionLine(1920, 800)
        )
        assertEquals("Resolution: 3840×2160 (4K)", MpvInfoLines.resolutionLine(3840, 2160))
    }

    @Test
    fun `a resolution mpv has not reported yet is a dash, not a zero`() {
        // Before the first decoded frame both properties read 0. "Resolution:
        // 0×0" is a number a viewer would try to interpret; the dash is not.
        assertEquals("Resolution: —", MpvInfoLines.resolutionLine(0, 0))
        assertEquals("Resolution: —", MpvInfoLines.resolutionLine(1920, 0))
    }

    @Test
    fun `the codec line goes through the shared normalizer`() {
        assertEquals("Codec: H.264", MpvInfoLines.codecLine("h264"))
        assertEquals("Codec: H.265", MpvInfoLines.codecLine("hevc"))
        // Nothing to normalize is a dash, never a blank or the word null.
        assertEquals("Codec: —", MpvInfoLines.codecLine(null))
        assertEquals("Codec: —", MpvInfoLines.codecLine("   "))
    }

    @Test
    fun `the decode line names the decoder in use and calls no-decoder software`() {
        assertEquals("Decode: mediacodec", MpvInfoLines.decodeLine("mediacodec"))
        // mpv's own way of saying hardware decoding is off.
        assertEquals("Decode: software", MpvInfoLines.decodeLine("no"))
        assertEquals("Decode: software", MpvInfoLines.decodeLine("none"))
        assertEquals("Decode: software", MpvInfoLines.decodeLine(null))
        assertEquals("Decode: software", MpvInfoLines.decodeLine("  "))
    }

    @Test
    fun `HDR is decided by the transfer function and primaries, not by the peak`() {
        assertEquals(
            "HDR: pq • bt.2020 • peak 1000 nits",
            MpvInfoLines.hdrLine("pq", "bt.2020", 1000.0)
        )
        // HLG alone is still HDR.
        assertEquals("HDR: hlg", MpvInfoLines.hdrLine("hlg", null, null))
        // A peak on an SDR transfer is not HDR: printing "HDR" over it would be
        // the panel lying about the one thing it is being read for.
        assertNull(MpvInfoLines.hdrLine("bt.1886", "bt.709", 400.0))
        assertNull(MpvInfoLines.hdrLine(null, null, 1000.0))
        // A peak mpv reports as 0 is not a value: it is the absence of one.
        assertEquals("HDR: pq • bt.2020", MpvInfoLines.hdrLine("pq", "bt.2020", 0.0))
        // And a peak with the fraction mpv reports is rounded, not printed raw.
        assertEquals(
            "HDR: pq • bt.2020 • peak 1000 nits",
            MpvInfoLines.hdrLine("pq", "bt.2020", 999.6)
        )
    }

    @Test
    fun `the video block reads the way the main player's rows read`() {
        val block = MpvInfoLines.videoBlock(
            report(
                videoBitrate = 8_163_000,
                pixelFormat = "yuv420p10",
                gamma = "pq",
                primaries = "bt.2020",
                sigPeak = 1000.0
            )
        )
        assertEquals(
            "Resolution: 1920×800 (1080p)\n" +
                "Codec: H.264\n" +
                "Bitrate: 8163 kbps\n" +
                "Decode: mediacodec\n" +
                "Pixel format: yuv420p10\n" +
                "HDR: pq • bt.2020 • peak 1000 nits",
            block
        )
    }

    @Test
    fun `a rate mpv has not measured is left out rather than shown as zero`() {
        // The shared bitrateLabel owns this rule - it returns null below 1 kbps,
        // which is why the panel can omit the row instead of printing it.
        val block = MpvInfoLines.videoBlock(report(videoBitrate = 0))
        assertTrue("no bitrate row without a bitrate: $block", !block.contains("Bitrate:"))
    }

    @Test
    fun `an audio-only file still gets a complete video block`() {
        // A radio stream has no video track at all, so every video property is
        // missing. The block still has to be readable text, not blank rows.
        val block = MpvInfoLines.videoBlock(
            report(videoFormat = null, width = 0, height = 0, hwdec = null)
        )
        assertEquals(
            "Resolution: —\n" +
                "Codec: —\n" +
                "Decode: software",
            block
        )
    }

    // ── AUDIO ─────────────────────────────────────────────────────────────

    @Test
    fun `channel counts become the layouts a viewer recognizes`() {
        assertEquals("mono", MpvInfoLines.channelLayout(1))
        assertEquals("stereo", MpvInfoLines.channelLayout(2))
        assertEquals("5.1", MpvInfoLines.channelLayout(6))
        assertEquals("6.1", MpvInfoLines.channelLayout(7))
        assertEquals("7.1", MpvInfoLines.channelLayout(8))
        // No layout of its own: reported by its number alone.
        assertNull(MpvInfoLines.channelLayout(3))
        assertNull(MpvInfoLines.channelLayout(null))
    }

    @Test
    fun `the channel line keeps the raw count beside the layout`() {
        // Both numbers matter: the layout is what a viewer compares, and the
        // count is what they quote when it sounds wrong.
        assertEquals("Channels: 6 (5.1)", MpvInfoLines.channelLine(6))
        assertEquals("Channels: 2 (stereo)", MpvInfoLines.channelLine(2))
        assertEquals("Channels: 3", MpvInfoLines.channelLine(3))
        assertEquals("Channels: —", MpvInfoLines.channelLine(null))
        assertEquals("Channels: —", MpvInfoLines.channelLine(0))
    }

    @Test
    fun `a sample rate mpv has not reported is a dash`() {
        assertEquals("Sample rate: 48000 Hz", MpvInfoLines.sampleRateLine(48_000))
        assertEquals("Sample rate: —", MpvInfoLines.sampleRateLine(null))
        assertEquals("Sample rate: —", MpvInfoLines.sampleRateLine(0))
    }

    @Test
    fun `the audio block reads the way the main player's rows read`() {
        val block = MpvInfoLines.audioBlock(
            report(audioCodec = "eac3", audioChannels = 6, audioBitrate = 448_000)
        )
        assertEquals(
            "Codec: EAC3\n" +
                "Channels: 6 (5.1)\n" +
                "Sample rate: 48000 Hz\n" +
                "Bitrate: 448 kbps",
            block
        )
    }

    @Test
    fun `a stream with no audio says so rather than going blank`() {
        val block = MpvInfoLines.audioBlock(
            report(audioCodec = null, audioChannels = null, audioSampleRate = null)
        )
        assertEquals(
            "Codec: —\n" +
                "Channels: —\n" +
                "Sample rate: —",
            block
        )
    }

    // ── SUBTITLE ──────────────────────────────────────────────────────────

    @Test
    fun `the subtitle row names the track, the sidecar, or none`() {
        assertEquals(
            "Subtitle: ENG • ASS",
            MpvInfoLines.subtitleLine(embedded = "ENG • ASS", sidecar = null)
        )
        assertEquals(
            "Subtitle: movie.en.srt (sidecar)",
            MpvInfoLines.subtitleLine(embedded = null, sidecar = "movie.en.srt")
        )
        assertEquals("Subtitle: None", MpvInfoLines.subtitleLine(null, null))
        assertEquals("Subtitle: None", MpvInfoLines.subtitleLine("   ", "  "))
    }

    @Test
    fun `a sidecar wins over the track it was added beside`() {
        // The sidecar is the file this app loaded on top of the container, so it
        // is what is on screen - naming the embedded track instead would name
        // the one the viewer is not looking at.
        assertEquals(
            "Subtitle: external.srt (sidecar)",
            MpvInfoLines.subtitleLine(embedded = "ENG • ASS", sidecar = "external.srt")
        )
    }

    // ── the file row ──────────────────────────────────────────────────────

    @Test
    fun `the file row is the URL's own filename, percent-decoded`() {
        assertEquals(
            "File: My Movie.2024.1080p.mkv",
            MpvInfoLines.fileRow(
                "https://cdn.example/My%20Movie.2024.1080p.mkv?token=abc",
                "TorBox"
            )
        )
    }

    @Test
    fun `the file row falls back to the source label, then to a dash`() {
        // A debrid link with no filename in it at all: the source's own label is
        // the honest answer, and only when there is none either is a dash.
        assertEquals(
            "File: TorBox",
            MpvInfoLines.fileRow("https://cdn.example/stream?id=42", "TorBox")
        )
        assertEquals("File: —", MpvInfoLines.fileRow("", null))
        assertEquals("File: —", MpvInfoLines.fileRow("https://cdn.example/stream", "  "))
    }
}
