package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_MOVIE
import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_TV
import com.kennyb1201.kbstream.data.catalogs.CatalogSort
import com.kennyb1201.kbstream.data.catalogs.CustomCatalog
import com.kennyb1201.kbstream.data.kb.KBFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder's genre/media-type rule and its chip catalog.
 *
 * The one that matters most is the media-type prune: a series catalog that keeps
 * a MOVIE genre id asks /discover/tv for a genre it does not have, and an empty
 * rail is what the viewer sees - not an error. The rest pins that the chips the
 * builder offers are real, distinct ids from the Browse catalog rather than a
 * second hand-kept list that can rot away from it.
 */
class CatalogBuilderModelsTest {

    // ------------------------------------------------------ genre per type --

    @Test
    fun `the genre sets are disjoint where tmdb splits them`() {
        // Action (28) / Adventure (12) / Sci-Fi (878) are movie genres; TV
        // splits them into 10759 / 10765. Offering all of them for both types
        // is the bug this rule exists to prevent.
        assertTrue(28 in CATALOG_MOVIE_GENRE_IDS)
        assertFalse(28 in CATALOG_TV_GENRE_IDS)
        assertTrue(10759 in CATALOG_TV_GENRE_IDS)
        assertFalse(10759 in CATALOG_MOVIE_GENRE_IDS)
        assertTrue(10765 in CATALOG_TV_GENRE_IDS)
        assertFalse(878 in CATALOG_TV_GENRE_IDS)
        assertTrue(878 in CATALOG_MOVIE_GENRE_IDS)
    }

    @Test
    fun `shared genres belong to both types`() {
        listOf(35, 80, 18, 9648, 37).forEach { id ->
            assertTrue("$id must be a movie genre", id in CATALOG_MOVIE_GENRE_IDS)
            assertTrue("$id must be a TV genre", id in CATALOG_TV_GENRE_IDS)
        }
    }

    @Test
    fun `the chips offered are exactly the legal ids for the type`() {
        val movieChips = catalogGenreOptions(CATALOG_MEDIA_MOVIE)
        val tvChips = catalogGenreOptions(CATALOG_MEDIA_TV)

        assertTrue(movieChips.isNotEmpty())
        assertTrue(tvChips.isNotEmpty())
        movieChips.forEach { option ->
            assertTrue("movie chip ${option.id} is not a legal movie genre", option.id in CATALOG_MOVIE_GENRE_IDS)
        }
        tvChips.forEach { option ->
            assertTrue("TV chip ${option.id} is not a legal TV genre", option.id in CATALOG_TV_GENRE_IDS)
        }
        assertFalse(
            "a movie-only genre must not be offered for series",
            tvChips.any { it.id == 28 }
        )
        assertFalse(
            "a TV-only genre must not be offered for movies",
            movieChips.any { it.id == 10759 }
        )
    }

    @Test
    fun `every genre id the builder can offer has a label`() {
        (CATALOG_MOVIE_GENRE_IDS + CATALOG_TV_GENRE_IDS).forEach { id ->
            val label = catalogGenreLabel(id)
            assertTrue("genre $id has no display name", label != null && label.isNotBlank())
        }
    }

    // ------------------------------------------------------ media switching --

    @Test
    fun `switching to series prunes movie-only genres`() {
        val movie = CustomCatalog(
            id = "c",
            name = "Actions",
            mediaType = CATALOG_MEDIA_MOVIE,
            filters = KBFilters(
                withGenres = "28,35",
                withoutGenres = "878"
            )
        )

        val series = withCatalogMediaType(movie, CATALOG_MEDIA_TV)

        assertEquals("series", series.railType)
        assertEquals(
            "only the shared genre survives",
            "35",
            series.filters.withGenres
        )
        assertNull(
            "a movie-only excluded genre is dropped outright",
            series.filters.withoutGenres
        )
    }

    @Test
    fun `switching back to movies keeps what is still legal`() {
        val series = CustomCatalog(
            id = "c",
            name = "Series",
            mediaType = CATALOG_MEDIA_TV,
            filters = KBFilters(withGenres = "10759,18,10765")
        )

        val movie = withCatalogMediaType(series, CATALOG_MEDIA_MOVIE)

        assertEquals("18", movie.filters.withGenres)
    }

