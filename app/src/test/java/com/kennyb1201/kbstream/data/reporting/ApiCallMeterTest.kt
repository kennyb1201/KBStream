package com.kennyb1201.kbstream.data.reporting

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-service call line (F7, spec 2026-11-20).
 *
 * The counts are what judge every later call-volume cut, so the two things that
 * can silently make them wrong are pinned here: which host counts as which
 * service, and that the line always lists every service (a missing column would
 * read as "no calls" for that API, which is the one thing a measurement must
 * never say by accident).
 */
class ApiCallMeterTest {

    @After
    fun tearDown() {
        ApiCallMeter.resetForTest()
    }

    @Test
    fun `each api's host maps to its own service`() {
        assertEquals("simkl", ApiCallMeter.labelFor("api.simkl.com"))
        assertEquals("trakt", ApiCallMeter.labelFor("api.trakt.tv"))
        assertEquals("mdblist", ApiCallMeter.labelFor("api.mdblist.com"))
        assertEquals("bingecat", ApiCallMeter.labelFor("bingecat.com"))
        assertEquals("tmdb", ApiCallMeter.labelFor("api.themoviedb.org"))
        assertEquals("anilist", ApiCallMeter.labelFor("graphql.anilist.co"))
        assertEquals("mal", ApiCallMeter.labelFor("api.myanimelist.net"))
        assertEquals("kitsu", ApiCallMeter.labelFor("kitsu.app"))
        assertEquals("tvmaze", ApiCallMeter.labelFor("api.tvmaze.com"))
        assertEquals("omdb", ApiCallMeter.labelFor("www.omdbapi.com"))
    }

    @Test
    fun `subdomains and case still land on the right service`() {
        assertEquals("tmdb", ApiCallMeter.labelFor("IMAGE.TMDB.ORG"))
        assertEquals("trakt", ApiCallMeter.labelFor("api.trakt.tv"))
        assertEquals("simkl", ApiCallMeter.labelFor("data.simkl.com"))
    }

    @Test
    fun `an add-on host nobody can name is counted as an add-on`() {
        assertEquals("addons", ApiCallMeter.labelFor("flixnest.app"))
        assertEquals("addons", ApiCallMeter.labelFor("pengu.uk"))
        assertEquals(
            "a domain that merely ENDS like a service must not be mistaken for it",
            "addons",
            ApiCallMeter.labelFor("notsimkl.com")
        )
    }

    @Test
    fun `the line lists every service, then the total`() {
        val empty = ApiCallMeter.line()

        listOf(
            "simkl=0", "trakt=0", "mdblist=0", "bingecat=0", "tmdb=0",
            "anilist=0", "mal=0", "kitsu=0", "tvmaze=0", "omdb=0", "addons=0"
        ).forEach { column ->
            assertTrue("the empty line must carry $column: $empty", empty.contains(column))
        }
        assertTrue("and a total: $empty", empty.contains("total=0"))

        ApiCallMeter.record("trakt")
        ApiCallMeter.record("trakt")
        ApiCallMeter.record("tmdb")

        val line = ApiCallMeter.line()

        assertTrue(line, line.contains("trakt=2"))
        assertTrue(line, line.contains("tmdb=1"))
        assertTrue(line, line.contains("total=3"))
        assertEquals(2, ApiCallMeter.countOf("trakt"))
    }

    @Test
    fun `the services keep the same order in every paste`() {
        val line = ApiCallMeter.line()
        val order = listOf("simkl", "trakt", "mdblist", "bingecat", "tmdb", "addons")
        val positions = order.map { line.indexOf("$it=") }

        assertTrue("every service is present: $line", positions.all { it >= 0 })
        assertEquals(
            "the columns must line up between two pastes: $line",
            positions.sorted(),
            positions
        )
    }
}
