package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pre-warm is triggered from, and what it is NOT allowed to become.
 *
 * The behavioural half is [PoTokenWarmTest]; this pins the wiring that no unit
 * test can reach: a Compose screen's effect, the app-level memory hook, and the
 * launch-time deferral. Those are exactly the places a later refactor drops
 * silently - the pre-warm simply stops happening, every resolve goes back to the
 * cold WebView that ate the whole mint budget, and nothing fails.
 *
 * It also pins the two rules the spec froze: the budgets are unchanged (this
 * moves work out of the window, it does not widen it), and there is no timer -
 * warm-up happens on triggers, never on a schedule.
 */
class PoTokenWarmWiringContractTest {

    private val provider by lazy { read(YOUTUBE + "WebViewPoTokenProvider.kt") }

    private val extractor by lazy { read(YOUTUBE + "InnerTubeExtractor.kt") }

    private val diagnostics by lazy { read("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt") }

    private val application by lazy { read("com/kennyb1201/kbstream/MainApplication.kt") }

    private val activity by lazy { read("com/kennyb1201/kbstream/MainActivity.kt") }

    @Test
    fun `a detail screen with a trailer pre-warms it, on both form factors`() {
        val tv = read("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
        val phone = read("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")

        assertTrue(
            "the TV detail screen must warm the trailer it is offering",
            tv.contains("LaunchedEffect(trailerVideo?.key)") &&
                tv.contains("InnerTubeExtractor.prewarmPoTokens(key)")
        )
        assertTrue(
            "and so must the phone's",
            phone.contains("LaunchedEffect(trailerKey)") &&
                phone.contains("InnerTubeExtractor.prewarmPoTokens(key)")
        )
    }

    @Test
    fun `the hero warms its token before it resolves the trailer`() {
        val home = read("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
        val effect = slice(home, "LaunchedEffect(heroTrailerKey.value)", "var heroTrailerReady")

        val warm = effect.indexOf("InnerTubeExtractor.prewarmPoTokens(key)")
        val resolve = effect.indexOf("TrailerPlayerLauncher.resolvePlayableUrl(")
        assertTrue("the hero's existing pre-warm is where this belongs", warm >= 0)
        assertTrue("the resolve must still be there", resolve >= 0)
        assertTrue(
            "tokens first: a resolve that starts before the page is loaded pays for the " +
                "cold WebView the pre-warm exists to remove",
            warm < resolve
        )
    }

    @Test
    fun `app start defers one warm-up past the first frame`() {
        val warm = activity.indexOf("InnerTubeExtractor.prewarmPoTokens()")
        val delay = activity.indexOf("delay(PO_TOKEN_WARM_DELAY_MS)")
        assertTrue("a cold launch must warm too", warm >= 0)
        assertTrue("and it must wait for the launch to settle", delay >= 0)
        assertTrue(
            "the delay comes first: an embed page loading during Home's first render is " +
                "exactly the competition the deferral is for",
            delay < warm
        )
        assertTrue(
            "inside an effect, so nothing runs before the first composition",
            slice(activity, "LaunchedEffect(Unit) {", "InnerTubeExtractor.prewarmPoTokens()")
                .contains("delay(PO_TOKEN_WARM_DELAY_MS)")
        )
    }

    @Test
    fun `critical memory pressure drops the warm page, and only the page`() {
        assertTrue(
            "the app-level trim hook must release the warm WebView",
            application.contains("PoTokens.releaseWarm()")
        )
        assertTrue(
            "at critical pressure - the same branch that gives back the browsing caches",
            slice(
                application,
                "if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)",
                "}\n        }\n    }"
            ).contains("PoTokens.releaseWarm()")
        )
        assertFalse(
            "the cached tokens are strings, not renderers - pressure must not throw away " +
                "the thing that makes the next resolve free",
            slice(provider, "override fun releaseWarm()", "override fun invalidate()")
                .contains("invalidate()")
        )
    }

    @Test
    fun `the kill switch still owns the whole layer, pre-warm included`() {
        assertTrue(
            "no watch-config fetch and no WebView when minting is off",
            extractor.contains("if (!provider.isEnabled()) return")
        )
        assertTrue(
            "and the provider re-checks it itself, so a pre-warm racing a flip mints nothing",
            slice(provider, "override suspend fun warmUp(", "mintMutex.withLock")
                .contains("if (!enabled()) return")
        )
        assertTrue(
            "the switch is still the youtube preference, read per call",
            provider.contains("enabled = { AppPreferences.getYouTubePoTokenEnabled(app) }")
        )
    }

    @Test
    fun `the pre-warm spends no budget of its own`() {
        val warmUp = slice(provider, "override suspend fun warmUp(", "override fun releaseWarm()")

        assertTrue(
            "a warm mint runs under the SAME provider timeout as a resolve's",
            warmUp.contains("withTimeout(mintTimeoutMs) { warming.warm(visitorData, videoId) }")
        )
        assertFalse(
            "no second, larger window may appear for the warm path",
            warmUp.contains("20_000") || warmUp.contains("15_000")
        )
        assertEquals(
            "and the ladder the resolve spends is untouched",
            6_000L,
            InnerTubeExtractor.GVS_TOKEN_BUDGET_MS
        )
    }

    @Test
    fun `one warm page, reused, and no timer`() {
        assertEquals(
            "exactly one warm page field: a second one is a second WebView",
            1,
            Regex("""private var warmPage: WarmPage\?""").findAll(provider).count()
        )
        assertTrue(
            "a repeated trigger re-navigates the existing page",
            provider.contains("val page = warmPage ?: (createPage() ?: return@withContext null)")
        )
        assertFalse(
            "warming is trigger-driven, never periodic",
            provider.contains("Timer(") || provider.contains("scheduleAtFixedRate")
        )
        assertTrue(
            "and destroying it is posted to the main thread, because pressure can call in " +
                "from anywhere",
            provider.contains("mainHandler.post { destroy(page) }")
        )
    }

    @Test
    fun `the report carries the warm state without any new plumbing`() {
        assertTrue(
            "Diagnostics prints the trace's lines, which is how pot.warm reaches a capture",
            diagnostics.contains("PoTokenTrace.lines().forEach")
        )
        assertTrue(
            "the line itself is always printable, so an unwarmed device says cold",
            read(YOUTUBE + "PoTokenTrace.kt")
                .contains("add(warmLine ?: \"pot.warm: state=\$WARM_COLD\")")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
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
        const val YOUTUBE = "com/kennyb1201/kbstream/data/youtube/"
    }
}
