package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one rule a card's backdrop is chosen by, and the collection path that now
 * shares it.
 *
 * A TMDB collection is not a title: `GET /collection/{id}` has no `images` to
 * append to, so a collection tile used to be handed whatever key art the search
 * result carried - the image most likely to have a burned-in title - while every
 * other card in the app was ranked. The fix is a second endpoint
 * (`/collection/{id}/images`) and the SAME ranking over it, which is why the
 * order itself lives in [bestCardBackdropPath] rather than inside
 * [cardBackdropPath]: two copies of it is how the collection rail would drift
 * away from every other card again.
 *
 * The order is the whole defense available: TMDB has no "textless" flag, and its
 * `null` language tag means "the uploader never set one" rather than "no text in
 * the image" (see [bestLogoPath]'s note). So these cases pin what the app can
 * actually promise - textless-tagged first, then English, then votes, and never
 * the image already on screen - not a guarantee that the winner is clean.
 */
class CollectionBackdropRankingTest {

    @Test
    fun `a textless backdrop is preferred over an English one, however it is voted`() {
        assertEquals(
            "/textless.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    asset("/english.jpg", language = "en", votes = 9.0),
                    asset("/textless.jpg", language = null, votes = 1.0)
                ),
                primaryPath = null
            )
        )
    }

    @Test
    fun `English is preferred over a foreign-language backdrop`() {
        assertEquals(
            "/english.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    asset("/french.jpg", language = "fr", votes = 10.0),
                    asset("/english.jpg", language = "en", votes = 1.0)
                ),
                primaryPath = null
            )
        )
    }

    @Test
    fun `among equals the better-voted image wins`() {
        assertEquals(
            "/better.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    asset("/worse.jpg", language = null, votes = 3.0),
                    asset("/better.jpg", language = null, votes = 7.5)
                ),
                primaryPath = null
            )
        )
    }

    @Test
    fun `the primary image is never chosen while another one exists`() {
        // The half of the rule that makes the change visible at all: the key art
        // a tile already holds is usually the best-voted textless image, so a
        // ranking without this returns it straight back and the tile looks
        // untouched.
        assertEquals(
            "/alternate.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    asset("/key-art.jpg", language = null, votes = 9.9),
                    asset("/alternate.jpg", language = null, votes = 4.0)
                ),
                primaryPath = "/key-art.jpg"
            )
        )
    }

    @Test
    fun `an English alternate beats a textless primary`() {
        assertEquals(
            "/english.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    asset("/key-art.jpg", language = null, votes = 9.9),
                    asset("/english.jpg", language = "en", votes = 2.0)
                ),
                primaryPath = "/key-art.jpg"
            )
        )
    }

    @Test
    fun `with no other image the primary is the answer`() {
        // The caller has the primary already; returning it keeps the title case
        // (a card that must draw something) exactly as it was. The COLLECTION
        // path turns this same answer into "nothing better exists" - see
        // [TmdbRepository.fetchCollectionBackdropCached].
        assertEquals(
            "/key-art.jpg",
            bestCardBackdropPath(
                backdrops = listOf(asset("/key-art.jpg", language = null, votes = 9.9)),
                primaryPath = "/key-art.jpg"
            )
        )
    }

    @Test
    fun `a title with no images answers with its primary backdrop`() {
        assertEquals("/primary.jpg", bestCardBackdropPath(emptyList(), "/primary.jpg"))
        assertNull(bestCardBackdropPath(emptyList(), null))
        assertNull(bestCardBackdropPath(emptyList(), "   "))
    }

    @Test
    fun `entries with no file path are not candidates`() {
        // TMDB stubs and half-filled rows do appear; a blank path would be
        // resolved into a URL that 404s, which reads as a broken image rather
        // than as missing art.
        assertEquals(
            "/real.jpg",
            bestCardBackdropPath(
                backdrops = listOf(
                    TmdbImageAsset(filePath = null, iso6391 = null, voteAverage = 10.0),
                    TmdbImageAsset(filePath = "", iso6391 = null, voteAverage = 10.0),
                    asset("/real.jpg", language = null, votes = 1.0)
                ),
                primaryPath = null
            )
        )
    }

    @Test
    fun `a title's own card backdrop is that same rule over its own images`() {
        // The delegation is the point of the extraction: if `cardBackdropPath`
        // ever grows a private tweak, the collection tiles silently stop matching
        // every other card.
        val detail = TmdbDetail(
            id = 603,
            posterPath = "/poster.jpg",
            backdropPath = "/key-art.jpg",
            images = TmdbImagesResponse(
                logos = listOf(TmdbImageAsset(filePath = "/logo.png", iso6391 = "en")),
                backdrops = listOf(
                    asset("/key-art.jpg", language = null, votes = 9.0),
                    asset("/textless.jpg", language = null, votes = 2.0),
                    asset("/english.jpg", language = "en", votes = 8.0)
                )
            )
        )

        assertEquals(
            bestCardBackdropPath(detail.images!!.backdrops, detail.backdropPath),
            detail.cardBackdropPath()
        )
        assertEquals("/textless.jpg", detail.cardBackdropPath())
    }

    @Test
    fun `a TMDB image URL gives up its file path at any size`() {
        // What lets a collection tile name the image it is already drawing: the
        // tile holds a URL (the search result's backdrop), the ranking compares
        // `file_path`s, and the size segment is whatever that surface chose.
        listOf(
            "https://image.tmdb.org/t/p/w780/abc.jpg" to "/abc.jpg",
            "https://image.tmdb.org/t/p/w1280/abc.jpg" to "/abc.jpg",
            "http://image.tmdb.org/t/p/original/abc.jpg" to "/abc.jpg"
        ).forEach { (url, path) ->
            assertEquals(url, path, TmdbRepository.tmdbImagePath(url))
        }
    }

    @Test
    fun `a URL that is not TMDB's own image has no path to compare`() {
        // An add-on CDN or a fanart URL is not ours to interpret: guessing a path
        // out of one could make an unrelated image count as "already on screen"
        // and suppress the ranked pick.
        listOf(
            null,
            "",
            "   ",
            "https://cdn.example.com/t/p/w780/abc.jpg",
            "https://image.tmdb.org/t/p/w780"
        ).forEach { url ->
            assertNull("$url must not yield a path", TmdbRepository.tmdbImagePath(url))
        }
    }

    private fun asset(filePath: String, language: String?, votes: Double) =
        TmdbImageAsset(filePath = filePath, iso6391 = language, voteAverage = votes)
}
