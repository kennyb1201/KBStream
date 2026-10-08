package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bitrate the info screens show.
 *
 * [PlayerBitrateLabelTest] pins what the numbers mean; this pins that the
 * players are actually wired to them, because the failure being fixed was
 * exactly a silent one: the info panel had a "Bitrate" line, the line simply
 * never appeared for any source whose container declares no rate - which on
 * this box is most of them - and nothing failed.
 *
 * So two things must hold:
 *
 *  - ExoPlayer's info panel reads the measured rate when the declared one is
 *    absent, and something in this session actually measures it (the video
 *    track's fetched bytes, from the analytics listener).
 *  - libmpv's info readout names the rate too: its own `video-bitrate`
 *    property, which is what the engine knows about the file.
 */
class PlayerBitrateWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the main player's info panel shows a bitrate for every source`() {
        val native = source(NATIVE)
        assertTrue(
            "the info panel must read the shared bitrate label",
            native.contains("bitrateLabel(")
        )
        assertTrue(
            "and offer the measured rate when the container declares none",
            native.contains("measuredBps = measuredBitrateBps(")
        )
        // The bytes have to come from somewhere: the analytics listener is the
        // only place media3 reports them.
        assertTrue(
            "the video track's fetched bytes must be accumulated",
            native.contains("mediaLoadData.trackType != C.TRACK_TYPE_VIDEO")
        )
        assertTrue(
            "and the accumulator must be reset per stream",
            native.contains("measuredVideoBytes = 0L")
        )
        assertFalse(
            "the old declared-only line must be gone",
            native.contains("if (streamBitrate > 0) appendLine(\"Bitrate:")
        )
    }

    @Test
    fun `the mpv readout names the rate too`() {
        val mpv = source(MPV_VIEW)
        assertTrue(
            "mpv's own video-bitrate must be read",
            mpv.contains("getPropertyIntOrNull(\"video-bitrate\")")
        )
        assertTrue(
            "and rendered as kbps alongside the codec it already names",
            mpv.contains("kbps")
        )
    }

    private companion object {
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/"
        const val NATIVE = PLAYER + "NativePlayerActivity.kt"
        const val MPV_VIEW = PLAYER + "MpvPlayerView.kt"
    }
}
