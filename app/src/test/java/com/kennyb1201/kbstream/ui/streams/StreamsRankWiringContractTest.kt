package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the three new list rules sit in the resolve path, and what they are fed.
 *
 * Each of them is invisible when it is wired in the wrong PLACE while every file
 * still compiles: a collapse applied before the addon reorder keeps the wrong
 * addon's row, a rank call that forgets the DV verdict re-promotes a copy this
 * box cannot show, and a runtime that never leaves the screen leaves the density
 * scoring dead - all of which look exactly like "the ranker did not change".
 *
 * Read from the source, the way this repo pins resolver wiring (see
 * DetailPlayProgressThemeContractTest): [StreamsViewModel] needs an Application
 * and a set of installed addons to run, so the order of the calls inside `fetch`
 * cannot be asserted from a JVM test any other way. The pure halves are covered
 * by [com.kennyb1201.kbstream.domain.streamengine.StreamDedupTest],
 * [com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreferenceTest] and
 * [com.kennyb1201.kbstream.domain.streamengine.StreamRankerTest].
 */
class StreamsRankWiringContractTest {

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

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt") }

    /**
     * The body of the member starting at [signature], up to the next member: a
     * line indented by exactly four spaces.
     *
     * The body is taken from the signature's own opening brace rather than from
     * the end of the signature text: these members' parameter lists span lines,
     * and the closing paren of one sits at four spaces - it would otherwise read
     * as the start of the next member and hand back an empty body.
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val bodyStart = src.indexOf('{', start + signature.length)
        assertTrue("source missing body: $signature", bodyStart >= 0)
        val rest = src.substring(bodyStart + 1)
        val end = Regex("""\n {4}\S""").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the collapse runs on the reordered list, and before the badges`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")

        val reorder = fetch.indexOf("SourceAddonPreference.ordered(")
        val collapse = fetch.indexOf("StreamDedup.collapse(preferredStreams)")
        val badges = fetch.indexOf("StreamBadgeEngine.apply(")

        assertTrue("the addon reorder must still be applied", reorder >= 0)
        assertTrue("the collapse must still be applied", collapse >= 0)
        assertTrue("the badges must still be attached", badges >= 0)
        assertTrue(
            "the collapse must run AFTER the reorder, so the surviving row is " +
                "the one the viewer would have picked from",
            collapse > reorder
        )
        assertTrue(
            "and BEFORE the badges, so the surviving row keeps its badges",
            badges > collapse
        )
    }

    @Test
    fun `every rank call is given the DV verdict and the runtime`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        val calls = Regex("""StreamRanker\.rank\(""").findAll(fetch).toList()

        assertEquals("the merged list and each addon's own tab list", 2, calls.size)
        calls.forEach { call ->
            val args = fetch.substring(call.range.first, minOf(fetch.length, call.range.first + 400))
            assertTrue(
                "a rank call that keeps the default DV bonus promotes a copy on " +
                    "this box's own hardware: $args",
                args.contains("dolbyVisionUseful")
            )
            assertTrue(
                "and one that keeps the default runtime scores bulk where the " +
                    "request knows the length: $args",
                args.contains("runtimeMinutes")
            )
        }
    }

    @Test
    fun `the diagnostics line is explained with the same parameters as the order`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        assertTrue(
            "the report must not print a score the order was not built with",
            fetch.contains("rankReportLines(") &&
                fetch.contains("runtimeMinutes = runtimeMinutes")
        )

        val report = functionBody(viewModel, "private fun rankReportLines(")
        val explain = report.substringAfter("StreamRanker.explain(", "")
        assertTrue(
            "explain must read the DV verdict and the runtime too",
            explain.contains("dolbyVisionUseful") && explain.contains("runtimeMinutes")
        )
    }

    @Test
    fun `the DV verdict is the box probe and the viewer's own setting`() {
        assertTrue(
            "a DV decoder on the box is not enough: the viewer can say their " +
                "display has none (Strip All)",
            viewModel.contains("DolbyVisionCapability.supportsNativeDolbyVision") &&
                viewModel.contains("AppPreferences.getDvCompatMode(application) !=") &&
                viewModel.contains("AppPreferences.DV_COMPAT_ALL")
        )
    }

    @Test
    fun `the runtime reaches the resolve from the screen`() {
        assertTrue(
            "load takes the runtime and hands it to the fetch",
            viewModel.contains("runtimeMinutes: Int? = null") &&
                viewModel.contains("runtimeMinutes = runtimeMinutes")
        )

        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "the StreamsTarget already carries the runtime; the background " +
                "resolve (auto-play) has to pass it on",
            activity.contains("pending.target.runtimeMinutes") &&
                activity.contains("current.target.runtimeMinutes")
        )
    }
}
