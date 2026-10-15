package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring half of the ANDROID-Z fix (see [SoftwareAudioRules] for the crash
 * and the source behind it), pinned where a JVM test can see it.
 *
 * Both halves are a single call that compiles perfectly and does nothing if it
 * is deleted or moved: a sink override media3 never asks, and a listener that
 * runs after the track report. The rule itself is covered by
 * [SoftwareAudioRulesTest]; what cannot be reached from a unit test - the
 * `AudioSink` decorator, and the player listener that owns the session - is
 * read out of the source here, the same shape as the other contract tests in
 * this tree.
 */
class SoftwareAudioWiringContractTest {

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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

    private fun normalized(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")
            .replace(Regex("\\s+"), " ")

    private val sink by lazy {
        normalized(
            source("com/kennyb1201/kbstream/ui/player/LiveDownmixAudioSink.kt")
        )
    }

    private val activity by lazy {
        normalized(
            source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        )
    }

    @Test
    fun `the sink answers both of the software decoder's capability questions`() {
        // DecoderAudioRenderer has one method per question - sinkSupportsFormat
        // -> supportsFormat(Boolean), getSinkFormatSupport -> getFormatSupport
        // (int) - and FfmpegAudioRenderer answers "does the software decoder fit
        // this track" from either, so overriding only one leaves the track
        // decodable through the other.
        assertTrue(
            "the boolean question has to be answered from the sink override",
            sink.contains("override fun supportsFormat(format: Format): Boolean")
        )
        assertTrue(
            "and so does the graded one",
            sink.contains("override fun getFormatSupport(format: Format): Int")
        )
        assertTrue(
            "both go through the shared rule",
            sink.contains("SoftwareAudioRules.refusesSoftwareDecode(format)")
        )
        assertTrue(
            "and the graded answer is media3's own \"unsupported\"",
            sink.contains("return AudioSink.SINK_FORMAT_UNSUPPORTED")
        )
        assertTrue(
            "every other format is the inner sink's answer, unchanged",
            sink.contains("inner.supportsFormat(format)") &&
                sink.contains("return inner.getFormatSupport(format)")
        )
    }

    @Test
    fun `the refusal is logged once per width, not once per query`() {
        assertTrue(
            "media3 asks this per format and per attempt, so the line is gated",
            sink.contains("if (refusedWidth != format.channelCount)")
        )
        assertTrue(
            "and the gate is cleared with the session",
            sink.contains("refusedWidth = Int.MIN_VALUE")
        )
    }

    @Test
    fun `a session with no audio renderer hands off from the track report`() {
        val listener = activity.indexOf("override fun onTracksChanged(tracks: Tracks)")
        val escalation = activity.indexOf("SoftwareAudioRules.usesBackupEngine(tracks)")
        val errorPath = activity.indexOf("override fun onPlayerError(error: PlaybackException)")

        assertTrue("the track report is in the listener", listener >= 0)
        assertTrue(
            "the verdict is asked inside onTracksChanged: media3 reports no error " +
                "for a track no renderer claims, so there is no error path to hook",
            escalation > listener
        )
        assertTrue(
            "and it stays inside that handler, ahead of the error handler",
            escalation < errorPath
        )
        assertTrue(
            "the only engine that can decode the track is the one to move it to, " +
                "as a decoder failure",
            activity.substring(escalation, errorPath)
                .contains("handOffToMpv(MpvPlayerActivity.FALLBACK_REASON_DECODER)")
        )
    }
}
