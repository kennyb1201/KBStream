package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A show reaches the app under two id flavors - an add-on catalog / Continue
 * Watching row keyed by the IMDb id ("tt...") and a TMDB search result keyed by
 * "tmdb:<n>" - and a completed episode is stored under whichever flavor played
 * it. Filing the SAME episode under both flavors must still give the viewer ONE
 * next-up card, not two.
 *
 * The claim being verified is that the read path already re-anchors a local row
 * to the viewing screen's id (`buildMergedWatchedKeys`) and the rail collapses
 * the two id flavors of one show (`dedupeAndSortUpNext` pairing their resolved
 * TMDB id). Both halves are pinned here so a regression in either one shows up
 * as two cards for one show.
 */
class FlavorCollapseNextUpTest {

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

    /** A next-up card as `buildLocalNextUpItem` builds it: id keyed by parent. */
    private fun nextUpCard(parentId: String, tmdbId: Int?): UpNextItem =
        UpNextItem(
            id = "nextup:$parentId",
            title = "Show",
            poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
            badge = UpNextBadge.NEXT_UP,
            parentId = parentId,
            parentType = "series",
            season = 1,
            episode = 2,
            tmdbId = tmdbId
        )

    @Test
    fun `the same episode completed under two id flavors reads watched exactly once`() {
        val rows = listOf(
            completedRow("tmdb:456:1:1", "tmdb:456", 1, 1),
            completedRow("tt123:1:1", "tt123", 1, 1)
        )

        // Whichever flavor the screen is open under, S1E1 is watched once, so
        // the next-up target is S1E2 - a single card, not two.
        for (parentId in listOf("tmdb:456", "tt123")) {
            val keys = WatchedEpisodeState.buildMergedWatchedKeys(
                parentId = parentId,
                localCompletedEntries = rows,
                simklCompletedEpisodes = emptySet()
            )
            val watched = WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
                parentId = parentId,
                season = 1,
                simklWatchedEpisodes = emptySet(),
                watchedEpisodeKeys = keys
            )

            assertEquals(
                "season 1 must read as exactly one watched episode under $parentId",
                setOf(1),
                watched
            )
        }
    }

    @Test
    fun `the two id flavors of one show collapse to a single next-up card`() {
        // Both cards resolved the same TMDB id, which is how the rail pairs a
        // "tt..." card with its "tmdb:..." twin.
        val imdbFlavor = nextUpCard(parentId = "tt123", tmdbId = 456)
        val tmdbFlavor = nextUpCard(parentId = "tmdb:456", tmdbId = 456)

        val rail = dedupeAndSortUpNext(listOf(imdbFlavor, tmdbFlavor))

        assertEquals(
            "one show must produce one card however its rows are keyed",
            1,
            rail.size
        )
    }

    @Test
    fun `a single flavor still collapses to itself`() {
        val rail = dedupeAndSortUpNext(listOf(nextUpCard(parentId = "tt123", tmdbId = 456)))

        assertEquals(1, rail.size)
        assertEquals("nextup:tt123", rail.single().id)
    }

    // ── the returning pass re-keys by resolved TMDB id ─────────────────

    @Test
    fun `two id flavors of one show collapse to one returning candidate`() {
        val rows = listOf(
            completedRow("tmdb:456:1:1", "tmdb:456", 1, 1),
            completedRow("tt123:1:2", "tt123", 1, 2)
        )

        val groups = collapseReturningShowCandidates(
            completedRows = rows,
            excludedParentIds = emptySet(),
            tmdbIdFor = { pid -> if (pid == "tt123" || pid == "tmdb:456") 456 else null }
        )

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("tmdb:456", group.key)
        // Furthest-watched is now computed over the UNION of both flavors.
        assertEquals(
            setOf("tmdb:456:1:1", "tt123:1:2"),
            group.rows.map { it.id }.toSet()
        )
        // The player canonicalizes to the IMDb flavor, so the card keeps it.
        assertEquals("tt123", group.parentId)
    }

    @Test
    fun `a flavor whose detail does not resolve stays its own raw group`() {
        val rows = listOf(
            completedRow("a", "tt123", 1, 1),
            completedRow("b", "tmdb:456", 1, 1)
        )

        val groups = collapseReturningShowCandidates(
            completedRows = rows,
            excludedParentIds = emptySet(),
            tmdbIdFor = { pid -> if (pid == "tt123") 456 else null }
        )

        assertEquals(setOf("tmdb:456", "raw:tmdb:456"), groups.map { it.key }.toSet())
        assertEquals("tt123", groups.first { it.key == "tmdb:456" }.parentId)
        assertEquals("tmdb:456", groups.first { it.key == "raw:tmdb:456" }.parentId)
    }

    @Test
    fun `the regular candidate excludes its twin by resolved key`() {
        // The regular 25 holds tt123; the returning pass holds tmdb:456. Same
        // show, so the second flavor must not become a second card.
        val groups = collapseReturningShowCandidates(
            completedRows = listOf(completedRow("x", "tmdb:456", 1, 1)),
            excludedParentIds = setOf("tt123"),
            tmdbIdFor = { pid -> if (pid == "tt123" || pid == "tmdb:456") 456 else null }
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `two shows with distinct detail ids never merge`() {
        val groups = collapseReturningShowCandidates(
            completedRows = listOf(
                completedRow("a", "tt111", 1, 1),
                completedRow("b", "tmdb:222", 1, 1)
            ),
            excludedParentIds = emptySet(),
            tmdbIdFor = { pid ->
                when (pid) {
                    "tt111" -> 111
                    "tmdb:222" -> 222
                    else -> null
                }
            }
        )

        assertEquals(2, groups.size)
        assertEquals(setOf("tmdb:111", "tmdb:222"), groups.map { it.key }.toSet())
    }

    @Test
    fun `without a tt flavor the newest row's flavor survives`() {
        val groups = collapseReturningShowCandidates(
            completedRows = listOf(
                completedRow("old", "tmdb:456", 1, 1).copy(completedAt = 1L, updatedAt = 1L),
                completedRow("new", "tvdb:456", 1, 2).copy(completedAt = 9L, updatedAt = 9L)
            ),
            excludedParentIds = emptySet(),
            tmdbIdFor = { 456 }
        )

        assertEquals(1, groups.size)
        assertEquals("tvdb:456", groups.single().parentId)
    }
}
