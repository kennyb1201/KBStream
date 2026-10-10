package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.addon.Stream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-playback probe must test the session's initial source first.
 *
 * The bug this pins: "Play manually" taps a source, but the player opened a
 * different one. The probe walked the full ranked list with no knowledge of the
 * explicit choice, so the first live candidate whose URL differed from the
 * session's current URL replaced it via `adoptInitialSource` ("probe picked a
 * different head source"). Pinning the initial source to the head of the probe
 * order makes the probe test the choice first, so a live pick is kept and the
 * override can only fire when the pick itself was dead.
 *
 * [probeCandidates] is the pure rule, so it is asserted directly. The two
 * funnels that consume it live inside Activities that cannot be built in a JVM
 * test, so their wiring is pinned from the source - the same pattern the other
 * source-contract tests use.
 */
class ProbeOrderContractTest {

    private fun stream(url: String, audio: String? = null) =
        Stream(url = url, audioUrl = audio)

    // ── the pure rule ──────────────────────────────────────────────────────

    @Test
    fun `the initial source is moved to the head with its addon`() {
        val sources = listOf(stream("A"), stream("B"), stream("C"))
        val addons = listOf("a", "b", "c")

        val (candidates, ordered) = probeCandidates(sources, addons, "B", null, emptyMap())

        assertEquals(listOf("B", "A", "C"), candidates.map { it.url })
        assertEquals("the addons follow their sources", listOf("b", "a", "c"), ordered)
    }

    @Test
    fun `an initial source already at the head keeps the rank order`() {
        val sources = listOf(stream("A"), stream("B"))
        val addons = listOf("a", "b")

        val (candidates, ordered) = probeCandidates(sources, addons, "A", null, emptyMap())

        assertEquals(listOf("A", "B"), candidates.map { it.url })
        assertEquals(listOf("a", "b"), ordered)
    }

    @Test
    fun `a blank initial url leaves rank order unchanged`() {
        val sources = listOf(stream("A"), stream("B"))
        val addons = listOf("a", "b")

        val (candidates, ordered) = probeCandidates(sources, addons, "   ", null, emptyMap())

        assertEquals(sources, candidates)
        assertEquals(addons, ordered)
    }

    @Test
    fun `an initial url absent from the list is synthesized with a null addon`() {
        // A played-link cache URL that a fresh resolve did not return: the
        // probe must still be able to test it, carrying the launch headers.
        val sources = listOf(stream("A"))
        val addons = listOf("a")
        val headers = mapOf("Referer" to "https://host/")

        val (candidates, ordered) =
            probeCandidates(sources, addons, "Z", "z-audio", headers)

        assertEquals(listOf("Z", "A"), candidates.map { it.url })
        assertEquals("addon unknown -> null, so nothing is demoted", listOf(null, "a"), ordered)
        assertEquals("z-audio", candidates.first().audioUrl)
        assertEquals(
            "the launch headers ride the synthesized candidate",
            headers,
            candidates.first().requestHeaders
        )
    }

    @Test
    fun `the candidates and addons stay index-aligned`() {
        val sources = listOf(stream("A"), stream("B"), stream("C"), stream("D"))
        val addons = listOf("a", "b", "c", "d")

        val (candidates, ordered) = probeCandidates(sources, addons, "D", null, emptyMap())

        assertEquals(candidates.size, ordered.size)
        assertEquals(listOf("d", "a", "b", "c"), ordered)
    }

    // ── the wiring in each engine's probe funnel ────────────────────────────

    private val nativeSource: String by lazy { readSource(NATIVE) }
    private val mpvSource: String by lazy { readSource(MPV) }

    @Test
    fun `each activity defines the probe order helper`() {
        val expected = "private fun probeOrder(): Pair<List<Stream>, List<String?>>"
        assertTrue("NativePlayerActivity.$expected", nativeSource.contains(expected))
        assertTrue("MpvPlayerActivity.$expected", mpvSource.contains(expected))
    }

    @Test
    fun `the native probe funnel probes the pinned order`() {
        assertFunnelProbesPinnedOrder(nativeSource, "private fun probeThenCreatePlayer()")
    }

    @Test
    fun `the mpv probe funnel probes the pinned order`() {
        assertFunnelProbesPinnedOrder(mpvSource, "private fun probeThenLoad(load: () -> Unit)")
    }

    private fun assertFunnelProbesPinnedOrder(source: String, signature: String) {
        val body = funnel(source, signature)
        assertTrue("must build the pinned order", body.contains("val (candidates, addons) = probeOrder()"))
        assertTrue(
            "must hand that order to the probe",
            body.contains("pickLiveSource(candidates, addons)")
        )
        assertTrue(
            "a kept initial source must be logged",
            body.contains("probe: initial source live, keeping:")
        )
        assertTrue(
            "an override still only happens on a different URL",
            body.contains("pick.url != currentUrl")
        )
        assertTrue(
            "and it still warns",
            body.contains("probe picked a different head source")
        )
    }

    /** The funnel body, from its signature up to the `probeOrder` helper below it. */
    private fun funnel(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val end = source.indexOf("private fun probeOrder", start)
        assertTrue("probeOrder not found after $signature", end > start)
        return source.substring(start, end)
    }

    private fun readSource(relative: String): String {
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
        const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
