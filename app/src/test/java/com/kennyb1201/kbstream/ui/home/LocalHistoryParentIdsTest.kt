package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local-history ids a show's watch state is read under.
 *
 * The reported failure was a Continue Watching card reading "season 1 episode
 * 1" for a show whose episodes were ticked off on Detail. Both screens read the
 * same local history, but Detail reads every id flavor the title is reachable
 * under while Continue Watching read only the flavor its row was keyed by -
 * and playback writes history under whichever flavor started it ("tt..." from
 * an add-on catalog, "tmdb:<n>" from search or a kids rail). Reading the row's
 * own flavor alone found no completed episode at all, so the show resolved to
 * its first unwatched episode and the card said S1E1.
 *
 * The rule lives in HomeUpNext.kt (its twin is
 * DetailViewModel.localHistoryParentIds) because the failure is silent: it
 * shows up as a plausible-looking episode number on a television, never as an
 * error.
 */
class LocalHistoryParentIdsTest {

    @Test
    fun `an imdb row also reads the tmdb flavor`() {
        assertEquals(
            listOf("tt12345", "tmdb:4567"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = 4567
            )
        )
    }

    @Test
    fun `a tmdb row also reads the imdb flavor`() {
        // The other direction: opened from search under "tmdb:4567" while the
        // episodes were played from an add-on catalog under "tt12345".
        assertEquals(
            listOf("tmdb:4567", "tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tmdb:4567",
                tmdbShowId = 4567,
                imdbId = "tt12345"
            )
        )
    }

    @Test
    fun `the row's own id comes first and is never duplicated`() {
        // getResumeForParents takes the newest row across the flavors; the
        // row's own id has to stay in the list exactly once.
        assertEquals(
            listOf("tmdb:4567", "tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tmdb:4567",
                tmdbShowId = 4567,
                imdbId = "tt12345"
            )
        )

        // The row is already keyed by the tmdb flavor the id resolves to, so
        // that flavor appears once - not as "tmdb:4567" twice.
        assertEquals(
            listOf("tmdb:4567"),
            localHistoryParentIdsForShow(
                parentId = "tmdb:4567",
                tmdbShowId = 4567
            )
        )
    }

    @Test
    fun `an unresolved tmdb id adds no flavor`() {
        // A synthetic add-on video carries a -1 sentinel: it must never become
        // a fake "tmdb:-1" key that could match another title's rows.
        assertEquals(
            listOf("tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = -1
            )
        )

        assertEquals(
            listOf("tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = null
            )
        )

        assertEquals(
            listOf("tt12345"),
            localHistoryParentIdsForShow(
                parentId = "tt12345",
                tmdbShowId = 0
            )
        )
    }

    @Test
    fun `an imdb id that is not one is ignored`() {
        // resolveImdbId surfaces whatever the network returned; only a real
        // "tt..." value may be added to the read set.
        assertEquals(
            listOf("tmdb:4567"),
            localHistoryParentIdsForShow(
                parentId = "tmdb:4567",
                tmdbShowId = 4567,
                imdbId = "  "
            )
        )

        assertEquals(
            listOf("tmdb:4567"),
            localHistoryParentIdsForShow(
                parentId = "tmdb:4567",
                tmdbShowId = 4567,
                imdbId = "1234567"
            )
        )

        // Padding around a real id is trimmed rather than rejected.
        assertTrue(
            "tt12345" in
                localHistoryParentIdsForShow(
                    parentId = "tmdb:4567",
                    tmdbShowId = 4567,
                    imdbId = " tt12345 "
                )
        )
    }

    @Test
    fun `a blank row id reads nothing`() {
        // Reads are keyed by parentId: an empty one has no rows to find and
        // must not produce a "tmdb:<n>" query on its own.
        assertTrue(
            localHistoryParentIdsForShow(
                parentId = "   ",
                tmdbShowId = 4567
            ).isEmpty()
        )
    }
}