    @Test
    fun `switching to the same type changes nothing at all`() {
        val catalog = CustomCatalog(
            id = "c",
            name = "n",
            mediaType = CATALOG_MEDIA_TV,
            filters = KBFilters(withGenres = "28")
        )
        assertTrue(
            "a no-op switch must not rewrite the rules",
            catalog === withCatalogMediaType(catalog, CATALOG_MEDIA_TV)
        )
    }

    @Test
    fun `pruning leaves unrelated filters alone`() {
        val pruned = pruneGenreFilters(
            KBFilters(
                withGenres = "28,35",
                withWatchProviders = "8",
                voteAverageGte = 7,
                year = "1990-1999",
                withOriginalLanguage = "en"
            ),
            CATALOG_MEDIA_TV
        )

        assertEquals("35", pruned.withGenres)
        assertEquals("8", pruned.withWatchProviders)
        assertEquals(7, pruned.voteAverageGte)
        assertEquals("1990-1999", pruned.year)
        assertEquals("en", pruned.withOriginalLanguage)
    }

    // ------------------------------------------------------------ chip lists --

    @Test
    fun `service chips all carry a watch-provider id`() {
        assertTrue(CATALOG_SERVICE_OPTIONS.isNotEmpty())
        assertEquals(
            "provider ids must not repeat",
            CATALOG_SERVICE_OPTIONS.size,
            CATALOG_SERVICE_OPTIONS.map { it.id }.toSet().size
        )
        assertEquals(
            "services are the provider entries that have one, one chip per provider",
            CATALOG_SERVICE_OPTIONS.size,
            com.kennyb1201.kbstream.ui.search.BROWSE_PROVIDER_ENTRIES
                .mapNotNull { it.providerId }
                .toSet()
                .size
        )
    }

    @Test
    fun `network chips are the entries with no provider id`() {
        assertTrue(CATALOG_NETWORK_OPTIONS.isNotEmpty())
        assertEquals(
            CATALOG_NETWORK_OPTIONS.size,
            CATALOG_NETWORK_OPTIONS.map { it.id }.toSet().size
        )
        assertEquals(
            "networks are the entries with no provider id, one chip per network",
            CATALOG_NETWORK_OPTIONS.size,
            com.kennyb1201.kbstream.ui.search.BROWSE_PROVIDER_ENTRIES
                .filter { it.providerId == null && !it.networkIsCompany }
                .mapNotNull { it.networkOrCompanyId }
                .toSet()
                .size
        )
    }

    @Test
    fun `studio and decade chips mirror the browse catalog`() {
        assertEquals(
            com.kennyb1201.kbstream.ui.search.BROWSE_STUDIOS.size,
            CATALOG_STUDIO_OPTIONS.size
        )
        assertEquals(
            com.kennyb1201.kbstream.ui.search.BROWSE_DECADES.size,
            CATALOG_DECADE_OPTIONS.size
        )
        assertTrue(CATALOG_DECADE_OPTIONS.all { it.label.endsWith("s") })
    }

    @Test
    fun `language, country and region codes are distinct and well formed`() {
        listOf(
            CATALOG_LANGUAGE_OPTIONS,
            CATALOG_COUNTRY_OPTIONS,
            CATALOG_REGION_OPTIONS
        ).forEach { options ->
            assertTrue(options.isNotEmpty())
            assertEquals(options.size, options.map { it.code }.toSet().size)
            assertTrue(options.all { it.code.isNotBlank() && it.label.isNotBlank() })
        }
        assertTrue("language codes are ISO 639-1", CATALOG_LANGUAGE_OPTIONS.all { it.code.length == 2 })
        assertTrue("country codes are ISO 3166-1", CATALOG_COUNTRY_OPTIONS.all { it.code.length == 2 })
        assertTrue(CATALOG_REGION_OPTIONS.any { it.code == "US" })
    }

    @Test
    fun `every filter option row offers a way back to no filter`() {
        listOf(CATALOG_MIN_RATING_OPTIONS, CATALOG_MAX_RATING_OPTIONS, CATALOG_MIN_VOTE_OPTIONS)
            .forEach { options ->
                assertEquals("the Any chip is first", 0, options.first())
                assertEquals(options.size, options.toSet().size)
                assertTrue(options.drop(1).all { it > 0 })
            }
        assertEquals(0, CATALOG_YEAR_BOUND_OPTIONS.first())
    }

