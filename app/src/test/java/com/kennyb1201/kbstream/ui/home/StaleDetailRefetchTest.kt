package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice B: a stale local "finished" verdict must not cost the viewer the episode
 * that aired after it.
 *
 * The two halves are tested separately: the pair a verdict is recorded with (pure,
 * below), and the wiring that asks TMDB again before believing a month-old detail
 * (the cache chain and the gate, pinned from source because the gate needs a
 * database, a tracker session and a network).
 */
class StaleDetailRefetchTest {

    // ── the pair a verdict carries (ii-1) ──────────────────────────────────

    @Test
    fun `the furthest watched pair is season-major over local and tracker evidence`() {
        assertEquals(
            "a later season wins whatever its episode number",
            4 to 2,
            furthestWatchedPair(
                mergedWatchedKeys = setOf("tt1:3:9", "tt1:4:2"),
                trackerWatchedEpisodes = setOf(1 to 5)
            )
        )
        assertEquals(
            "and the tracker's plays count when its shows are merged under another flavor",
            2 to 6,
            furthestWatchedPair(
                mergedWatchedKeys = setOf("tt1:2:3"),
                trackerWatchedEpisodes = setOf(2 to 6)
            )
        )
    }

    @Test
    fun `a pair is only something an episode can be derived from`() {
        assertNull(
            "no rows at all: nothing is proved",
            furthestWatchedPair(emptySet(), emptySet())
        )
        assertNull(
            "row ids that name no episode prove nothing (honest-unknown)",
            furthestWatchedPair(setOf("a-raw-history-row-id"), emptySet())
        )
        assertEquals(
            "a specials play is not the furthest episode watched",
            2 to 3,
            furthestWatchedPair(setOf("tt1:0:4"), setOf(2 to 3))
        )
        assertNull(
            "and a tracker's 0-numbered play is no episode either",
            furthestWatchedPair(emptySet(), setOf(0 to 4, 3 to 0))
        )
    }

    @Test
    fun `the detail's own pair only counts when the evidence lists it`() {
        assertTrue(
            "proved by the merged local keys",
            isEpisodeInWatchedEvidence(
                pair = 1 to 8,
                mergedWatchedKeys = setOf("tt1:1:8"),
                trackerWatchedEpisodes = emptySet()
            )
        )
        assertTrue(
            "proved by a tracker's set",
            isEpisodeInWatchedEvidence(
                pair = 2 to 1,
                mergedWatchedKeys = emptySet(),
                trackerWatchedEpisodes = setOf(2 to 1)
            )
        )
        assertFalse(
            "a different episode is not evidence for this one",
            isEpisodeInWatchedEvidence(
                pair = 1 to 8,
                mergedWatchedKeys = setOf("tt1:1:7"),
                trackerWatchedEpisodes = emptySet()
            )
        )
        assertFalse(
            "and a detail alone proves nothing",
            isEpisodeInWatchedEvidence(
                pair = 1 to 8,
                mergedWatchedKeys = emptySet(),
                trackerWatchedEpisodes = emptySet()
            )
        )
    }

