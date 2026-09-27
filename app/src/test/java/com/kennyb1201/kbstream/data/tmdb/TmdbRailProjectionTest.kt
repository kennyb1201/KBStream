package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail projection is what lets the disk cache hold roughly twice as many
 * titles: one full appended TMDB detail measured 65-280 KB against the live
 * API, and the projection is 27-81 KB.
 *
 * The risk in that trade is a surface that DOES read the bulk silently getting
 * a projection and rendering an empty cast list or no trailer. So these cases
 * name both halves: every field the rail path relies on survives, and every
 * field that is dropped is dropped.
 *
 * `images` is the case that makes the point. It was dropped first, and it
 * broke the Home and KB rail landscape art - not through a field read, but
 * through the [bestLogoPath]/[cardBackdropPath] helpers defined on top of it.
 */
class TmdbRailProjectionTest {

    private val full = TmdbDetail(
        id = 1396,
        name = "Breaking Bad",
        title = null,
        posterPath = "/poster.jpg",
        backdropPath = "/backdrop.jpg",
        overview = "A chemistry teacher.",
        voteAverage = 8.9,
        voteCount = 14000,
        runtime = 49,
        episodeRunTime = listOf(49),
        originalLanguage = "en",
        originCountries = listOf("US"),
        releaseDate = null,
        firstAirDate = "2008-01-20",
        productionCompanies = listOf(TmdbProductionCompany(id = 1, name = "Sony")),
        networks = listOf(TmdbNetwork(id = 174, name = "AMC")),
        numberOfSeasons = 5,
        numberOfEpisodes = 62,
        seasons = listOf(TmdbSeasonSummary(seasonNumber = 1, episodeCount = 7)),
        credits = TmdbCredits(),
        videos = TmdbVideos(),
        recommendations = TmdbRecommendations(),
        reviews = TmdbReviews(),
        genres = listOf(TmdbGenre(id = 18, name = "Drama")),
        keywords = TmdbKeywords(),
        images = TmdbImagesResponse(
            logos = listOf(TmdbImageAsset(filePath = "/logo.png", iso6391 = "en")),
            backdrops = listOf(TmdbImageAsset(filePath = "/alt-backdrop.jpg", iso6391 = null)),
            posters = listOf(TmdbImageAsset(filePath = "/alt-poster.jpg"))
        ),
        awards = "16 Primetime Emmys",
        tagline = "Remember my name.",
        belongsToCollection = TmdbCollectionRef(id = 9, name = "Collection"),
        status = "Ended",
        contentRatings = TmdbTvContentRatings(),
        releaseDates = TmdbMovieReleaseDates()
    )

    @Test
    fun `the bulk is dropped`() {
        val slim = full.railProjection()

        assertNull(slim.credits)
        assertNull(slim.videos)
        assertNull(slim.recommendations)
        assertNull(slim.reviews)
        assertNull(slim.keywords)
    }

    @Test
    fun `images survives, because the rail landscape-art helpers read it`() {
        // This case is the reason the projection keeps `images`. Dropping it
        // looked free - no rail reads the `images` FIELD - but the per-card
        // backdrop + clearlogo prefetch on Home and in KB folders calls
        // bestLogoPath()/cardBackdropPath(), which are defined entirely in
        // terms of it. Both were the slim callers, so dropping it would have
        // blanked every rail card's alternate backdrop and clearlogo with no
        // compile error and no other failing test.
        val slim = full.railProjection()

        assertNotNull(slim.images)
        assertEquals("/alt-backdrop.jpg", slim.cardBackdropPath())
        assertEquals("/logo.png", slim.bestLogoPath())
        assertEquals("/alt-poster.jpg", slim.alternatePosterPath())
    }

    @Test
    fun `the projected card art is identical to the full payload's`() {
        val slim = full.railProjection()

        assertEquals(full.cardBackdropPath(), slim.cardBackdropPath())
        assertEquals(full.bestLogoPath(), slim.bestLogoPath())
        assertEquals(full.alternatePosterPath(), slim.alternatePosterPath())
    }

    @Test
    fun `everything a rail card or resume row draws survives`() {
        val slim = full.railProjection()

        assertEquals("Breaking Bad", slim.name)
        assertEquals("/poster.jpg", slim.posterPath)
        assertEquals("/backdrop.jpg", slim.backdropPath)
        assertEquals("A chemistry teacher.", slim.overview)
        assertEquals(8.9, slim.voteAverage!!, 0.0)
        assertEquals(14000, slim.voteCount)
        assertEquals("2008-01-20", slim.firstAirDate)
        assertEquals("en", slim.originalLanguage)
        assertEquals(listOf(TmdbGenre(id = 18, name = "Drama")), slim.genres)
        assertEquals(listOf(TmdbNetwork(id = 174, name = "AMC")), slim.networks)
    }

    @Test
    fun `the next-episode walk keeps its season and runtime data`() {
        // Continue Watching resolves "what is next" from these, and the player
        // uses episodeRunTime for its runtime gate.
        val slim = full.railProjection()

        assertEquals(5, slim.numberOfSeasons)
        assertEquals(62, slim.numberOfEpisodes)
        assertEquals(1, slim.seasons.size)
        assertEquals(7, slim.seasons.first().episodeCount)
        assertEquals(listOf(49), slim.episodeRunTime)
        assertEquals(49, slim.runtime)
    }

    @Test
    fun `the kids-mode certification data survives`() {
        // kidsFilter() runs over every rail page and decides from these; a
        // projection without them would fail OPEN, which is the wrong way for
        // a parental control to fail.
        val slim = full.railProjection()

        assertNotNull(slim.contentRatings)
        assertNotNull(slim.releaseDates)
    }

    @Test
    fun `the small fields a hero or detail header uses are untouched`() {
        val slim = full.railProjection()

        assertEquals("16 Primetime Emmys", slim.awards)
        assertEquals("Remember my name.", slim.tagline)
        assertEquals("Ended", slim.status)
        assertEquals(9, slim.belongsToCollection?.id)
        assertEquals(1, slim.productionCompanies.size)
    }

    @Test
    fun `projecting is idempotent and never resurrects the bulk`() {
        // The projection is applied on every slim write, so it has to be safe
        // to apply to something already projected.
        val twice = full.railProjection().railProjection()

        assertEquals(full.railProjection(), twice)
        assertNull(twice.credits)
        assertNull(twice.keywords)
        assertNotNull(twice.images)
    }

    @Test
    fun `a projection is a copy, not a view of the cached object`() {
        // The same instance is handed to `full` callers, so the projection must
        // not mutate it on the way out.
        val slim = full.railProjection()

        assertTrue(slim !== full)
        assertNotNull(full.credits)
    }
}
