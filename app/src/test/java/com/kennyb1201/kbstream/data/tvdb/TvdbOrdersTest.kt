package com.kennyb1201.kbstream.data.tvdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alternate-episode-order rules (spec 2026-11-13, F2/F3).
 *
 * These are the decisions a field test can only see the end of: which orders a
 * show may offer, what a chosen order's episodes are called, and which TMDB
 * episode each one IS. They are pure, so they are pinned here rather than left
 * to a device and two live APIs.
 */
class TvdbOrdersTest {

    // ── What may be offered ──────────────────────────────────────────────

    @Test
    fun `the aired order is always first, and only TVDB's own alternates follow`() {
        val options = TvdbOrders.options(
            listOf(
                TvdbSeasonType(name = "Aired Order", type = "official"),
                TvdbSeasonType(name = "DVD Order", type = "dvd"),
                TvdbSeasonType(name = "Absolute Order", type = "absolute")
            )
        )

        assertEquals(3, options.size)
        assertEquals(TvdbOrders.OFFICIAL_LABEL, options.first().label)
        assertTrue("the zero-state is the first entry", options.first().official)
        assertEquals("", options.first().type)
        assertEquals(listOf("dvd", "absolute"), options.drop(1).map { it.type })
        assertEquals("TVDB's own label, not an invented one", "DVD Order", options[1].label)
    }

    @Test
    fun `default and official spellings are the zero-state, not two more rows`() {
        val options = TvdbOrders.options(
            listOf(
                TvdbSeasonType(name = "Aired Order", type = "official"),
                TvdbSeasonType(name = "Default", type = "default"),
                TvdbSeasonType(name = "DVD Order", type = "dvd"),
                TvdbSeasonType(name = "DVD (duplicate)", type = "DVD")
            )
        )

        assertEquals(2, options.size)
        assertEquals("dvd", options[1].type)
    }

    @Test
    fun `a show with no alternate order has one option, which the picker refuses to draw`() {
        // S2: the row is absent, not a menu with one item.
        assertEquals(1, TvdbOrders.options(emptyList()).size)
        assertEquals(1, TvdbOrders.options(null).size)
        assertEquals(
            1,
            TvdbOrders.options(listOf(TvdbSeasonType(name = "Aired Order", type = "official"))).size
        )
    }

    @Test
    fun `a blank or official type is the aired order and stores nothing`() {
        assertNull(TvdbOrders.normalise(null))
        assertNull(TvdbOrders.normalise(""))
        assertNull(TvdbOrders.normalise("official"))
        assertNull(TvdbOrders.normalise(" Default "))
        assertEquals("dvd", TvdbOrders.normalise(" dvd "))
        assertTrue(TvdbOrders.isOfficial(null))
        assertFalse(TvdbOrders.isOfficial("absolute"))
    }

    // ── The join ─────────────────────────────────────────────────────────

    @Test
    fun `episodes join on their air date, so a renumber keeps the same episode`() {
        // Avatar's DVD order puts "The Winter Solstice" two slots later than the
        // aired order; the episode is the same broadcast.
        val catalogue = TvdbOrders.catalogue(
            seasonType = "dvd",
            orderEpisodes = listOf(
                record(season = 1, number = 1, aired = "2005-02-21"),
                record(season = 1, number = 2, aired = "2005-02-25"),
                record(season = 1, number = 3, aired = "2005-03-04")
            ),
            tmdbEpisodes = listOf(
                tmdb(season = 1, episode = 1, aired = "2005-02-21"),
                tmdb(season = 1, episode = 2, aired = "2005-03-04"),
                tmdb(season = 1, episode = 3, aired = "2005-02-25")
            )
        )

        assertEquals("dvd", catalogue.seasonType)
        assertTrue(catalogue.mapsAnything)
        // The first episode airs first in both orders.
        assertEquals(1, catalogue.orderFor(1, 1)?.orderEpisode)
        // TMDB E2 is the DVD's E3, and TMDB E3 is the DVD's E2.
        assertEquals(3, catalogue.orderFor(1, 2)?.orderEpisode)
        assertEquals(2, catalogue.orderFor(1, 3)?.orderEpisode)
        assertEquals(1, catalogue.orderFor(1, 3)?.orderSeason)
    }

    @Test
    fun `a one-day disagreement still joins, because the two databases do file that`() {
        val catalogue = TvdbOrders.catalogue(
            seasonType = "absolute",
            orderEpisodes = listOf(
                record(season = 1, number = 27, aired = "2007-07-19"),
                record(season = 1, number = 28, aired = "2007-07-26")
            ),
            tmdbEpisodes = listOf(
                tmdb(season = 2, episode = 12, aired = "2007-07-20"),
                tmdb(season = 2, episode = 13, aired = "2007-07-27")
            )
        )

        assertEquals(27, catalogue.orderFor(2, 12)?.orderEpisode)
        assertEquals(1, catalogue.orderFor(2, 12)?.orderSeason)
        assertEquals(28, catalogue.orderFor(2, 13)?.orderEpisode)
    }

