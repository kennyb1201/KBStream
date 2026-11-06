package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The type suffix a pinned Library rail's heading carries.
 *
 * The rail draws no per-card label (Kenny: the type lives in the header only), so
 * the header has to say what the list actually holds - and a pinned list may be
 * all films, all shows or a mix, unlike the Because You Watched rails, which are
 * single-type by construction and taught the wording ([libraryRailTypeSuffix]
 * repeats their separator and their words on purpose).
 *
 * The suffix is computed from the items that LOADED, which is why the rules are
 * about the rows' own `type` spellings: an add-on's "tv" is a series, "anime" is
 * a series, "anime.movie" is a film, and a spelling this build does not know must
 * add nothing rather than guess a side.
 */
class LibraryRailTypeSuffixTest {

    @Test
    fun `a single-type list names that type`() {
        assertEquals(" · Movies", libraryRailTypeSuffix(listOf("movie", "movie")))
        assertEquals(" · Series", libraryRailTypeSuffix(listOf("series", "series")))
    }

    @Test
    fun `a mixed list says both, in the Because You Watched wording`() {
        assertEquals(
            " · Movies & Series",
            libraryRailTypeSuffix(listOf("movie", "series", "movie"))
        )
    }

    @Test
    fun `the add-on spellings are the type they mean`() {
        // "tv" and "anime" are series everywhere else in the app; an anime FILM
        // is a movie. A rail that read them literally would say nothing at all.
        assertEquals(" · Series", libraryRailTypeSuffix(listOf("tv")))
        assertEquals(" · Series", libraryRailTypeSuffix(listOf("show")))
        assertEquals(" · Series", libraryRailTypeSuffix(listOf("anime", "anime.series")))
        assertEquals(" · Movies", libraryRailTypeSuffix(listOf("anime.movie")))
        assertEquals(
            " · Movies & Series",
            libraryRailTypeSuffix(listOf("anime.movie", "anime"))
        )
        // Case and padding are the wire's, not the rule's.
        assertEquals(" · Movies", libraryRailTypeSuffix(listOf("  MOVIE  ")))
    }

    @Test
    fun `no items and no known type both add nothing`() {
        assertEquals(
            "an empty rail is not drawn at all, but a blank suffix is still the answer",
            "",
            libraryRailTypeSuffix(emptyList())
        )
        assertEquals(
            "a spelling this build does not know adds nothing rather than guessing",
            "",
            libraryRailTypeSuffix(listOf("", "collection", "kitsu.series"))
        )
    }

    @Test
    fun `an unknown spelling does not make a single-type list mixed`() {
        // The unknown one is ignored; the known one still names the list, so a
        // rail of films plus one odd row reads "Movies", not "Movies & Series"
        // and not nothing.
        assertEquals(" · Movies", libraryRailTypeSuffix(listOf("movie", "channel")))
    }
}
