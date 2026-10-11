package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's buffering recipe, pinned where it can be seen.
 *
 * Without it the engine had no recovery path at all: no reconnect option was
 * set, so one dropped connection (or a failed seek-reopen) stalled network
 * playback permanently - the spinner that never ends. It also pinned no HLS
 * adaptation and never paused to refill a thin cache, so a marginal network
 * stuttered instead of riding through.
 *
 * Honest boundary: no option makes a source faster than the bitrate it needs.
 * What these change is transient stalls. `reconnect_max_retries` with the delay
 * caps is what keeps recovery BOUNDED, so a truly dead source still fails -
 * and still reports why through the mpv log observer - rather than retrying
 * forever, and a legitimate VOD EOF is untouched (this is reconnect-on-error,
 * not reconnect-at-eof).
 *
 * There is no device in CI and a libmpv Activity cannot be driven in a JVM
 * test, so the options that implement this are read out of the source the way
 * this tree's other player contracts are.
 */
class MpvBufferingResilienceContractTest {

    private val view: String by lazy { read(MPV_VIEW) }

    /**
     * The option strings are written as adjacent string literals across lines,
     * so a raw `contains` of the full value would never match. Joining
     * `" + "` back into the literal it is, then collapsing every run of
     * whitespace to one space so a call split across lines reads as one line,
     * keeps the assertions exact - the whole value has to be right, not just a
     * prefix of it.
     */
    private val flat: String by lazy {
        view.replace(Regex("\"\\s*\\+\\s*\""), "")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun `a thin cache pauses to refill instead of stuttering through it`() {
        assertTrue(
            "cache-pause makes a marginal network ride through a thin cache as one brief pause " +
                "instead of a stutter loop",
            view.contains("MPVLib.setOptionString(\"cache-pause\", \"yes\")")
        )
        assertTrue(
            "and the pause is bounded to a couple of seconds",
            view.contains("MPVLib.setOptionString(\"cache-pause-wait\", \"2\")")
        )
    }

    @Test
    fun `HLS adapts the variant to the network instead of pinning the heaviest`() {
        assertTrue(
            "hls-bitrate=no lets the variant be adapted; pinning the top one stalls when the " +
                "network cannot keep up",
            view.contains("MPVLib.setOptionString(\"hls-bitrate\", \"no\")")
        )
    }

    @Test
    fun `the primary http(s) open reconnects, which is the permanent-spinner fix`() {
        assertTrue(
            "stream-lavf-o is the option covering the primary open; without it one dropped " +
                "connection or failed seek-reopen stalls network playback forever",
            flat.contains(
                "MPVLib.setOptionString( \"stream-lavf-o\", " +
                    "\"reconnect=1,reconnect_on_network_error=1," +
                    "reconnect_on_http_error=5xx,reconnect_streamed=1," +
                    "reconnect_delay_max=5,reconnect_max_retries=5," +
                    "reconnect_delay_total_max=20\" )"
            )
        )
    }

    @Test
    fun `demuxer-internal opens reconnect too, for HLS and DASH segments`() {
        assertTrue(
            "demuxer-lavf-o covers the segment opens the demuxer makes on its own",
            flat.contains(
                "MPVLib.setOptionString( \"demuxer-lavf-o\", " +
                    "\"http_persistent=0,reconnect=1,reconnect_on_network_error=1," +
                    "reconnect_streamed=1,reconnect_delay_max=5," +
                    "reconnect_max_retries=5,reconnect_delay_total_max=20\" )"
            )
        )
    }

    @Test
    fun `recovery from a dead source stays bounded rather than retrying forever`() {
        listOf("stream-lavf-o", "demuxer-lavf-o").forEach { option ->
            val value = flat
                .substringAfter("MPVLib.setOptionString( \"$option\", \"")
                .substringBefore("\")")
            assertTrue("$option must cap the retry count, or a dead URL spins forever",
                value.contains("reconnect_max_retries=5"))
            assertTrue("$option must cap a single retry delay",
                value.contains("reconnect_delay_max=5"))
            assertTrue("$option must cap the total reconnect budget",
                value.contains("reconnect_delay_total_max=20"))
        }
    }

    @Test
    fun `late frames are dropped at the video output rather than building jitter`() {
        assertTrue(
            "framedrop=vo drops only the frames the VO was already going to miss - never " +
                "pre-emptively",
            view.contains("MPVLib.setOptionString(\"framedrop\", \"vo\")")
        )
    }

    @Test
    fun `the cache sizes, timeout and low-latency path are unchanged`() {
        assertTrue("64 MB forward cache unchanged", view.contains("const val CACHE_MB = 64"))
        assertTrue(
            "the low-latency cap is unchanged",
            view.contains("const val LOW_LATENCY_MAX_BYTES = 8 * 1024 * 1024")
        )
        assertTrue(
            "network-timeout is unchanged",
            view.contains("MPVLib.setOptionString(\"network-timeout\", \"30\")")
        )
        assertTrue(
            "and the low-latency branch still disables the cache",
            view.contains("MPVLib.setOptionString(\"cache\", if (lowLatency) \"no\" else \"yes\")")
        )
        assertFalse(
            "no reconnect option may be gated behind the buffer mode: the spinner does not care " +
                "which profile is selected",
            view.contains("if (!lowLatency) MPVLib.setOptionString(\"stream-lavf-o\"")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
