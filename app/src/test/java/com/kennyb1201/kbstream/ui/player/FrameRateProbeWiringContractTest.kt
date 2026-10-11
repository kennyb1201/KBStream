package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-playback frame-rate probe only helps where it is actually launched,
 * and the wiring is the part source review cannot see at runtime: a probe that
 * is written but never called, or called on the wrong engine, looks exactly like
 * a probe that found nothing. So the call sites are pinned here by reading the
 * sources.
 *
 * Also pinned: the probe reads the container's own declared rate (never samples
 * timestamps on a truncated head, which hangs), refuses a null result through
 * the matcher's own `normalise`, and never runs for a live channel where the
 * bytes never end.
 */
class FrameRateProbeWiringContractTest {

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `the probe reads the container's declared rate, not timestamp samples`() {
        val probe = source(PROBE)
        assertTrue(
            "the declared video rate is the whole point",
            probe.contains("MediaFormat.KEY_FRAME_RATE")
        )
        assertTrue(
            "the extractor must be released on every path",
            probe.contains("extractor.release()")
        )
        assertTrue(
            "the head is a ranged fetch",
            probe.contains("bytes=\$from-\$to")
        )
    }

    @Test
    fun `probe junk is refused through the matcher's own normalise`() {
        assertTrue(
            "0, NaN and absurd rates must be refused exactly like player-reported ones",
            source(PROBE).contains("FrameRateMatch.normalise(")
        )
    }

    @Test
    fun `the native player probes alongside player prep and marks the source`() {
        val native = source(NATIVE)
        assertTrue(
            "the probe must be launched from the native player's setup",
            native.contains("FrameRateProbe.probe(")
        )
        assertTrue(
            "the probed rate is labelled so the diagnostics row can name its source",
            native.contains("FrameRateSource.PROBE")
        )
        assertTrue(
            "a live channel is never probed: its bytes never end",
            native.contains("!isLiveChannel && FrameRateProbe.isProbeable(currentUrl)")
        )
    }

    @Test
    fun `the mpv player probes at session start with the player report as fallback`() {
        val mpv = source(MPV)
        assertTrue(
            "the probe launch is a named step at session start",
            squash(mpv).contains("fun loadStream() { startFrameRateProbe()")
        )
        assertTrue(
            "the probe must actually be launched",
            mpv.contains("FrameRateProbe.probe(")
        )
        assertTrue(
            "the probed rate is labelled for the diagnostics row",
            mpv.contains("FrameRateSource.PROBE")
        )
        assertTrue(
            "mpv's own frame-rate report stays as the fallback/confirmation",
            mpv.contains("onVideoFrameRateChanged = { fps ->")
        )
    }

    @Test
    fun `every null path of the probe names itself in the log`() {
        // The field capture this pins (2026-10-11) had three playbacks whose
        // PLAYER_AFR lines contained only the panel report: the probe returned
        // null five different ways, silently, so the log could not say which.
        val probe = source(PROBE)
        assertTrue(
            "a URL that cannot be probed must say so",
            probe.contains("probe skipped: URL not probeable (host=\${hostOf(url)})")
        )
        assertTrue(
            "a probe that read nothing must be distinguishable from one never launched",
            probe.contains("probe: no reading (timeout or download failed, host=\${hostOf(url)})")
        )
        assertTrue(
            "a refused reading must name the value it refused",
            probe.contains("probe: reading \$measured fps unusable (host=\${hostOf(url)})")
        )
        assertTrue(
            "a head that is not a 206 must report the status it got",
            probe.contains("probe head request: HTTP \${response.code}")
        )
        assertTrue(
            "an empty head body must say so",
            probe.contains("probe head: empty body")
        )
        assertTrue(
            "a video track without a rate key is the mislabelled-container case",
            probe.contains("probe: video track has no frame-rate key (mime=\$mime)")
        )
        assertTrue(
            "no video track at all is its own answer",
            probe.contains("probe: head has no video track")
        )
    }

    @Test
    fun `the probe logs the host, never the signed url`() {
        val probe = source(PROBE)
        assertTrue(
            "the host helper is the only piece of the URL a log line may carry",
            probe.contains("private fun hostOf(url: String): String")
        )
        assertTrue(
            "a URL that will not parse still has to produce a log line",
            probe.contains("?: \"?\"")
        )
        listOf("\$url", "\${url}").forEach { interpolation ->
            assertTrue(
                "a signed debrid URL must never be interpolated into a log line: $interpolation",
                !probe.contains(interpolation)
            )
        }
    }

    @Test
    fun `a probe that was never launched says so, on the AFR tag`() {
        val native = source(NATIVE)
        assertTrue(
            "the else leg is what makes 'never launched' visible at all",
            native.contains("probe not launched: matcher=\${matcher != null} ")
        )
        assertTrue(
            "it belongs to the same tag as the probe's own lines",
            native.contains("\"PLAYER_AFR\"")
        )
    }

    private companion object {
        const val PROBE = "com/kennyb1201/kbstream/ui/player/FrameRateProbe.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
