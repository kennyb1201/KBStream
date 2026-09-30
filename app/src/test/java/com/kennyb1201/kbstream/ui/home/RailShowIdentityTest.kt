package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both Home rails keep ONE entry per show, even though a show reaches them
 * under more than one id flavor at once.
 *
 * Reported bug: the same show appeared twice in Continue Watching AND in
 * Upcoming. Neither rail compared what the two cards actually resolve to:
 *
 *  - Continue Watching grouped on `upNextShowKey`, one hand-picked key per
 *    card, so an imdb-keyed card ("parent:series:tt...") and its tmdb-keyed
 *    twin ("parent:series:97546") landed in different groups and both survived
 *    whenever they were not in the "resume vs suggestion" relationship
 *    `collapseDuplicateUpNextCards` already handled (two resume points, or two
 *    suggestions).
 *  - Upcoming deduped on the raw `parentId` string, so the same show produced
 *    two rows - one from the local card, one from the Simkl caught-up card.
 *
 * The resolved TMDB id is what pairs the flavors, which is why grouping uses
 * [upNextGroupingKeys] rather than card order or the display title.
 */
class RailShowIdentityTest {

    private fun card(
        parentId: String,
        title: String = "PAW Patrol",
        parentType: String = "series",
        tmdbId: Int? = null,
        season: Int? = 3,
        episode: Int? = 16,
        startPositionMs: Long = 0L,
        progressPercent: Float? = null,
        recencyTimestamp: Long = 1_000L
    ): UpNextItem = UpNextItem(
        id = "history:$parentId:$season:$episode",
        title = title,
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        startPositionMs = startPositionMs,
        progressPercent = progressPercent,
        recencyTimestamp = recencyTimestamp,
        tmdbId = tmdbId
    )

    private fun upcomingRow(
        item: UpNextItem,
        airDateEpochMs: Long,
        episodeTitle: String? = null,
        poster: String? = null
    ): UpcomingEpisode = UpcomingEpisode(
        id = "upcoming:${item.parentId}",
        parentId = item.parentId ?: "",
        parentType = item.parentType ?: "series",
        title = item.title,
        poster = poster,
        backdrop = null,
        season = item.season ?: 1,
        episode = item.episode ?: 1,
        airDateEpochMs = airDateEpochMs,
        airDateLabel = "Today",
        episodeTitle = episodeTitle
    )

    // ── grouping keys ────────────────────────────────────────────────

    @Test
    fun `an imdb card meets its tmdb twin through the resolved tmdb id`() {
        val imdb = card(parentId = "tt0898266", tmdbId = 97546)
        val tmdb = card(parentId = "tmdb:97546", tmdbId = 97546)

        val shared =
            upNextGroupingKeys(imdb) intersect upNextGroupingKeys(tmdb)

        assertEquals(setOf("parent:series:97546"), shared)
    }

    @Test
    fun `two different titles that share a name are not grouped`() {
        // The two "Ghostbusters": a remake and its original. Grouping on the
        // name would hide one of them from the rail, which is why the title
        // key is only a fallback for a card with no id at all.
        val original = card(
            parentId = "tt0087332",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 620,
            season = null,
            episode = null
        )
        val remake = card(
            parentId = "tt1289401",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 43074,
            season = null,
            episode = null
        )

        assertTrue(
            (upNextGroupingKeys(original) intersect upNextGroupingKeys(remake)).isEmpty()
        )

        // The looser identity keys do merge them, which is exactly the
        // distinction: those pair a duplicate, these group the rail.
        assertTrue(
            (upNextIdentityKeys(original) intersect upNextIdentityKeys(remake))
                .isNotEmpty()
        )
    }

    @Test
    fun `a card with no id falls back to the title`() {
        val noIds = card(parentId = "", title = "Paw Patrol", tmdbId = null)

        assertEquals(
            setOf(upNextTitleKey(noIds)),
            upNextGroupingKeys(noIds)
        )
    }

