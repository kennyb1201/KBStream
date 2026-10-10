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
    fun `network chips are every plain network entry, keyed by its own id`() {
        // The bug this replaces: the row read `networkOrCompanyId`, which is
        // null on a plain network page (those keep their TMDB network id in
        // `id`), so it matched only the SERVICE entries with no watch-provider
        // id and offered a single chip while Browse listed 77 networks.
        val plainNetworks = com.kennyb1201.kbstream.ui.search.BROWSE_PROVIDER_ENTRIES
            .filter { it.providerId == null && !it.networkIsCompany && it.id > 0 }
        assertTrue(
            "the row has to offer the networks the browse catalog carries, not one",
            plainNetworks.size > 30
        )
        assertEquals(
            "one chip per network, no repeats",
            CATALOG_NETWORK_OPTIONS.size,
            CATALOG_NETWORK_OPTIONS.map { it.id }.toSet().size
        )
        assertEquals(
            "networks are the entries with no provider id, keyed by their own id",
            plainNetworks.map { it.id }.toSet(),
            CATALOG_NETWORK_OPTIONS.map { it.id }.toSet()
        )
        assertEquals(
            "ABC is one of them",
            "ABC",
            CATALOG_NETWORK_OPTIONS.firstOrNull { it.id == 2 }?.label
        )
    }

    @Test
    fun `the release-type chips are tmdb's own codes`() {
        assertEquals("", CATALOG_RELEASE_TYPE_OPTIONS.first().code)
        assertEquals("Any release", CATALOG_RELEASE_TYPE_OPTIONS.first().label)
        assertEquals(
            CATALOG_RELEASE_TYPE_OPTIONS.size,
            CATALOG_RELEASE_TYPE_OPTIONS.map { it.code }.toSet().size
        )
        assertEquals(
            "the digital release is TMDB's type 4",
            "Digital release",
            CATALOG_RELEASE_TYPE_OPTIONS.first { it.code == "4" }.label
        )
        CATALOG_RELEASE_TYPE_OPTIONS.forEach { option ->
            assertTrue("${option.label} has no code at all", option.label.isNotBlank())
            assertTrue(
                "${option.code} is not a release type TMDB knows (1-6)",
                option.code.isEmpty() ||
                    option.code.split(',').all { it.trim().toIntOrNull() in 1..6 }
            )
        }
    }

    @Test
    fun `a series catalog cannot keep a release type`() {
        // with_release_type is movie-only, like with_cast: /discover/tv has no
        // such filter, so a series catalog that kept one would ask for a rule
        // the endpoint silently ignores.
        val movie = CustomCatalog(
            id = "c",
            name = "Digital",
            mediaType = CATALOG_MEDIA_MOVIE,
            filters = KBFilters(withReleaseType = "4", withCast = "31")
        )
        val series = withCatalogMediaType(movie, CATALOG_MEDIA_TV)
        assertNull("the release type is a movie rule", series.filters.withReleaseType)
        assertNull("and so is the cast", series.filters.withCast)

        val back = withCatalogMediaType(series, CATALOG_MEDIA_MOVIE)
        assertNull("a pruned rule does not come back", back.filters.withReleaseType)
    }

    @Test
    fun `the release filter is one more thing the rule count sees`() {
        assertEquals(1, catalogRuleCount(KBFilters(withReleaseType = "4")))
        assertEquals(
            2,
            catalogRuleCount(KBFilters(withReleaseType = "4", withWatchProviders = "8"))
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

    // ------------------------------------------------------- hand-typed ids --

    @Test
    fun `a typed id is read out of whatever shape it was pasted in`() {
        // The ids a viewer has to hand are the ones printed on a TMDB page or
        // sitting in its URL, so all of these have to mean the same thing.
        assertEquals(listOf(8), parseCustomIds("8"))
        assertEquals(listOf(8), parseCustomIds("  #8  "))
        assertEquals(listOf(8), parseCustomIds("tmdb:8"))
        assertEquals(listOf(8), parseCustomIds("https://www.themoviedb.org/movie/8"))
        assertEquals(listOf(8, 337, 9), parseCustomIds("8, 337\n9"))
        assertEquals(
            "a repeated id is one chip",
            listOf(8, 9),
            parseCustomIds("8, 9, 8")
        )
        assertEquals(listOf(213), parseCustomIds("id=213"))
    }

    @Test
    fun `a token with no id in it is dropped rather than read as zero`() {
        assertTrue(parseCustomIds("netflix").isEmpty())
        assertTrue(parseCustomIds("").isEmpty())
        assertTrue(parseCustomIds("   ").isEmpty())
        assertTrue(parseCustomIds(",,,").isEmpty())
        assertEquals(
            "0 is not a TMDB id",
            emptyList<Int>(),
            parseCustomIds("0")
        )
        assertEquals(
            "the words around an id do not stop it being read",
            listOf(8),
            parseCustomIds("Netflix 8")
        )
    }

    @Test
    fun `every id field reads and writes its own filter`() {
        // One field per id-list chip row, and each one has to land on the field
        // its row renders - an id written into the wrong list is a rule that
        // silently matches nothing.
        CatalogIdField.entries.forEach { field ->
            val written = field.withIds(KBFilters(), listOf(7, 9))
            assertEquals(
                "${field.label} did not write its ids back",
                listOf(7, 9),
                field.read(written)
            )
            assertEquals(
                "${field.label} wrote into another field's list",
                1,
                CatalogIdField.entries.count { other -> other.read(written).isNotEmpty() }
            )
        }
        assertEquals(CatalogIdField.entries.size, CatalogIdField.entries.map { it.label }.toSet().size)
        assertEquals(CatalogIdField.entries.size, CatalogIdField.entries.map { it.name }.toSet().size)
    }

    @Test
    fun `adding ids keeps the order, drops duplicates and stays reversible`() {
        val after = CatalogIdField.SERVICES.withIds(
            KBFilters(withWatchProviders = "8"),
            listOf(337, 8)
        )
        assertEquals(listOf(8, 337), CatalogIdField.SERVICES.read(after))
        assertEquals("8,337", after.withWatchProviders)
        // A typed id joins the same CSV a tapped chip does, so the chip rule
        // that takes an id off again works on it unchanged.
        assertEquals(
            "a typed id is removable like a tapped one",
            "337",
            com.kennyb1201.kbstream.data.catalogs.csvToggle(after.withWatchProviders, 8)
        )
        // Emptying the field is a real state, not an empty string.
        val cleared = CatalogIdField.NETWORKS.withIds(KBFilters(), emptyList())
        assertNull(cleared.withNetworks)
    }

    @Test
    fun `an id the shipped list does not have is drawn as its own chip`() {
        val options = listOf(CatalogFilterOption(8, "Netflix"))
        assertEquals(
            "a known id keeps the name the list gives it",
            listOf(CatalogFilterOption(8, "Netflix")),
            catalogOptionsWithCustom(options, listOf(8))
        )
        assertEquals(
            "an unknown id still has to be visible and removable",
            listOf(CatalogFilterOption(8, "Netflix"), CatalogFilterOption(9999, "#9999")),
            catalogOptionsWithCustom(options, listOf(9999, 8))
        )
        assertEquals(
            "and it is not drawn twice",
            2,
            catalogOptionsWithCustom(options, listOf(9999, 9999)).size
        )
    }

    // ---------------------------------------- hand-typed minimum votes --

    @Test
    fun `a typed vote count is read however it is punctuated`() {
        assertEquals(2500, parseVoteCountInput("2500"))
        assertEquals(2500, parseVoteCountInput("2,500"))
        assertEquals(2500, parseVoteCountInput(" 2500 "))
        // Not a positive count: there is no floor to write, so nothing is.
        assertNull(parseVoteCountInput(""))
        assertNull(parseVoteCountInput("votes"))
        assertNull(parseVoteCountInput("0"))
    }

    @Test
    fun `a custom minimum is drawn as its own chip and leaves known ones alone`() {
        assertEquals(
            "a count the chips already carry changes nothing",
            CATALOG_MIN_VOTE_OPTIONS,
            voteCountOptionsWithCustom(CATALOG_MIN_VOTE_OPTIONS, 500)
        )
        assertEquals(
            "no rule is no extra chip",
            CATALOG_MIN_VOTE_OPTIONS,
            voteCountOptionsWithCustom(CATALOG_MIN_VOTE_OPTIONS, null)
        )
        assertEquals(
            "a typed count shows up, in order, so it can be seen and taken back",
            listOf(0, 50, 200, 300, 500, 1000),
            voteCountOptionsWithCustom(CATALOG_MIN_VOTE_OPTIONS, 300)
        )
        assertEquals(
            "and a high one lands at the end",
            listOf(0, 50, 200, 500, 1000, 2500),
            voteCountOptionsWithCustom(CATALOG_MIN_VOTE_OPTIONS, 2500)
        )
    }

    @Test
    fun `a typed minimum reaches the same filter the chips write`() {
        // The point of typing a number is the rule, not the chip: it has to
        // land on `voteCountGte` exactly as a tapped chip does, or the row
        // shows a rule the loader never sends.
        val typed = parseVoteCountInput("2500")
        assertTrue(parseVoteCountInput("2,500") == typed)
        val filters = KBFilters(voteCountGte = typed)
        assertEquals(2500, filters.voteCountGte)
    }
}
