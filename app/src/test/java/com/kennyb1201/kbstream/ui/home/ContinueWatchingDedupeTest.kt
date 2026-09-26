package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue Watching keeps ONE card per show.
 *
 * Reported bug: a show the user was part-way through showed up twice - once
 * as the paused episode ("Resume S4E5", from the local resume row keyed by
 * the show's imdb id) and once as the tracker's suggestion for the episode
 * that had just aired ("New Episode S4E6", keyed by the show's tmdb id). The
 * id flavors disagreed, so the show-level dedupe could not see they were the
 * same show.
 */
class ContinueWatchingDedupeTest {

    private fun resumeCard(
        parentId: String = "tt10986410",
        title: String = "Ted Lasso",
        parentType: String = "series",
        season: Int = 4,
        episode: Int = 5
    ): UpNextItem = UpNextItem(
        id = "history:$parentId:$season:$episode",
        title = title,
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        subtitle = "Resume - S${season}E$episode",
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        startPositionMs = 15L * 60L * 1000L,
        progressPercent = 0.4f,
        historyRowId = "$parentId:$season:$episode",
        recencyTimestamp = 1_000L
    )

    private fun suggestionCard(
        parentId: String,
        badge: UpNextBadge,
        title: String = "Ted Lasso",
        parentType: String = "series",
        season: Int = 4,
        episode: Int = 6
    ): UpNextItem = UpNextItem(
        id = "simkl:show-12345",
        title = title,
        poster = null,
        badge = badge,
        subtitle = "New Episode - S${season}E$episode",
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        recencyTimestamp = 2_000L
    )

    @Test
    fun `a paused episode hides the new-episode suggestion for the same show`() {
        val items = listOf(
            suggestionCard("tmdb:97546", UpNextBadge.NEW_EPISODE),
            resumeCard()
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(1, result.size)
        assertEquals(UpNextBadge.CONTINUE_WATCHING, result.single().badge)
        assertEquals(5, result.single().episode)
    }

    @Test
    fun `the id flavors do not matter`() {
        // The resume row's parent id is the imdb id, the tracker card's is the
        // tmdb id - same show, so still one card.
        val items = listOf(
            resumeCard(parentId = "tt10986410"),
            suggestionCard("tmdb:97546", UpNextBadge.NEXT_UP)
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(1, result.size)
        assertEquals(UpNextBadge.CONTINUE_WATCHING, result.single().badge)
    }

    @Test
    fun `an identical id collapses to the resume card too`() {
        val items = listOf(
            resumeCard(),
            suggestionCard("tt10986410", UpNextBadge.NEW_SEASON)
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(1, result.size)
        assertEquals(UpNextBadge.CONTINUE_WATCHING, result.single().badge)
    }

    @Test
    fun `a new-episode suggestion survives when nothing is in progress`() {
        // Caught up with the show: the new episode IS the thing to watch.
        val items = listOf(
            suggestionCard("tmdb:97546", UpNextBadge.NEW_EPISODE)
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(1, result.size)
        assertEquals(UpNextBadge.NEW_EPISODE, result.single().badge)
    }

    @Test
    fun `another show's suggestion is untouched`() {
        val items = listOf(
            resumeCard(),
            suggestionCard(
                parentId = "tt1234567",
                badge = UpNextBadge.NEW_EPISODE,
                title = "Slow Horses"
            )
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(2, result.size)
        assertTrue(result.any { it.title == "Ted Lasso" })
        assertTrue(result.any { it.title == "Slow Horses" })
    }

    @Test
    fun `a movie with the same name is not swallowed`() {
        val items = listOf(
            resumeCard(),
            suggestionCard(
                parentId = "tmdb:9",
                badge = UpNextBadge.NEW_EPISODE,
                title = "Ted Lasso",
                parentType = "movie"
            )
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(2, result.size)
    }

    @Test
    fun `a paused tracker session also supersedes the suggestion`() {
        // Nothing local, but a paused session on another device: the card
        // carries progress instead of a history row id.
        val pausedElsewhere = suggestionCard(
            parentId = "tmdb:97546",
            badge = UpNextBadge.CONTINUE_WATCHING
        ).copy(startPositionMs = 0L, progressPercent = 0.62f)

        val items = listOf(
            pausedElsewhere,
            suggestionCard("tt10986410", UpNextBadge.NEW_EPISODE)
        )

        val result = collapseDuplicateUpNextCards(items)

        assertEquals(1, result.size)
        assertEquals(0.62f, result.single().progressPercent ?: 0f, 0.0001f)
    }

    @Test
    fun `the same episode keyed two ways collapses to the local card`() {
        val localRow = resumeCard()
        val remoteTwin = suggestionCard(
            parentId = "tmdb:97546",
            badge = UpNextBadge.CONTINUE_WATCHING,
            season = 4,
            episode = 5
        ).copy(startPositionMs = 0L, progressPercent = 0.5f)

        val result = collapseDuplicateUpNextCards(listOf(localRow, remoteTwin))

        assertEquals(1, result.size)
        assertEquals(localRow.id, result.single().id)
    }

    @Test
    fun `a later episode paused on another device keeps its own card`() {
        // Two real resume points (S4E5 here, S4E6 paused elsewhere) are not
        // the same thing to continue, so both stay on the rail.
        val items = listOf(
            resumeCard(),
            suggestionCard(
                parentId = "tmdb:97546",
                badge = UpNextBadge.CONTINUE_WATCHING,
                season = 4,
                episode = 6
            ).copy(startPositionMs = 0L, progressPercent = 0.3f)
        )

        assertEquals(2, collapseDuplicateUpNextCards(items).size)
    }

    @Test
    fun `show key prefers the parent id over the title`() {
        assertEquals(
            "parent:series:tt10986410",
            upNextShowKey(resumeCard())
        )
        assertEquals(
            "parent:series:97546",
            upNextShowKey(suggestionCard("tmdb:97546", UpNextBadge.NEW_EPISODE))
        )
        assertEquals(
            "title:series:some show",
            upNextShowKey(
                suggestionCard(
                    parentId = "",
                    badge = UpNextBadge.NEXT_UP,
                    title = "Some Show"
                )
            )
        )
    }

    // ── a card whose enrichment failed ──────────────────────────────

    @Test
    fun `a card labelled with its raw id collapses onto the real one`() {
        // Reported bug: a show appeared twice in Continue Watching - one card
        // with artwork, and one with no thumbnail whose title was the raw
        // TMDB id. The twin's enrichment had failed, so it had no title to
        // match on either, and the two id flavours ("tt..." vs "tmdb:...")
        // never collided on their own. The resolved TMDB id both cards carry
        // is what pairs them.
        val localRow = resumeCard().copy(
            tmdbId = 97546
        )
        val brokenTwin = suggestionCard(
            parentId = "tmdb:97546",
            badge = UpNextBadge.CONTINUE_WATCHING,
            season = 4,
            episode = 5
        ).copy(
            title = "tmdb:97546",
            tmdbId = 97546,
            startPositionMs = 0L,
            progressPercent = 0.5f
        )

        val result = collapseDuplicateUpNextCards(listOf(localRow, brokenTwin))

        assertEquals(1, result.size)
        assertEquals(localRow.id, result.single().id)
    }

    @Test
    fun `the resolved tmdb id does not merge two different shows`() {
        // The widening must not swallow a genuinely different show that only
        // shares the local card's tmdb id namespace.
        val localRow = resumeCard().copy(
            tmdbId = 97546
        )
        val otherShow = suggestionCard(
            parentId = "tt00000001",
            badge = UpNextBadge.CONTINUE_WATCHING,
            title = "Breeders",
            season = 4,
            episode = 5
        ).copy(
            tmdbId = 11111,
            startPositionMs = 0L,
            progressPercent = 0.5f
        )

        assertEquals(
            2,
            collapseDuplicateUpNextCards(listOf(localRow, otherShow)).size
        )
    }

    @Test
    fun `identity keys name every id form the card carries`() {
        val keys = upNextIdentityKeys(
            resumeCard().copy(tmdbId = 97546)
        )

        assertEquals(
            setOf(
                "parent:series:tt10986410",
                "parent:series:97546",
                "title:series:ted lasso"
            ),
            keys
        )
    }

    @Test
    fun `episode keys keep one episode apart from the next`() {
        val fifth = upNextEpisodeKeys(
            resumeCard().copy(tmdbId = 97546)
        )
        val sixth = upNextEpisodeKeys(
            resumeCard().copy(tmdbId = 97546, episode = 6)
        )

        assertTrue(fifth.contains("parent:series:97546:4:5"))
        assertEquals(emptySet<String>(), fifth intersect sixth)
    }
}
