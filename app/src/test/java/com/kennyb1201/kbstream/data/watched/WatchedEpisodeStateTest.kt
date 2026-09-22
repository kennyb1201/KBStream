package com.kennyb1201.kbstream.data.watched

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Episode identity in this app is "<parentId>:<season>:<episode>", and the
 * same show is reachable under two id flavors (TMDB search / kids rails use
 * "tmdb:<n>", add-on catalogs and Continue Watching use "tt..."). A completed
 * row is stored under whichever flavor played it, so the merge has to be
 * flavor tolerant or the other flavor's copy reads as completely unwatched.
 */
class WatchedEpisodeStateTest {

    private fun completedRow(
        id: String,
        parentId: String,
        season: Int?,
        episode: Int?
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = "series",
        name = "Show",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = 0L,
        durationMs = 0L,
        updatedAt = 1L,
        isCompleted = true
    )

    @Test
    fun localRowsFromTheOtherFlavorAnchorToTheScreenId() {
        // Watched via the add-on route ("tt123"), now viewing the TMDB route.
        val rows = listOf(completedRow("tt123:2:5", "tt123", 2, 5))

        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = rows,
            simklCompletedEpisodes = emptySet()
        )

        assertTrue(
            "cross-flavor row should key the viewing screen's episode",
            keys.contains("tmdb:456:2:5")
        )
    }

    @Test
    fun sameFlavorRowsStillWork() {
        val rows = listOf(completedRow("tt123:2:5", "tt123", 2, 5))

        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tt123",
            localCompletedEntries = rows,
            simklCompletedEpisodes = emptySet()
        )

        assertTrue(keys.contains("tt123:2:5"))
    }

    @Test
    fun simklEpisodesAnchorToTheScreenIdRegardlessOfLocalRows() {
        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = emptyList(),
            simklCompletedEpisodes = setOf(1 to 3)
        )

        assertTrue(keys.contains("tmdb:456:1:3"))
    }

    @Test
    fun rowsWithoutSeasonOrEpisodeProduceNoKey() {
        val rows = listOf(completedRow("tt123", "tt123", null, null))

        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = rows,
            simklCompletedEpisodes = emptySet()
        )

        assertEquals(setOf("tt123"), keys)
    }

    @Test
    fun seasonEpisodesResolveForTheViewingScreen() {
        val rows = listOf(completedRow("tt123:2:5", "tt123", 2, 5))

        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = rows,
            simklCompletedEpisodes = emptySet()
        )

        val season = WatchedEpisodeState.localWatchedEpisodesForSeason(
            parentId = "tmdb:456",
            season = 2,
            watchedEpisodeKeys = keys
        )

        assertEquals(setOf(5), season)
    }

    @Test
    fun seasonEpisodesIgnoreOtherSeasonsAndShows() {
        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = listOf(
                completedRow("tmdb:456:2:5", "tmdb:456", 2, 5),
                completedRow("tmdb:456:3:1", "tmdb:456", 3, 1)
            ),
            simklCompletedEpisodes = emptySet()
        )

        val season = WatchedEpisodeState.localWatchedEpisodesForSeason(
            parentId = "tmdb:456",
            season = 2,
            watchedEpisodeKeys = keys
        )

        assertEquals(setOf(5), season)
        assertFalse(season.contains(1))
    }

    @Test
    fun effectiveSeasonMergesTrackersAndLocalRows() {
        val keys = WatchedEpisodeState.buildMergedWatchedKeys(
            parentId = "tmdb:456",
            localCompletedEntries = listOf(completedRow("tt123:2:5", "tt123", 2, 5)),
            simklCompletedEpisodes = setOf(2 to 1)
        )

        val season = WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
            parentId = "tmdb:456",
            season = 2,
            simklWatchedEpisodes = setOf(2 to 1),
            watchedEpisodeKeys = keys
        )

        assertEquals(setOf(1, 5), season)
    }
}
