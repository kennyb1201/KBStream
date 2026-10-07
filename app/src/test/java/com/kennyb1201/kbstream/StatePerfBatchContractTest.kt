package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remaining audit P2s: the played-link cache staying out of a binge's way,
 * the watched-override store never falling back to a global file, the three
 * performance items, and two stale comments.
 *
 * Each is wiring a pure test cannot reach (an Activity handoff, a prefs-name
 * fallback, a Compose recomposition), so the shape is pinned at the source.
 */
class StatePerfBatchContractTest {

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

    // ── a next-episode advance bypasses the played-link cache ──────────────

    @Test
    fun `the next-episode advance is excluded from the played-link cache`() {
        val main = readSource(MAIN)
        assertTrue(
            "the distinction must exist on PendingPlay",
            main.contains("val isNextEpisodeAdvance: Boolean") &&
                main.contains("!bingeGroup.isNullOrBlank() || !addonName.isNullOrBlank()")
        )
        assertTrue(
            "the cache read must skip an advance, or a re-binge replays the " +
                "previous ordering instead of the binge resolver's",
            main.contains("if (pending.isNextEpisodeAdvance) null")
        )
        assertTrue(
            "and the write must skip it too, so the next replay is not handed the " +
                "same stale ordering",
            main.contains("if (pending.isNextEpisodeAdvance) {")
        )
    }

    // ── the watched-override store is always profile-scoped ────────────────

    @Test
    fun `the watched-override fallback is profile-scoped, never global`() {
        val watched = readSource(WATCHED)
        assertTrue(
            "the collapsed-file fallback must still exist for the no-profile case",
            watched.contains("\"kbstream_watched_overrides\"")
        )
        assertTrue(
            "both call sites must resolve the name through ProfileStorage",
            watched.split("ProfileStorage.prefsName(").size - 1 >= 2
        )
        // No getSharedPreferences() addressed by the bare, unscoped name: that
        // is what sent overrides to a file no profile reads afterwards (and
        // shared them across profiles in the pre-onCreate window).
        val unscoped = Regex("getSharedPreferences\\(\\s*\"kbstream_watched_overrides\"")
        assertFalse(
            "an unscoped watched-overrides file must not be opened directly",
            unscoped.containsMatchIn(watched)
        )
    }

    // ── detail review pages are fetched in bounded parallel ───────────────

    @Test
    fun `the extra review pages are fetched in parallel, not one at a time`() {
        val detail = readSource(DETAIL_ENRICHMENT)
        assertTrue(
            "a bound must exist so the fan-out cannot burst",
            detail.contains("reviewPagesSemaphore = Semaphore(permits = 4)")
        )
        assertTrue(
            "the pages must be launched concurrently",
            detail.contains("async {") && detail.contains("reviewPagesSemaphore.withPermit {")
        )
        assertTrue(
            "and awaited together",
            detail.contains(".awaitAll()")
        )
        // The old serial walk must be gone.
        assertFalse(
            "the serial page walk is what made a cold open wait on up to 29 trips",
            detail.contains("(3..maxPage).forEach { page ->")
        )
    }

    // ── the guide's selection index is memoized ───────────────────────────

    @Test
    fun `the guide memoizes its selection index`() {
        val guide = readSource(GUIDE)
        assertTrue(
            "the per-recomposition O(n) scan must be memoized on its real inputs",
            guide.contains("remember(selectedChannelId, groupedChannels) {")
        )
    }

    // ── the hero's logo analysis is off the main thread ───────────────────

    @Test
    fun `the hero logo darkness analysis runs off the main thread`() {
        val home = readSource(HOME)
        assertTrue(
            "the analysis must be dispatched to a background worker",
            home.contains("withContext(Dispatchers.Default) {") &&
                home.contains("isDarkMonochromeArtwork(image.toBitmap())")
        )
        assertTrue(
            "and the composition must supply the scope that dispatch runs on",
            home.contains("val scope = rememberCoroutineScope()") &&
                home.contains("import androidx.compose.runtime.rememberCoroutineScope")
        )
    }

    // ── the two stale comments are corrected ──────────────────────────────

    @Test
    fun `the DV bonus comment matches the conservative code`() {
        val ranker = readSource(STREAM_RANKER)
        assertFalse(
            "a copy labeled both HDR10 and DV still loses the bonus when DV is not " +
                "useful, so the comment must not claim 'or both' keeps it",
            ranker.contains("HDR10 (or both) still keeps the bonus")
        )
    }

    @Test
    fun `the tier comment admits worked and slow are both reachable`() {
        val pref = readSource(SOURCE_PREFERENCE)
        assertTrue(
            "worked+slow is reachable through the public API, and the comment must say so",
            pref.contains("Both CAN be true at")
        )
        assertFalse(
            "the old comment wrongly called it a half-written store only",
            pref.contains("the two are only ever both true for a half-written store")
        )
    }

    // ── a lagging season listing is not cached as "nothing left" ────────

    @Test
    fun `the season-map cache keeps only seasons that carry episodes`() {
        val home = readSource(HOME_VM)
        assertTrue(
            "the populated-only filter must exist",
            home.contains("seasonEpisodesBySeason.filterValues { it.isNotEmpty() }")
        )
        assertTrue(
            "and the cache write must be gated on it, so an all-empty walk - TMDB\n" +
                "lagging - is not stored for the whole TTL and does not mark the\n" +
                "show locally finished with its tracker card suppressed",
            home.contains("if (populatedSeasons.isNotEmpty()) {") &&
                home.contains("to populatedSeasons")
        )
        assertTrue(
            "the old map-wide guard is what let an all-empty walk through",
            !home.contains("showSeasonEpisodesCache[tmdbId] =\n            System.currentTimeMillis() to seasonEpisodesBySeason.toMap()")
        )
    }

    @Test
    fun `the hero prewarm comment names the size the hero actually draws`() {
        val prewarm = readSource(HERO_PREWARM)
        assertTrue(prewarm.contains("1280x720"))
        assertFalse(
            "the stale 1920x1080 size no longer matches the hero's own decode",
            prewarm.contains("1920x1080")
        )
    }

    private companion object {
        private const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        private const val WATCHED =
            "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        private const val DETAIL_ENRICHMENT =
            "com/kennyb1201/kbstream/ui/detail/DetailRatingEnrichment.kt"
        private const val GUIDE =
            "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        private const val HOME =
            "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        private const val STREAM_RANKER =
            "com/kennyb1201/kbstream/domain/streamengine/StreamRanker.kt"
        private const val SOURCE_PREFERENCE =
            "com/kennyb1201/kbstream/domain/streamengine/SourceAddonPreference.kt"
        private const val HERO_PREWARM =
            "com/kennyb1201/kbstream/ui/home/HeroPrewarm.kt"
        private const val HOME_VM =
            "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
