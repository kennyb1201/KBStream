package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caught-up twin fix: a finished show must not keep a "next up" card.
 *
 * Reported: South Park, watched S29E2, ticked on Detail, and Continue Watching
 * still offered S29E2 as the next thing to watch. Three holes fed it, all in the
 * local path:
 *
 *  - the watched-state preload read local history under at most three id
 *    flavors (missing `tvdb:<n>` and the bare number), so a row filed under one
 *    of those was invisible and the show looked unwatched;
 *  - the regular candidate set grouped by RAW parent id, so one show filed
 *    under two flavors produced two candidates, one of them built from an older
 *    episode - and that row was the listing fallback's floor;
 *  - caught-up was only recorded when the resolver answered null, which it
 *    never does when it refuses to walk or walks blind, so a show whose latest
 *    aired episode WAS watched was never recorded as finished and its tracker
 *    twin survived.
 */
class SouthParkCaughtUpTwinTest {

    // ── T1: the flavor list ─────────────────────────────────────────

    @Test
    fun `the twin list covers every id flavor the show is filed under`() {
        assertEquals(
            listOf("tt12345", "tmdb:4567", "4567", "tvdb:999"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = 4567,
                imdbId = "tt12345",
                tvdbId = 999
            )
        )

        // Filed under the TVDB flavor: it stays first and is not duplicated.
        assertEquals(
            listOf("tvdb:999", "tmdb:4567", "4567", "tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tvdb:999",
                tmdbShowId = 4567,
                imdbId = "tt12345",
                tvdbId = 999
            )
        )

        // No TVDB id resolved: no tvdb flavor is invented, and the unresolved
        // tmdb sentinel still adds nothing.
        assertEquals(
            listOf("tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = -1,
                tvdbId = 0
            )
        )
    }

    @Test
    fun `a failed tvdb lookup without a proven miss marks the read incomplete`() {
        assertTrue(
            "a failed lookup may be blind to tvdb-filed rows",
            isTvdbTwinUnknown(
                tmdbId = 4567,
                tvdbId = null,
                parentId = "tt12345",
                missRecorded = false
            )
        )

        assertFalse(
            "a proven \"no TVDB id\" answer is not blindness",
            isTvdbTwinUnknown(4567, null, "tt12345", missRecorded = true)
        )
        assertFalse(
            "a resolved twin is not unknown",
            isTvdbTwinUnknown(4567, 999, "tt12345", missRecorded = false)
        )
        assertFalse(
            "no TMDB id means the lookup never ran",
            isTvdbTwinUnknown(0, null, "tt12345", missRecorded = false)
        )

        assertTrue(
            "the aggregate reports either twin",
            isIdTwinReadIncomplete(
                tmdbId = 4567,
                imdbId = "tt12345",
                tvdbId = null,
                parentId = "tt12345",
                imdbMissRecorded = false,
                tvdbMissRecorded = false
            )
        )
    }

    @Test
    fun `both ids come off one external-ids response`() {
        val models = source("com/kennyb1201/kbstream/data/tmdb/TmdbModels.kt")
        assertTrue(
            "the tvdb id TMDB already returns must be parsed, or the twin can never resolve",
            models.contains("@Json(name = \"tvdb_id\") val tvdbId: Int? = null")
        )

        val repository = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
        assertTrue(repository.contains("suspend fun resolveTvdbId(tmdbId: Int, type: String): Int?"))
        assertTrue(repository.contains("internal fun hasProvenNoTvdbMapping("))
        assertTrue(
            "the pair the IMDb resolver fetched must be remembered, or the TVDB twin costs a second request",
            repository.contains("rememberExternalIds(key, ext, now)")
        )
        assertTrue(
            "one cache for the pair, memory and disk",
            repository.contains("externalIdsMemoryCache") &&
                repository.contains("readJsonCacheRow(\"extids:")
        )
    }

    // ── T2/T3: the latest aired episode is watched ───────────────────

    @Test
    fun `the latest aired episode in the merged state means caught up`() {
        assertTrue(
            "the local rows are re-anchored to the card's parent id",
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tt0121955:29:1", "tt0121955:29:2"),
                trackerWatchedEpisodes = emptySet()
            )
        )