    @Test
    fun `an unresolved twin still meets its named card`() {
        // Reported: a couple of shows stayed doubled on Continue Watching
        // because one side's TMDB lookup failed - so it carried only its own
        // flavor's parent id, no resolved id, and had nothing to meet the
        // other flavor on. Strict grouping leaves them as two; the title-key
        // merge is what brings them back to one.
        val unresolved = card(
            parentId = "tt0898266",
            title = "PAW Patrol",
            tmdbId = null,
            startPositionMs = 60_000L
        )
        val resolved = card(
            parentId = "tmdb:97546",
            title = "PAW Patrol",
            tmdbId = 97546
        )

        assertEquals(
            2,
            clusterByIdentityKeys(
                listOf(unresolved, resolved),
                ::upNextGroupingKeys
            ).size
        )
        assertEquals(1, dedupeAndSortUpNext(listOf(unresolved, resolved)).size)
    }

    @Test
    fun `a namesake is merged only when one card has no resolved id`() {
        // A card with no resolved id has nothing but its name to be found by,
        // so it joins a same-named card; two cards that BOTH resolved their
        // own TMDB ids are two different shows however they are named.
        val unresolved = card(
            parentId = "tt0898266",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = null,
            season = null,
            episode = null
        )
        val namesakeResolved = card(
            parentId = "tt1289401",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 43074,
            season = null,
            episode = null
        )

        assertEquals(
            1,
            dedupeAndSortUpNext(listOf(unresolved, namesakeResolved)).size
        )

        val originalResolved = card(
            parentId = "tt0087332",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 620,
            season = null,
            episode = null
        )

        assertEquals(
            2,
            dedupeAndSortUpNext(listOf(originalResolved, namesakeResolved)).size
        )
    }

    // ── transitive clustering ────────────────────────────────────────

    @Test
    fun `a chain of cards sharing a key per link lands in one cluster`() {
        // A carries tt+tmdb, B carries only the tmdb id, C carries a tvdb id
        // plus the tmdb id: A-B pair on the tmdb id and B-C on it too, so all
        // three are one show even though A and C share no key directly.
        val a = card(parentId = "tt0898266", tmdbId = 97546)
        val b = card(parentId = "97546", tmdbId = null)
        val c = card(parentId = "tvdb:999", tmdbId = 97546)

        val clusters = clusterByIdentityKeys(listOf(a, b, c), ::upNextGroupingKeys)

        assertEquals(1, clusters.size)
        assertEquals(3, clusters.single().size)
    }

    @Test
    fun `a snapshot card keyed tmdb pairs with the enriched imdb card`() {
        // The instant Continue Watching seed builds cards with no resolved
        // TMDB id, so its "tmdb:<n>" parent id is the only key it can offer.
        // The enriched card resolves the same number, which is what merges
        // the two instead of leaving the snapshot's twin on the rail.
        val snapshot = card(parentId = "tmdb:97546", tmdbId = null)
        val enriched = card(parentId = "tt0898266", tmdbId = 97546)

        assertEquals(
            1,
            clusterByIdentityKeys(listOf(snapshot, enriched), ::upNextGroupingKeys).size
        )
    }

    @Test
    fun `unrelated shows stay apart and keep their order`() {
        val paw = card(parentId = "tt0898266", tmdbId = 97546)
        val bluey = card(
            parentId = "tt7678620",
            title = "Bluey",
            tmdbId = 82728
        )
        val pawTwin = card(parentId = "tmdb:97546", tmdbId = 97546)

        val clusters =
            clusterByIdentityKeys(listOf(paw, bluey, pawTwin), ::upNextGroupingKeys)

        assertEquals(2, clusters.size)
        assertEquals(listOf(paw, pawTwin), clusters[0])
        assertEquals(listOf(bluey), clusters[1])
    }

    @Test
    fun `clustering an empty list is empty`() {
        assertEquals(
            emptyList<List<UpNextItem>>(),
            clusterByIdentityKeys(emptyList(), ::upNextGroupingKeys)
        )
    }

