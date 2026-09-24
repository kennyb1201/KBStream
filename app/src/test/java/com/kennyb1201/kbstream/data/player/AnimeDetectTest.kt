package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind Settings → Playback engine → "Play Anime in MPV": what counts
 * as anime, and which engine that setting sends it to.
 *
 * Each signal is pinned on its own, because over-detecting is as bad as
 * under-detecting here - a Western cartoon routed into MPV loses the app's own
 * player panel (sources, audio tuning, remembered tracks) for nothing.
 */
class AnimeDetectTest {

    @Test
    fun `an anime catalog type is anime on its own`() {
        assertTrue(AnimeDetect.isAnime("anime", emptyList(), null))
        assertTrue(AnimeDetect.isAnime("anime.series", emptyList(), null))
        assertTrue(AnimeDetect.isAnime("anime.movie", emptyList(), null))

        // Addon catalog types arrive with arbitrary casing and spacing.
        assertTrue(AnimeDetect.isAnimeType(" Anime.Series "))
        assertTrue(AnimeDetect.isAnimeType("ANIME"))
    }

    @Test
    fun `japanese animation is anime`() {
        assertTrue(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = listOf(AnimeDetect.ANIMATION_GENRE_ID, 10759),
                originalLanguage = "ja"
            )
        )

        // TMDB has shipped both casings/spacings of the language code.
        assertTrue(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = listOf(AnimeDetect.ANIMATION_GENRE_ID),
                originalLanguage = " JA "
            )
        )
    }

    @Test
    fun `animation in another language is not anime`() {
        // A US cartoon: the fansub typesetting and 10-bit releases this
        // setting exists for are not what it ships.
        assertFalse(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = listOf(AnimeDetect.ANIMATION_GENRE_ID),
                originalLanguage = "en"
            )
        )

        assertFalse(
            AnimeDetect.isAnime(
                parentType = "movie",
                genreIds = listOf(AnimeDetect.ANIMATION_GENRE_ID),
                originalLanguage = "fr"
            )
        )
    }

    @Test
    fun `japanese live action is not anime`() {
        // The genre check is what keeps J-dramas out: right language, wrong
        // medium.
        assertFalse(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = listOf(18, 9648),
                originalLanguage = "ja"
            )
        )
    }

    @Test
    fun `the anime keyword is enough by itself`() {
        // Covers a non-Japanese original language (a donghua, a Korean
        // animation) and a title whose genre list the caller did not have.
        assertTrue(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = emptyList(),
                originalLanguage = "zh",
                keywordNames = listOf("anime")
            )
        )

        assertTrue(
            AnimeDetect.isAnime(
                parentType = "movie",
                genreIds = emptyList(),
                originalLanguage = null,
                keywordNames = listOf("based on manga", " Anime ")
            )
        )

        // A keyword that merely mentions it is not it.
        assertFalse(
            AnimeDetect.isAnime(
                parentType = "series",
                genreIds = emptyList(),
                originalLanguage = "en",
                keywordNames = listOf("anime inspired")
            )
        )
    }

    @Test
    fun `a title with no signals at all is not anime`() {
        assertFalse(AnimeDetect.isAnime(null, emptyList(), null))
        assertFalse(AnimeDetect.isAnime("", emptyList(), null, emptyList()))
        assertFalse(AnimeDetect.isAnime("series", emptyList(), null))
        assertFalse(AnimeDetect.isAnime("movie", emptyList(), "ja"))
    }

    /**
     * Episode ids carry their season and episode ("tt1234:4:2"); the detail
     * cache is keyed by the bare show id, so the suffix has to go before the
     * lookup - otherwise every episode misses the cache entry the rest of the
     * app already warmed.
     */
    @Test
    fun `an episode id resolves to its show id`() {
        assertEquals("tt1234", AnimeDetect.titleIdOf("tt1234:4:2"))
        assertEquals("tt1234", AnimeDetect.titleIdOf(" tt1234:4:2 "))
        assertEquals("tmdb:1234", AnimeDetect.titleIdOf("tmdb:1234:4:2"))
        assertEquals("1234", AnimeDetect.titleIdOf("1234:1:10"))

        // Movies have no suffix, and a bare tmdb id must not lose its prefix:
        // only the canonical season/episode PAIR is ever stripped.
        assertEquals("tt1234", AnimeDetect.titleIdOf("tt1234"))
        assertEquals("tmdb:1234", AnimeDetect.titleIdOf("tmdb:1234"))
    }
}