    @Test
    fun `with no dates at all, matching counts and positions still joins a season`() {
        val catalogue = TvdbOrders.catalogue(
            seasonType = "dvd",
            orderEpisodes = (1..4).map { record(season = 2, number = it, aired = null) },
            tmdbEpisodes = (1..4).map { tmdb(season = 2, episode = it, aired = null) }
        )

        (1..4).forEach { number ->
            assertEquals(number, catalogue.orderFor(2, number)?.orderEpisode)
        }
    }

    @Test
    fun `an episode the join cannot place keeps no order number, and is never invented`() {
        val catalogue = TvdbOrders.catalogue(
            seasonType = "dvd",
            orderEpisodes = listOf(record(season = 1, number = 1, aired = "2005-02-21")),
            tmdbEpisodes = listOf(
                tmdb(season = 1, episode = 1, aired = "2005-02-21"),
                tmdb(season = 1, episode = 2, aired = "2005-03-04")
            )
        )

        assertNull(catalogue.orderFor(1, 2))
        // The unmatched TMDB episode is not in the mapping and therefore keeps
        // TMDB's number on the row that reads it (see TvdbEpisodeDisplay).
        assertNull(catalogue.forTmdbSeason(1)[2])
    }

    @Test
    fun `an empty answer builds an empty catalogue that maps nothing`() {
        // The invalid-type trap: an empty 200 must never read as "everything is
        // renumbered". A catalogue with no episodes is exactly that refusal.
        val empty = TvdbOrders.catalogue("dvd", emptyList(), listOf(tmdb(1, 1, "2005-02-21")))
        assertEquals(0, empty.episodes.size)
        assertFalse(empty.mapsAnything)

        val noTmdb = TvdbOrders.catalogue("dvd", listOf(record(1, 1, "2005-02-21")), emptyList())
        assertFalse(noTmdb.mapsAnything)
    }

    @Test
    fun `the stored order is a choice, and a catalogue only maps in one direction`() {
        val catalogue = TvdbOrders.catalogue(
            seasonType = "absolute",
            orderEpisodes = (1..3).map { record(season = 1, number = it, aired = "2005-02-0$it") },
            tmdbEpisodes = (1..3).map { tmdb(season = 1, episode = it, aired = "2005-02-0$it") }
        )

        assertEquals(2, catalogue.orderFor(1, 2)?.orderEpisode)
        assertNull("there is no reverse index by construction", catalogue.orderFor(1, 99))
        assertEquals(setOf(1, 2, 3), catalogue.forTmdbSeason(1).keys)
    }

    // ── The number a source is asked for ─────────────────────────────────

    @Test
    fun `the add-on is asked for the number the viewer sees`() {
        val catalogue = TvdbCatalogue(
            seasonType = "absolute",
            builtAtMs = 0L,
            episodes = listOf(
                TvdbOrderEpisode(1, 27, tmdbSeason = 2, tmdbEpisode = 12),
                TvdbOrderEpisode(1, 28, tmdbSeason = 2, tmdbEpisode = 13)
            )
        )

        assertEquals("tt0417299:1:27", TvdbOrders.translatedStreamId("tt0417299:2:12", catalogue))
        assertEquals("tt0417299:1:28", TvdbOrders.translatedStreamId("tt0417299:2:13", catalogue))
    }

    @Test
    fun `an id with no order, no mapping or no shape is returned untouched`() {
        val catalogue = TvdbCatalogue(
            seasonType = "dvd",
            builtAtMs = 0L,
            episodes = listOf(TvdbOrderEpisode(1, 6, tmdbSeason = 1, tmdbEpisode = 5))
        )

        assertEquals("tt1:1:5", TvdbOrders.translatedStreamId("tt1:1:5", null))
        assertEquals("tt1:1:9", TvdbOrders.translatedStreamId("tt1:1:9", catalogue))
        assertEquals("tt1:movie", TvdbOrders.translatedStreamId("tt1:movie", catalogue))
        assertEquals("tt1:1", TvdbOrders.translatedStreamId("tt1:1", catalogue))
        assertEquals("tt1:-1:5", TvdbOrders.translatedStreamId("tt1:-1:5", catalogue))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun record(season: Int, number: Int, aired: String?) =
        TvdbEpisodeRecord(season = season, number = number, airDate = aired)

    private fun tmdb(season: Int, episode: Int, aired: String?) =
        TvdbTmdbEpisode(season = season, episode = episode, airDate = aired)
}
