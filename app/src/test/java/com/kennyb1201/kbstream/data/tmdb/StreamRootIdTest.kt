package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides which id a Stremio stream request names a title by.
 *
 * The case that motivated it is a series catalog handing out `tt3121722` for a
 * show whose TMDB record (`57532`) maps to `tt4873036`: the wrong id was trusted
 * blindly, and every episode of the show was answered with another title's
 * streams. The rule is therefore "TMDB's own IMDB id wins, and only a
 * well-formed one counts", with the route id (the catalog's) as the fallback
 * that keeps a title with no TMDB record working exactly as it did before.
 */
class StreamRootIdTest {

    @Test
    fun `TMDB's IMDB id replaces the catalog's`() {
        assertEquals("tt4873036", preferredStreamRootId("tt3121722", "tt4873036"))
    }

    @Test
    fun `an id that already agrees is returned untouched`() {
        assertEquals("tt0903747", preferredStreamRootId("tt0903747", "tt0903747"))
    }

    @Test
    fun `a TMDB-flavoured route id becomes the IMDB one`() {
        // A route opened from a TMDB rail carries "tmdb:<n>"; the request id is
        // still the flavor every add-on and every history row speaks.
        assertEquals("tt0903747", preferredStreamRootId("tmdb:1396", "tt0903747"))
    }

    @Test
    fun `a tvdb-flavoured route id becomes the IMDB one`() {
        // TVDB-sourced catalogs (AIOMetadata, BingeCat) emit "tvdb:<n>".
        assertEquals("tt0386676", preferredStreamRootId("tvdb:79126", "tt0386676"))
    }

    @Test
    fun `no resolved id keeps the route id`() {
        // No TMDB id to ask about: a synthetic add-on-videos detail, a missing
        // key, an offline device, or an API error.
        assertEquals("tt3121722", preferredStreamRootId("tt3121722", null))
    }

    @Test
    fun `a blank resolved id keeps the route id`() {
        assertEquals("tt3121722", preferredStreamRootId("tt3121722", ""))
        assertEquals("tt3121722", preferredStreamRootId("tt3121722", "   "))
    }

    @Test
    fun `a non-IMDB answer is never used as the request id`() {
        // A "tvdb:"/"tmdb:" flavor or a bare number is not something an add-on
        // can resolve, so requesting it would be worse than the id we already
        // had - the fallback is the whole point.
        assertEquals("tvdb:12345", preferredStreamRootId("tvdb:12345", "tvdb:98765"))
        assertEquals("tt3121722", preferredStreamRootId("tt3121722", "57532"))
        assertEquals("tt3121722", preferredStreamRootId("tt3121722", "tt"))
        // A route id with no IMDB shape to replace stays itself too: the rule
        // never invents an id for a title it only knows as "tmdb:<n>".
        assertEquals("tmdb:1396", preferredStreamRootId("tmdb:1396", "57532"))
    }

    @Test
    fun `the resolved id is trimmed`() {
        assertEquals("tt4873036", preferredStreamRootId("tt3121722", " tt4873036 "))
    }
}
