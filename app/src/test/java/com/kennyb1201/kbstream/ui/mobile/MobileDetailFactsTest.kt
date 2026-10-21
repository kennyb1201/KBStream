package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbMovieReleaseDateEntry
import com.kennyb1201.kbstream.data.tmdb.TmdbMovieReleaseDates
import com.kennyb1201.kbstream.data.tmdb.TmdbMovieReleaseDatesResult
import com.kennyb1201.kbstream.data.tmdb.TmdbTvContentRating
import com.kennyb1201.kbstream.data.tmdb.TmdbTvContentRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Movie Details / Show Details block ([detailFacts]), which is the
 * one part of that section a JVM test can check without a compose harness: the
 * rows, their order, and the wording of each value.
 *
 * The values come from the shared `TmdbDetail` helpers the TV meta line reads,
 * so what is pinned here is that the phone asks for the same facts and drops
 * the ones TMDB did not send - never that it invents a placeholder.
 */
class MobileDetailFactsTest {

    private fun movie(
        releaseDate: String? = "2026-03-06",
        runtime: Int? = 107,
        status: String? = "Released",
        certification: String? = "R",
        origin: List<String> = listOf("US", "AU", "GB")
    ) = TmdbDetail(
        id = 1,
        title = "By Any Means",
        releaseDate = releaseDate,
        runtime = runtime,
        status = status,
        originCountries = origin,
        releaseDates = certification?.let { rating ->
            TmdbMovieReleaseDates(
                results = listOf(
                    TmdbMovieReleaseDatesResult(
                        iso31661 = "US",
                        releaseDates = listOf(
                            TmdbMovieReleaseDateEntry(certification = rating)
                        )
                    )
                )
            )
        }
    )

    private fun series(
        status: String? = "Returning Series",
        firstAirDate: String? = "2022-09-13",
        lastAirDate: String? = "2024-01-04",
        episodeRuntime: List<Int> = listOf(26),
        certification: String? = "TV-MA",
        origin: List<String> = listOf("JP")
    ) = TmdbDetail(
        id = 2,
        name = "Cyberpunk: Edgerunners",
        firstAirDate = firstAirDate,
        lastEpisodeToAir = lastAirDate?.let { TmdbEpisodeAirInfo(airDate = it) },
        episodeRunTime = episodeRuntime,
        status = status,
        originCountries = origin,
        contentRatings = certification?.let { rating ->
            TmdbTvContentRatings(
                results = listOf(TmdbTvContentRating(iso31661 = "US", rating = rating))
            )
        }
    )

    @Test
    fun `a film's block is its movie details, in the reference's row order`() {
        val facts = detailFacts("movie", movie())

        assertEquals("Movie Details", detailFactsTitle(isMovie = true))
        assertEquals(
            listOf("Release Info", "Runtime", "Certification", "Origin Country"),
            facts.map { it.label }
        )
        assertEquals("2026", facts.first { it.label == "Release Info" }.value)
        assertEquals("1h 47m", facts.first { it.label == "Runtime" }.value)
        assertEquals("R", facts.first { it.label == "Certification" }.value)
        // A film has no Status row: nothing on that line is something a viewer
        // acts on, and the reference has never shown it for a movie.
        assertTrue(facts.none { it.label == "Status" })
    }

    @Test
    fun `a running series says Ongoing and leaves its span open`() {
        val facts = detailFacts("series", series())

        assertEquals("Show Details", detailFactsTitle(isMovie = false))
        assertEquals(
            listOf("Status", "Release Info", "Runtime", "Certification", "Origin Country"),
            facts.map { it.label }
        )
        assertEquals("Ongoing", facts.first { it.label == "Status" }.value)
        assertEquals("2022-", facts.first { it.label == "Release Info" }.value)
        assertEquals("26m", facts.first { it.label == "Runtime" }.value)
        assertEquals("TV-MA", facts.first { it.label == "Certification" }.value)
    }

    @Test
    fun `a finished series closes its span on the last aired year`() {
        val facts = detailFacts("series", series(status = "Ended", lastAirDate = "2023-05-05"))
        assertEquals("Ended", facts.first { it.label == "Status" }.value)
        assertEquals("2022-2023", facts.first { it.label == "Release Info" }.value)
    }

    @Test
    fun `rows TMDB cannot fill are dropped, not printed empty`() {
        val bare = detailFacts(
            "movie",
            movie(runtime = null, certification = null, origin = emptyList())
        )
        assertEquals(listOf("Release Info"), bare.map { it.label })
        assertEquals("2026", bare.single().value)

        // Nothing at all: no block, which is what the caller tests before
        // drawing the heading.
        assertTrue(detailFacts("movie", null).isEmpty())
        assertTrue(detailFacts("movie", TmdbDetail(id = 3)).isEmpty())
    }

    @Test
    fun `an unknown or missing status is passed through, never invented`() {
        assertNull(seriesStatusLabel(null))
        assertNull(seriesStatusLabel("   "))
        assertEquals("Pilot", seriesStatusLabel("Pilot"))
        assertEquals("In production", seriesStatusLabel("In Production"))
        assertEquals("Something New", seriesStatusLabel("Something New"))
        assertTrue(
            "no Status row when the API sent none",
            detailFacts("series", series(status = null)).none { it.label == "Status" }
        )
    }

    @Test
    fun `origin countries become names, capped at three`() {
        val names = originCountryNames(listOf("US", "AU", "GB", "CA"))
        assertEquals(3, names?.split(", ")?.size)
        assertTrue("the codes are names, not codes", names!!.contains("United States"))
        assertNull(originCountryNames(emptyList()))
        assertNull(originCountryNames(listOf("", "  ")))

        // An unparseable code is echoed rather than dropped.
        assertEquals("ZZTOP", countryDisplayName("ZZTOP"))
        assertNotEquals("US", countryDisplayName("US"))
        // Duplicates are one country, not two.
        assertEquals("Japan", originCountryNames(listOf("JP", "JP")))
    }
}