    // ── the bypass (fix i) ─────────────────────────────────────────────────

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

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("end marker missing after $start: $end", to > from)
        return text.substring(from, to)
    }

    private fun repository() = source(TMDB_REPOSITORY)

    private fun viewModel() = source(VIEW_MODEL)

    private fun returningGate() = slice(
        viewModel(),
        "private suspend fun selectReturningShowCandidates(",
        "\n    /**"
    )

    private fun localNextUpLoader() = slice(
        viewModel(),
        "private suspend fun loadLocalNextUpItems(",
        "\n    /**"
    )

    @Test
    fun `every link of the detail chain can be asked again, and defaults to the cache`() {
        val repo = repository()

        listOf(
            "suspend fun fetchEnrichedMetaCached(",
            "suspend fun getDetailByTmdbId(",
            "suspend fun lookupDetailByTmdbId(",
            "suspend fun lookupDetailByImdbId(",
            "suspend fun lookupDetailByExternalId(",
            "suspend fun lookupDetailForRawId("
        ).forEach { head ->
            assertTrue(
                "$head must take a forceRefresh that defaults to today's behaviour",
                repo.substringAfter(head)
                    .take(3000)
                    .contains("forceRefresh: Boolean = false")
            )
        }

        assertTrue(
            "and each link threads it to the next instead of swallowing it: the " +
                "TMDB-id route, the IMDb passthrough and all three raw-id branches",
            repo.contains("lookupDetailByTmdbId(tmdbId, type, forceRefresh) as? TmdbDetailLookup.Found") &&
                repo.contains("lookupDetailByExternalId(imdbId, \"imdb_id\", type, forceRefresh)") &&
                repo.contains("lookupDetailByTmdbId(tmdbId, type, forceRefresh)") &&
                repo.contains("lookupDetailByImdbId(trimmed.substringBefore(':'), type, forceRefresh)") &&
                repo.contains("lookupDetailByExternalId(tvdbId, \"tvdb_id\", type, forceRefresh)")
        )

        val vm = viewModel()
        assertTrue(
            "and the Home lookup threads it through the SAME flavor branch it reads",
            vm.contains(
                "tmdbRepository.getDetailByTmdbId(\n" +
                    "                                    id,\n" +
                    "                                    \"series\",\n" +
                    "                                    forceRefresh\n" +
                    "                                )"
            ) &&
                vm.contains(
                    "tmdbRepository.fetchEnrichedMetaCached(\n" +
                        "                            parentId,\n" +
                        "                            \"series\",\n" +
                        "                            forceRefresh = forceRefresh\n" +
                        "                        )"
                ) &&
                vm.contains(
                    "tmdbRepository.lookupDetailForRawId(\n" +
                        "                    parentId,\n" +
                        "                    \"series\",\n" +
                        "                    forceRefresh\n" +
                        "                )"
                )
        )
    }

    @Test
    fun `a forced call skips the caches instead of trusting them`() {
        val repo = repository()

        assertTrue(
            "the TMDB-id route skips its memory TTL and its disk row",
            repo.contains(
                "        if (!forceRefresh) {\n" +
                    "            val cached = detailCache[key]\n" +
                    "            if (cached != null && now - cached.first < detailCacheTtlMs) {\n" +
                    "                cached.second?.let { return TmdbDetailLookup.Found(it) }"
            ) &&
                repo.windowed("val diskCached = if (forceRefresh) {".length)
                    .count { it == "val diskCached = if (forceRefresh) {" } >= 2
        )

        assertTrue(
            "the enriched route skips memory and BOTH disk rows (full and rail)",
            repo.contains("        if (!forceRefresh) {\n            val cached = detailCache[key]") &&
                repo.contains("if (!full && !forceRefresh) {")
        )

        assertTrue(
            "and the external-id route has its own bypass, because it files under " +
                "a different key - a fresh detail fetched by TMDB id never answers it",
            repo.contains("forceRefresh: Boolean = false\n    ): TmdbDetailLookup {") &&
                repo.contains("val railDiskCached = if (forceRefresh) {")
        )

        assertTrue(
            "the write-back still happens on the normal path, under the normal key",
            repo.contains("cacheJson(diskKey, detailJsonAdapter.toJson(detail), now)") &&
                repo.contains("cacheJson(\n                    railDetailKey(key),")
        )

        assertTrue(
            "a miss is still not cached",
            repo.contains("val detail = (result as? TmdbDetailLookup.Found)?.detail\n        if (detail != null) {")
        )
    }

    @Test
    fun `no TTL changed`() {
        val repo = repository()
        assertTrue(
            "the 12h memory TTL is untouched",
            repo.contains("detailCacheTtlMs = 12L * 60L * 60L * 1000L")
        )
        assertTrue(
            "and the 30-day disk TTL is untouched - the bypass is the whole point",
            repo.contains("detailCacheDiskTtlMs = 30L * 24L * 60L * 60L * 1000L")
        )
    }

    // ── the gate (fix i-3) ─────────────────────────────────────────────────

    @Test
    fun `a finished-looking verdict is re-asked before it is believed`() {
        val gate = returningGate()

        assertTrue(
            "the re-fetch only runs when the cached answer says caught up",
            gate.contains("if (!qualifies && refetchAttempts < max) {")
        )
        assertTrue(
            "through the group's OWN flavor, so the write lands where the builder reads",
            gate.contains("seriesDetailFor(group.parentId, forceRefresh = true)")
        )
        assertTrue(
            "and the gate is then re-run on the fresh last-aired episode",
            gate.windowed("hasUnwatchedAiredEpisodes(".length)
                .count { it == "hasUnwatchedAiredEpisodes(" } == 2
        )

        val fetchAt = gate.indexOf("seriesDetailFor(group.parentId, forceRefresh = true)")
        val attemptAt = gate.indexOf("refetchAttempts += 1")
        assertTrue(
            "an attempt is counted before the ask, so a dead network cannot spin the pass",
            attemptAt in 0 until fetchAt
        )
        assertTrue(
            "and the cap is the output cap, not a new one",
            gate.contains("refetchAttempts < max")
        )
    }

    @Test
    fun `a failed re-fetch keeps the cached verdict, and a miss is never re-fetched`() {
        val gate = returningGate()

        assertTrue(
            "only groups the gate already had a verdict for: no detail, no attempt",
            gate.indexOf("val detail = details[group.parentId] ?: continue") <
                gate.indexOf("seriesDetailFor(group.parentId, forceRefresh = true)")
        )
        assertTrue(
            "and no last-aired episode, no attempt either",
            gate.indexOf("var lastAired = detail.lastEpisodeToAir ?: continue") <
                gate.indexOf("seriesDetailFor(group.parentId, forceRefresh = true)")
        )
        assertTrue(
            "a null answer changes nothing",
            gate.contains("if (refreshed != null) {")
        )
        assertTrue(
            "the group is only added when the surviving verdict says it qualifies",
            gate.indexOf("if (qualifies) {") > gate.indexOf("seriesDetailFor(group.parentId, forceRefresh = true)")
        )
    }

    @Test
    fun `the re-fetch reaches the builder through the cache it warms`() {
        val vm = viewModel()

        assertTrue(
            "the builder re-reads through the cached flavor lookup...",
            slice(
                vm,
                "private suspend fun buildLocalNextUpItem(",
                "val detail = (lookup as? TmdbDetailLookup.Found)?.detail"
            ).contains("seriesDetailLookup(parentId)")
        )

        val loader = localNextUpLoader()
        assertTrue(
            "...after the returning pass has run: the gate must warm the detail " +
                "before the builder's own read, never after it",
            loader.indexOf("selectReturningShowCandidates(") in
                0 until loader.indexOf("buildLocalNextUpItem(")
        )
    }

    // ── the recorded verdict and its plumbing (fix ii) ─────────────────────

    @Test
    fun `both recording sites carry the pair they can prove`() {
        val builder = slice(
            viewModel(),
            "private suspend fun buildLocalNextUpItem(",
            "\n    private suspend fun preload"
        )

        assertEquals(
            "the resolver-null path and the merged-state path both record",
            2,
            builder.split("recordLocallyFinishedShow(").size - 1
        )
        assertTrue(
            "the merged-state path offers the detail's latest aired pair, which the " +
                "recorder verifies against the evidence before using",
            builder.contains("provedWatched = detail.lastEpisodeToAir?.let { lastAired ->")
        )
        assertTrue(
            "and a null/zero pair is not turned into episode 0",
            builder.contains("if (season != null && episode != null) season to episode else null")
        )
    }

    @Test
    fun `the recorder only relaxes the rule on evidence that answered`() {
        val recorder = slice(
            viewModel(),
            "private suspend fun recordLocallyFinishedShow(",
            "\n    /**"
        )

        assertTrue(
            "the merged state is read for this show",
            recorder.contains("watchedEpisodeKeysByShow[parentId].orEmpty()") &&
                recorder.contains("trackerWatchedEpisodesByShow[parentId].orEmpty()")
        )
        assertTrue(
            "under the mutex the rest of the preload uses",
            recorder.contains("watchedStateMutex.withLock {")
        )
        assertTrue(
            "the pair is the union's furthest, else the proved pair only when listed",
            recorder.contains("furthestWatchedPair(") &&
                recorder.contains("isEpisodeInWatchedEvidence(")
        )
        assertTrue(
            "and the record is filed under every key the show answers to",
            recorder.contains("entry.keys.forEach { key -> caughtUpShows[key] = entry }")
        )
    }

    @Test
    fun `the merge filters on the map, not a set of show keys`() {
        val vm = viewModel()
        val merge = slice(vm, "val locallyFinishedShows:", "locallyFinished =")

        assertTrue(
            "a map keyed by the show's keys",
            merge.contains("MutableMap<String, LocallyFinishedShow>") &&
                merge.contains("Collections.synchronizedMap(")
        )
        assertTrue(
            "handed to the local pass...",
            merge.contains("loadLocalNextUpItems(\n                                    localItems,\n                                    locallyFinishedShows")
        )
        assertTrue(
            "...and to the tracker filter",
            merge.contains("trackerCardLocallyFinished(\n                                        item,\n                                        locallyFinishedShows")
        )
        assertTrue(
            "an empty map still short-circuits, as the empty set did",
            merge.contains("if (locallyFinishedShows.isEmpty()) {")
        )
        assertFalse(
            "and nothing still passes a bare set of keys",
            vm.contains("trackerCardLocallyFinished(\n                                        item,\n                                        locallyFinishedShowKeys")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val TMDB_REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
