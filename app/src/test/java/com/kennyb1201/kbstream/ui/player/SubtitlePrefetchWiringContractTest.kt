package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prefetching the next episode's subtitle only earns its keep if it happens
 * where the viewer is NOT waiting, and only pays off if the next episode
 * actually looks for it before reaching for the network.
 *
 * Three things have to hold, in both engines, and none of them is visible to a
 * compile:
 *
 *  1. the fetch starts from the end panels - the credits, while episode N is
 *     still playing - rather than at episode N+1's playback start;
 *  2. it only runs when this session's own auto-fetch ran at all, which is what
 *     proves the viewer wants fetched subtitles for this show and that this
 *     episode needed one. Without that gate every series with embedded
 *     subtitles would fetch and cache a subtitle per episode for nothing;
 *  3. the auto-fetch consults the prefetch BEFORE searching, and leaves the
 *     coroutine on a hit, so there is no search and no download left to see.
 *
 * The store's own behavior is unit tested in `SubtitlePrefetchTest`.
 */
class SubtitlePrefetchWiringContractTest {

    private fun readSource(path: String): String {
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val nativeEndPanels by lazy {
        functionBody(NATIVE, "private fun showEndPanels() {")
    }
    private val mpvEndPanels by lazy {
        functionBody(MPV, "private fun showEndPanels() {")
    }
    private val nativePrefetch by lazy {
        functionBody(NATIVE, "private fun prefetchNextEpisodeSubtitle(nextSeason: Int, nextEpisode: Int) {")
    }
    private val mpvPrefetch by lazy {
        functionBody(MPV, "private fun prefetchNextEpisodeSubtitle(nextSeason: Int, nextEpisode: Int) {")
    }
    private val nativeAutoFetch by lazy {
        functionBody(NATIVE, "private fun maybeAutoFetchSubtitle() {")
    }
    private val mpvAutoFetch by lazy {
        functionBody(MPV, "private fun maybeAutoFetchSubtitle() {")
    }

    private val prefetchCall = "prefetchNextEpisodeSubtitle(target.first, target.second)"

    @Test
    fun `both engines fetch the next episode's subtitle while the end panels are up`() {
        listOf("native" to nativeEndPanels, "mpv" to mpvEndPanels).forEach { (engine, body) ->
            assertTrue(
                "$engine must prefetch from the end panels",
                body.contains(prefetchCall)
            )
            val target = body.indexOf("val target")
            val kick = body.indexOf(prefetchCall)
            assertTrue(
                "$engine must prefetch only once the next episode is known",
                target in 0 until kick
            )
        }
    }

    @Test
    fun `both engines prefetch only when this session's auto-fetch ran at all`() {
        listOf("native" to nativePrefetch, "mpv" to mpvPrefetch).forEach { (engine, body) ->
            assertTrue(
                "$engine must gate the prefetch on the auto-fetch having run",
                body.contains("!autoSubtitleFetchTried")
            )
            assertTrue(
                "$engine must prefetch at most once per session",
                body.contains("if (subtitlePrefetchStarted")
            )
            assertTrue(
                "$engine must set the once-per-session latch before launching",
                body.indexOf("subtitlePrefetchStarted = true") < body.indexOf("SubtitlePrefetch.prefetchFor(")
            )
            // It runs off the session's own scope, next to the fetch it feeds.
            assertTrue(
                "$engine must run the prefetch on the session's coroutine scope",
                body.contains("lifecycleScope.launch")
            )
        }
    }

    @Test
    fun `both engines use a prefetched file before they search`() {
        listOf("native" to nativeAutoFetch, "mpv" to mpvAutoFetch).forEach { (engine, body) ->
            val cacheLookup = body.indexOf("SubtitlePrefetch.get(")
            val search = body.indexOf("SubtitleSearchHelper.search(")
            assertTrue("$engine must consult the prefetch", cacheLookup >= 0)
            assertTrue(
                "$engine must consult the prefetch before searching",
                cacheLookup in 0 until search
            )
            // And a hit must END the coroutine: falling through would run the
            // search anyway, which is the round-trip this exists to remove.
            val hit = body.substring(cacheLookup, search)
            assertTrue("$engine must leave on a hit", hit.contains("return@launch"))
        }
        // Each engine attaches the hit the way its own download path does.
        assertTrue(
            "native must attach the prefetched uri",
            nativeAutoFetch.contains("attachExternalSubtitle(hit.uri)")
        )
        assertTrue(
            "mpv must apply the prefetched uri",
            mpvAutoFetch.contains("applyDownloadedSubtitle(hit.fileName, hit.uri)")
        )
    }

    @Test
    fun `the prefetch validates the download before it caches it`() {
        val source = readSource(PREFETCH)
        // A 200 that parses to nothing is not a subtitle (the same rule every
        // other download route applies), and must not be cached as one.
        assertTrue(
            "the prefetch must validate the body",
            source.contains("SubtitleSearchHelper.isUsableSubtitleBody(")
        )
        val check = source.indexOf("SubtitleSearchHelper.isUsableSubtitleBody(")
        val store = source.indexOf("fileName = pick.fileName")
        assertTrue(
            "validation must precede the cache write",
            check in 0 until store
        )
        // The file it caches is written by the shared helper, so it lands on the
        // same deterministic path the auto-fetch's own download would use.
        assertTrue(
            "the prefetch must cache through the shared writer",
            source.contains("SubtitleSearchHelper.toCacheUri(")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val PREFETCH =
            "com/kennyb1201/kbstream/ui/player/SubtitlePrefetch.kt"
    }
}
