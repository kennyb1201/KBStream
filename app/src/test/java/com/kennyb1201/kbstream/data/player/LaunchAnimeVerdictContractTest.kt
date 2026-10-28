package com.kennyb1201.kbstream.data.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Play anime in MPV" has to hold on EVERY launch, not on the launches that
 * happen to be a minute behind a streams fetch (field 2026-10-28: anime opens in
 * MPV sometimes).
 *
 * The verdict used to live in one slot that only StreamsViewModel's fetch filled
 * and the player route took one-shot, so it was missing whenever the picker
 * reused a list it already had, whenever the viewer spent more than a minute on
 * the source list, whenever they backed out and picked a second source, and
 * whenever a fetch for another title landed in between. The engine now files
 * verdicts by title, never consumes them on read, and resolves one itself when
 * the session has none - these pins keep all three properties.
 *
 * Read as source text, the way this tree's wiring contracts read: the decision
 * is a Context-and-prefs path with no unit-testable seam that does not want a
 * device.
 */
class LaunchAnimeVerdictContractTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + path)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private val engine: String
        get() = source("com/kennyb1201/kbstream/data/player/PlayerEngine.kt")

    private val launchSite: String
        get() = source("com/kennyb1201/kbstream/MainActivity.kt")

    @Test
    fun `the launch asks the engine for a decision, not for whoever fetched last`() {
        val site = launchSite
        val call = site.indexOf("PlayerEngine.prefersMpvForLaunch(")
        assertTrue(
            "the player route must take the launch's own title to the engine",
            call >= 0
        )
        val callEnd = site.indexOf(')', call)
        val arguments = site.substring(call, minOf(callEnd, call + 400))
        assertTrue(
            "the title id is what the verdict is filed under",
            arguments.contains("parentId = current.parentId")
        )
        assertTrue(
            "and its media type is what the anime rule reads (an `anime` catalog " +
                "type is anime by definition, with no lookup)",
            arguments.contains("parentType = current.parentType")
        )
    }

    @Test
    fun `a DRM launch never asks`() {
        val site = launchSite
        val guard = site.indexOf("current.drmLicenseUrl == null &&")
        val call = site.indexOf("PlayerEngine.prefersMpvForLaunch(")
        assertTrue(
            "the DRM guard must sit on the MPV branch itself: the engine now " +
                "resolves a verdict when the cache has none, so clearing the cache " +
                "is no longer enough to keep a DRM stream in ExoPlayer",
            guard in 0 until call
        )
    }

    @Test
    fun `verdicts are filed by title`() {
        val text = engine
        assertTrue(
            "one slot per session is what let one title's fetch erase another's verdict",
            text.contains("private val launchAnime = ConcurrentHashMap<String, LaunchAnime>()")
        )
        assertTrue(
            "a missing verdict is resolved at the launch instead of defaulting to ExoPlayer",
            text.contains("?: resolveLaunchAnime(")
        )
        assertTrue(
            "and the resolution is bounded: it sits between the tap and the first frame",
            text.contains("withTimeoutOrNull(LAUNCH_ANIME_BUDGET_MS)")
        )
    }

    @Test
    fun `reading a verdict does not consume it`() {
        val text = engine
        assertFalse(
            "a read must not take the verdict: a second launch of the same title " +
                "(another source from the same picker, a rotate, a relaunch) read " +
                "nothing and opened ExoPlayer",
            text.contains("getAndSet")
        )
        val read = text.indexOf("private fun cachedLaunchAnime(titleId: String): Boolean?")
        assertTrue("the read path must exist", read >= 0)
        val body = text.substring(read, minOf(text.length, read + 500))
        assertTrue(
            "it may only DROP an aged-out entry, never a fresh one",
            body.contains("launchAnime.remove(titleId, verdict)")
        )
    }

    @Test
    fun `the picker still clears the verdict for a DRM pick`() {
        assertTrue(
            "the DRM pick in the streams screen is still what keeps that launch off MPV",
            source("com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt")
                .contains("PlayerEngine.clearLaunchAnime()")
        )
    }
}