    @Test
    fun `the sort chips are every CatalogSort`() {
        assertEquals(CatalogSort.entries.size, CATALOG_SORT_OPTIONS.size)
        assertEquals(CatalogSort.entries.toList(), CATALOG_SORT_OPTIONS)
    }

    // ------------------------------------------------------------- summary --

    @Test
    fun `the rule count counts each populated dimension once`() {
        assertEquals(0, catalogRuleCount(KBFilters()))
        assertEquals(1, catalogRuleCount(KBFilters(withGenres = "28")))
        assertEquals(
            "one dimension with three ids is still one rule",
            1,
            catalogRuleCount(KBFilters(withGenres = "28,12,35"))
        )
        assertEquals(
            "every populated dimension counts",
            6,
            catalogRuleCount(
                KBFilters(
                    withGenres = "28",
                    withWatchProviders = "8",
                    voteAverageGte = 7,
                    voteCountGte = 100,
                    year = "1990-1999",
                    withOriginalLanguage = "en"
                )
            )
        )
        assertEquals(
            "release bounds count too",
            2,
            catalogRuleCount(
                KBFilters(releaseDateGte = "2015-01-01", releaseDateLte = "2020-12-31")
            )
        )
        assertEquals(
            "a blank value is not a rule",
            0,
            catalogRuleCount(KBFilters(withGenres = " ", voteCountGte = 0))
        )
    }

    @Test
    fun `the summary names the type, the sort and how narrow it is`() {
        assertEquals(
            "Movies \u00b7 Popularity \u00b7 no filters",
            catalogSummaryLine(CustomCatalog(id = "c", name = "n"))
        )
        assertEquals(
            "Series \u00b7 Newest \u00b7 1 filter",
            catalogSummaryLine(
                CustomCatalog(
                    id = "c",
                    name = "n",
                    mediaType = CATALOG_MEDIA_TV,
                    sort = CatalogSort.NEWEST.id,
                    filters = KBFilters(withGenres = "10759")
                )
            )
        )
        assertNotEquals(
            "the count is plural-aware",
            catalogSummaryLine(
                CustomCatalog(id = "c", name = "n", filters = KBFilters(withGenres = "28"))
            ),
            catalogSummaryLine(
                CustomCatalog(
                    id = "c",
                    name = "n",
                    filters = KBFilters(withGenres = "28", voteCountGte = 100)
                )
            )
        )
    }

    @Test
    fun `the genre summary names the selected genres and nothing else`() {
        assertEquals("Action", catalogGenreSummary(KBFilters(withGenres = "28")))
        assertEquals("Action, Comedy", catalogGenreSummary(KBFilters(withGenres = "28,35")))
        assertEquals("", catalogGenreSummary(KBFilters()))
        assertEquals(
            "an unknown id is skipped rather than shown as a number",
            "",
            catalogGenreSummary(KBFilters(withGenres = "999999"))
        )
        assertEquals(
            "excluded genres are not part of the name line",
            "",
            catalogGenreSummary(KBFilters(withoutGenres = "28"))
        )
    }

    // ------------------------------------------------ the post-genre filters --

    @Test
    fun `the age-rating scales do not cross media types`() {
        // "TV-14" is not a movie rating and "NC-17" is not a TV one: asking the
        // wrong endpoint for either answers an empty page, which reads as a
        // broken catalog rather than as a rule mismatch.
        val movie = catalogCertificationOptions(CATALOG_MEDIA_MOVIE).map { it.code }
        val tv = catalogCertificationOptions(CATALOG_MEDIA_TV).map { it.code }

        assertTrue("PG-13" in movie)
        assertFalse("TV-14" in movie)
        assertTrue("TV-14" in tv)
        assertFalse("R" in tv)
        assertTrue(isCatalogCertification(CATALOG_MEDIA_MOVIE, "R"))
        assertFalse(isCatalogCertification(CATALOG_MEDIA_MOVIE, "TV-MA"))
        assertTrue(isCatalogCertification(CATALOG_MEDIA_TV, "TV-MA"))
        assertFalse(isCatalogCertification(CATALOG_MEDIA_TV, null))
    }