        assertTrue(
            "an episode-suffixed key parses to its pair",
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tmdb:2190:29:2"),
                trackerWatchedEpisodes = emptySet()
            )
        )

        assertTrue(
            "the tracker's raw pair counts without a built key",
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = emptySet(),
                trackerWatchedEpisodes = setOf(29 to 2)
            )
        )

        assertFalse(
            "the previous episode watched is not caught up",
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tt0121955:29:1"),
                trackerWatchedEpisodes = emptySet()
            )
        )
    }

    @Test
    fun `no last-aired episode abstains`() {
        // A stale or unresolvable detail has nothing to prove caught-up with.
        assertFalse(
            latestAiredEpisodeWatched(
                latestSeason = null,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tt0121955:29:2"),
                trackerWatchedEpisodes = emptySet()
            )
        )
        assertFalse(
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = null,
                mergedWatchedKeys = setOf("tt0121955:29:2"),
                trackerWatchedEpisodes = emptySet()
            )
        )
    }

    @Test
    fun `the recording runs on the merged state before the omit branch`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

        val mergedStateCheck = viewModel.indexOf("latestAiredEpisodeWatched(")
        val omitBranch = viewModel.indexOf("shouldOmitLocalAdvanceCard(")

        assertTrue("the merged-state caught-up check is missing", mergedStateCheck >= 0)
        assertTrue(
            "it must run BEFORE the advance card is decided, or the card is printed first",
            mergedStateCheck in 0 until omitBranch
        )
        assertTrue(
            "both the resolver-null path and the merged-state path record caught up",
            viewModel.windowed("caughtUpShowKeys += upNextShowParentKeys(".length)
                .count { it == "caughtUpShowKeys += upNextShowParentKeys(" } >= 2
        )
    }

    // ── T4: one candidate per show in the regular 25 ─────────────────

    @Test
    fun `two flavors of one show yield one candidate built from the newest row`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                completedRow(id = "e1", parentId = "tt0121955", episode = 1, at = 100L),
                completedRow(id = "e2", parentId = "tmdb:2190", episode = 2, at = 300L)
            ),
            representedIdentifiers = emptySet(),
            max = 10,
            tmdbIdFor = { parentId ->
                if (parentId == "tt0121955" || parentId == "tmdb:2190") 2190 else null
            }
        )

        assertEquals(1, result.size)
        assertEquals("tt0121955", result.single().first)
        assertEquals(
            "the card's row must be the newest across the flavors - it is the fallback floor",
            "e2",
            result.single().second.id
        )
    }

    @Test
    fun `a show already on the rail is skipped whichever flavor it is filed under`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                completedRow(id = "e1", parentId = "tt0121955", episode = 1, at = 100L),
                completedRow(id = "e2", parentId = "tt777", episode = 1, at = 200L)
            ),
            representedIdentifiers = setOf("2190"),
            max = 10,
            tmdbIdFor = { parentId -> if (parentId == "tt0121955") 2190 else null }
        )

        assertEquals(listOf("tt777"), result.map { it.first })
    }

    @Test
    fun `unresolved flavors keep the raw grouping`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                completedRow(id = "a", parentId = "tt1", episode = 1, at = 10L),
                completedRow(id = "b", parentId = "tt2", episode = 1, at = 20L)
            ),
            representedIdentifiers = emptySet(),
            max = 10
        )

        assertEquals(listOf("tt2", "tt1"), result.map { it.first })
    }

    // ── T5: a show with something left keeps its card ────────────────

    @Test
    fun `an unwatched latest aired episode still leaves the card`() {
        assertFalse(
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tt0121955:29:1"),
                trackerWatchedEpisodes = emptySet()
            )
        )

        // The advance-card rule itself is unchanged: only a self-target or an
        // incomplete read is withheld, never a legitimately later episode.
        assertFalse(shouldOmitLocalAdvanceCard(29, 2, 29, 1, evidenceIncomplete = false))
        assertTrue(shouldOmitLocalAdvanceCard(29, 1, 29, 1, evidenceIncomplete = false))
    }

    // ── T6: the listing fallback floors on the furthest pair ─────────

    @Test
    fun `the fallback floors on the furthest known pair`() {
        // Row E3, merged state knows E5, E7 aired: the fallback must print E6.
        assertEquals(1 to 5, fallbackEpisodeFloor(1, 3, 1, 5))

        // A resolved pair behind the row changes nothing.
        assertEquals(1 to 3, fallbackEpisodeFloor(1, 3, 1, 2))

        // Season-major, like every other furthest-point rule here.
        assertEquals(2 to 1, fallbackEpisodeFloor(1, 12, 2, 1))

        // No pair (the incomplete short-circuit) keeps the row, as today.
        assertEquals(1 to 3, fallbackEpisodeFloor(1, 3, null, null))
        assertEquals(1 to 3, fallbackEpisodeFloor(1, 3, 1, null))
    }

    @Test
    fun `the fallback is called with the floored pair`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        assertTrue(viewModel.contains("fallbackEpisodeFloor("))
        assertTrue(
            "the floored pair must be what the listing walk starts from",
            viewModel.contains("rowSeason = floor.first,") &&
                viewModel.contains("rowEpisode = floor.second")
        )
    }

    // ── T7: the paths that already worked ───────────────────────────

    @Test
    fun `the resolver-null path still records caught up`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val slice = viewModel
            .substringAfter("if (resolved == null) {")
            .substringBefore("if (resolved.isResume) return null")

        assertTrue(
            "a show with nothing left must still be recorded as finished",
            slice.contains("caughtUpShowKeys += upNextShowParentKeys(")
        )
    }

    @Test
    fun `a local-only show with its latest aired episode watched also clears`() {
        // No tracker evidence at all: the merged key set alone answers, which
        // is what makes the fix hold after "Remove" wiped the Simkl history.
        assertTrue(
            latestAiredEpisodeWatched(
                latestSeason = 29,
                latestEpisode = 2,
                mergedWatchedKeys = setOf("tt0121955:29:2"),
                trackerWatchedEpisodes = emptySet()
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun completedRow(
        id: String,
        parentId: String,
        episode: Int,
        at: Long
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = "series",
        name = "South Park",
        poster = null,
        streamUrl = null,
        season = 29,
        episode = episode,
        positionMs = 0L,
        durationMs = 0L,
        updatedAt = at,
        isCompleted = true,
        completedAt = at
    )

    private val root: File by lazy { findSourceRoot() }

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
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }
}
