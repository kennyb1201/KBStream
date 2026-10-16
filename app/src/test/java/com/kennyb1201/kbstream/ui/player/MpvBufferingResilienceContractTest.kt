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
 * The 2026-10-15 field follow-up is in here too: the engine resumed after only
 * ~2s of cushion (`cache-pause-wait` was 2), which is why the Onn 4K Plus log
 * shows seven buffering events whose every refill lasted ~2s before another
 * stall. The threshold is now 8s, the cache is prefilled before the first frame
 * and after a seek, FFmpeg's AVIO buffer on the demuxer path is enlarged, and
 * the log's `Audio device underrun detected` gets its own audio buffer.
 * `demuxer-hysteresis-secs` was the lowest-confidence knob of that spec and was
 * deliberately NOT taken - the note at its call site says why, and there is no
 * pin for it here so that taking it later is a deliberate act.
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
            view.contains("MpvBackend.setOptionString(\"cache-pause\", \"yes\")")
        )
        assertTrue(
            "the resume threshold is the field bug: 2s of cushion per resume IS the ~2s refill " +
                "the log shows before buffering is re-entered, so the old value has to be 8",
            view.contains("MpvBackend.setOptionString(\"cache-pause-wait\", \"8\")")
        )
        assertFalse(
            "and the 2 that caused the loop must not come back",
            view.contains("MpvBackend.setOptionString(\"cache-pause-wait\", \"2\")")
        )
        assertTrue(
            "the same cushion is prefilled before the first frame and after a seek, so startup " +
                "and seek-restart do not begin on a thin cache either",
            view.contains("MpvBackend.setOptionString(\"cache-pause-initial\", \"yes\")")
        )
    }

    @Test
    fun `the field log's audio underrun gets its own device buffer`() {
        assertTrue(
            "the same run recorded `Audio device underrun detected`; mpv #13189 is that exact " +
                "underrun and --audio-buffer=1 is what it names",
            view.contains("MpvBackend.setOptionString(\"audio-buffer\", \"1\")")
        )
    }

    @Test
    fun `the demuxer's AVIO read buffer is enlarged, not mpv's generic stream buffer`() {
        assertTrue(
            "2 MB of AVIO read buffer, so a bursty CDN refills the cache in fewer, larger reads",
            view.contains("MpvBackend.setOptionString(\"demuxer-lavf-buffersize\", \"2097152\")")
        )
        assertFalse(
            "and NOT stream-buffer-size: the primary open goes through lavf here, so the " +
                "generic knob would not cover the path this is for",
            view.contains("MpvBackend.setOptionString(\"stream-buffer-size\"")
        )
    }

    @Test
    fun `HLS adapts the variant to the network instead of pinning the heaviest`() {
        assertTrue(
            "hls-bitrate=no lets the variant be adapted; pinning the top one stalls when the " +
                "network cannot keep up",
            view.contains("MpvBackend.setOptionString(\"hls-bitrate\", \"no\")")
        )
    }

    @Test
    fun `the primary http(s) open reconnects, which is the permanent-spinner fix`() {
        assertTrue(
            "stream-lavf-o is the option covering the primary open; without it one dropped " +
                "connection or failed seek-reopen stalls network playback forever",
            flat.contains(
                "MpvBackend.setOptionString( \"stream-lavf-o\", " +
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
                "MpvBackend.setOptionString( \"demuxer-lavf-o\", " +
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
                .substringAfter("MpvBackend.setOptionString( \"$option\", \"")
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
            view.contains("MpvBackend.setOptionString(\"framedrop\", \"vo\")")
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
            view.contains("MpvBackend.setOptionString(\"network-timeout\", \"30\")")
        )
        assertTrue(
            "and the low-latency branch still disables the cache",
            view.contains("MpvBackend.setOptionString(\"cache\", if (lowLatency) \"no\" else \"yes\")")
        )
        assertFalse(
            "no reconnect option may be gated behind the buffer mode: the spinner does not care " +
                "which profile is selected",
            view.contains("if (!lowLatency) MpvBackend.setOptionString(\"stream-lavf-o\"")
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