    @Test
    fun `a media-type switch drops the rules the new type cannot answer`() {
        // /discover/tv has no person filter and /discover/movie has no status,
        // type or network filter, so each swap has to clear its own half.
        val seriesRules = KBFilters(
            withCast = "31",
            withStatus = "0",
            withType = "4",
            withoutNetworks = "213"
        )
        val asMovie = pruneMediaTypeFilters(seriesRules, CATALOG_MEDIA_MOVIE)
        assertEquals("31", asMovie.withCast)
        assertNull("movie discover has no status", asMovie.withStatus)
        assertNull(asMovie.withType)
        assertNull(asMovie.withoutNetworks)

        // ...and the prune is ONE-WAY: switching back does not resurrect the
        // rules the previous type could not express, because the viewer saw
        // them leave the row. A TV catalog that still carries its own shape
        // rules keeps them untouched.
        val asSeries = pruneMediaTypeFilters(
            KBFilters(withCast = "31", withStatus = "0"),
            CATALOG_MEDIA_TV
        )
        assertNull("tv discover has no person filter", asSeries.withCast)
        assertEquals("a tv-only rule is untouched on tv", "0", asSeries.withStatus)
    }

    @Test
    fun `a certification is dropped or kept by the scale it belongs to`() {
        val tvRated = KBFilters(certificationCountry = "US", certification = "TV-14")
        val asMovie = pruneMediaTypeFilters(tvRated, CATALOG_MEDIA_MOVIE)

        assertNull("TV-14 is not a movie rating", asMovie.certification)
        assertNull("and its scale goes with it", asMovie.certificationCountry)

        val movieRated = KBFilters(certificationCountry = "US", certification = "PG-13")
        assertEquals("PG-13", pruneMediaTypeFilters(movieRated, CATALOG_MEDIA_MOVIE).certification)
        assertEquals("US", pruneMediaTypeFilters(movieRated, CATALOG_MEDIA_MOVIE).certificationCountry)
        assertEquals(
            "a valid rating survives a switch to the other type only if that type has it",
            null,
            pruneMediaTypeFilters(movieRated, CATALOG_MEDIA_TV).certification
        )
    }

    @Test
    fun `the runtime window is valid on both media types`() {
        // Episode runtime on a series, runtime on a film: one rule, both types,
        // so a switch must not silently drop it.
        val window = KBFilters(withRuntimeGte = 30, withRuntimeLte = 120)
        val asTv = pruneMediaTypeFilters(window, CATALOG_MEDIA_TV)

        assertEquals(30, asTv.withRuntimeGte)
        assertEquals(120, asTv.withRuntimeLte)
    }

    @Test
    fun `the switch routes through the full prune, not the genre one`() {
        val catalog = CustomCatalog(
            id = "c",
            name = "n",
            mediaType = CATALOG_MEDIA_TV,
            filters = KBFilters(withStatus = "3", withCast = "31")
        )
        val switched = withCatalogMediaType(catalog, CATALOG_MEDIA_MOVIE)

        assertNull(switched.filters.withStatus)
        assertEquals("31", switched.filters.withCast)
    }

    @Test
    fun `the tv shape codes are tmdb's own numbers`() {
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), CATALOG_TV_STATUS_OPTIONS.map { it.code })
        assertEquals("0", CATALOG_TV_STATUS_OPTIONS.first().code)
        assertEquals("Returning", CATALOG_TV_STATUS_OPTIONS.first().label)
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6"), CATALOG_TV_TYPE_OPTIONS.map { it.code })
        assertEquals("Scripted", CATALOG_TV_TYPE_OPTIONS.first { it.code == "4" }.label)
    }

    @Test
    fun `the rule count and the summary line see the new dimensions`() {
        assertEquals(
            3,
            catalogRuleCount(
                KBFilters(
                    withRuntimeGte = 30,
                    withRuntimeLte = 120,
                    withCast = "31"
                )
            )
        )
        assertEquals(1, catalogRuleCount(KBFilters(certification = "R")))
        assertEquals(1, catalogRuleCount(KBFilters(withStatus = "0")))
        assertEquals(1, catalogRuleCount(KBFilters(withType = "4")))
        assertEquals(1, catalogRuleCount(KBFilters(withoutNetworks = "213")))
        assertEquals(
            "zero is not a runtime rule",
            0,
            catalogRuleCount(KBFilters(withRuntimeGte = 0, withRuntimeLte = 0))
        )
        assertEquals(
            "Movies \u00b7 Popularity \u00b7 2 filters",
            catalogSummaryLine(
                CustomCatalog(
                    id = "c",
                    name = "n",
                    filters = KBFilters(withRuntimeLte = 90, certification = "PG")
                )
            )
        )
    }
}
