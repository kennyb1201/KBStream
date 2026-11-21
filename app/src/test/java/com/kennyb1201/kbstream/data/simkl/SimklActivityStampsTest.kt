package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delta that replaced Simkl's one-boolean activity gate (spec 2026-11-21,
 * rule 5).
 *
 * `SimklActivitiesResponse` used to be read through `all` alone, so any change
 * anywhere re-synced every marker set. The rules here decide which half moved;
 * the cost of getting them wrong is asymmetric, which is why the fallback is
 * always "fetch": a missed change leaves checkmarks wrong with nothing to
 * correct them, while an extra fetch is one read.
 */
class SimklActivityStampsTest {

    private fun stamps(
        all: String = "T10",
        movies: String? = "M10",
        moviesCompleted: String? = "MC10",
        shows: String? = "S10",
        showsCompleted: String? = "SC10",
        showsWatching: String? = "SW10",
        anime: String? = null,
        animeCompleted: String? = null,
        animeWatching: String? = null
    ) = SimklActivityStamps(
        all = all,
        movies = movies,
        moviesCompleted = moviesCompleted,
        shows = shows,
        showsCompleted = showsCompleted,
        showsWatching = showsWatching,
        anime = anime,
        animeCompleted = animeCompleted,
        animeWatching = animeWatching
    )

    @Test
    fun `the stamps round-trip through the stored form`() {
        val saved = stamps(anime = "A10", animeCompleted = "AC10", animeWatching = null)

        val parsed = SimklActivityStamps.parse(saved.wire())

        assertEquals(saved, parsed)
        assertNull("a record this build did not write is not read", SimklActivityStamps.parse("x"))
        assertNull(SimklActivityStamps.parse(null))
    }

    @Test
    fun `an unchanged account moves neither half`() {
        val changes = stamps().changesSince(stamps())

        assertFalse(changes.movies)
        assertFalse(changes.shows)
        assertFalse(changes.any)
    }

    @Test
    fun `a movie-only change fetches only the movie set`() {
        val changes = stamps(
            all = "T11",
            movies = "M11",
            moviesCompleted = "MC11"
        ).changesSince(stamps())

        assertTrue("the completed-movies stamp moved", changes.movies)
        assertFalse("every show stamp is identical, so no show read is owed", changes.shows)
    }

    @Test
    fun `a show-only change fetches only the show sets`() {
        val changes = stamps(
            all = "T12",
            shows = "S12",
            showsWatching = "SW12"
        ).changesSince(stamps())

        assertFalse(changes.movies)
        assertTrue(changes.shows)
    }

    @Test
    fun `an anime change counts as a show change`() {
        // The all-shows read covers anime, so an anime stamp moving is a show
        // read - and only a show read.
        val changes = stamps(
            all = "T13",
            anime = "A13"
        ).changesSince(stamps(anime = "A12"))

        assertFalse(changes.movies)
        assertTrue(changes.shows)
    }

    @Test
    fun `no saved record means both halves`() {
        val changes = stamps().changesSince(null)

        assertTrue(changes.movies)
        assertTrue(changes.shows)
    }

    @Test
    fun `a payload without section blocks falls back to the account stamp`() {
        val bare = SimklActivityStamps(all = "T14")
        val saved = SimklActivityStamps(all = "T13")

        val changes = bare.changesSince(saved)

        assertTrue("this build cannot tell which half moved, so it fetches both", changes.any)
        assertFalse(
            "and an identical account stamp still answers neither, which is the " +
                "cheapest gate the endpoint exists for",
            bare.changesSince(SimklActivityStamps(all = "T14")).any
        )
    }
}