    // ── the Upcoming rail ────────────────────────────────────────────

    @Test
    fun `one show with a next episode yields one Upcoming row`() {
        val local = card(parentId = "tt0898266", tmdbId = 97546)
        val simklTwin = card(parentId = "tmdb:97546", tmdbId = 97546)

        val rows = selectUpcomingPerShow(
            listOf(
                local to upcomingRow(local, airDateEpochMs = 9_000L),
                simklTwin to upcomingRow(
                    simklTwin,
                    airDateEpochMs = 5_000L,
                    episodeTitle = "Pups Save the Day"
                )
            )
        )

        assertEquals(1, rows.size)
        // The earliest date survived the second-source correction, and it is
        // the row that knows the episode title.
        assertEquals(5_000L, rows.single().airDateEpochMs)
        assertEquals("Pups Save the Day", rows.single().episodeTitle)
    }

    @Test
    fun `different shows each keep their own Upcoming row`() {
        val paw = card(parentId = "tt0898266", tmdbId = 97546)
        val bluey = card(parentId = "tt7678620", title = "Bluey", tmdbId = 82728)

        val rows = selectUpcomingPerShow(
            listOf(
                paw to upcomingRow(paw, airDateEpochMs = 5_000L),
                bluey to upcomingRow(bluey, airDateEpochMs = 7_000L)
            )
        )

        assertEquals(2, rows.size)
    }

    @Test
    fun `the fallback title never swallows a show the ids keep apart`() {
        // Same name, different ids: two entries, not one.
        val original = card(
            parentId = "tt0087332",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 620,
            season = null,
            episode = null
        )
        val remake = card(
            parentId = "tt1289401",
            title = "Ghostbusters",
            parentType = "movie",
            tmdbId = 43074,
            season = null,
            episode = null
        )

        val rows = selectUpcomingPerShow(
            listOf(
                original to upcomingRow(original, airDateEpochMs = 5_000L),
                remake to upcomingRow(remake, airDateEpochMs = 6_000L)
            )
        )

        assertEquals(2, rows.size)
        assertFalse(rows[0].parentId == rows[1].parentId)
    }

    // ── the instant snapshot seed ────────────────────────────────────

    @Test
    fun `two flavors of one show collapse to the newest snapshot card`() {
        // The seed's SQL groups by the raw parent id, so the show's older
        // imdb-flavored row and its newer tmdb-flavored row both arrive -
        // and neither has a resolved TMDB id to pair them with yet.
        val older = card(
            parentId = "tt0898266",
            season = 1,
            episode = 5,
            startPositionMs = 60_000L,
            recencyTimestamp = 1_000L
        )
        val newer = card(
            parentId = "tmdb:97546",
            season = 3,
            episode = 15,
            startPositionMs = 90_000L,
            recencyTimestamp = 2_000L
        )

        val collapsed = collapseInstantSnapshotItems(listOf(older, newer))

        assertEquals(1, collapsed.size)
        assertEquals(newer.parentId, collapsed.single().parentId)
        assertEquals(15, collapsed.single().episode)
    }

    @Test
    fun `a same-named movie and series stay separate in the seed`() {
        val series = card(
            parentId = "tmdb:1",
            title = "Ghostbusters",
            parentType = "series"
        )
        val movie = card(
            parentId = "tt0087332",
            title = "Ghostbusters",
            parentType = "movie",
            season = null,
            episode = null
        )

        assertEquals(
            2,
            collapseInstantSnapshotItems(listOf(series, movie)).size
        )
    }

    @Test
    fun `different shows are untouched and keep their order`() {
        val paw = card(parentId = "tt0898266", recencyTimestamp = 2_000L)
        val bluey = card(
            parentId = "tt7678620",
            title = "Bluey",
            recencyTimestamp = 1_000L
        )

        val collapsed = collapseInstantSnapshotItems(listOf(paw, bluey))

        assertEquals(listOf(paw, bluey), collapsed)
    }
}
