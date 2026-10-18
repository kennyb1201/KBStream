package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Simkl's API rules make attribution a per-item link: "Wherever Simkl data
 * appears in your app, link back to the Simkl page for that specific item - not
 * just a generic homepage link", with the published shapes
 * `/movies/:simkl_id/:slug`, `/tv/:simkl_id/:slug` and `/anime/:simkl_id/:slug`,
 * and the slug included "when you have it".
 *
 * That makes this URL the one thing about the credit that can be wrong in a way
 * nobody notices: a link that opens Simkl's homepage still looks like
 * attribution, and a link with the wrong id opens a different show. Both halves
 * are pinned here.
 */
class SimklItemLinkTest {

    @Test
    fun `a movie points at its Simkl movie page`() {
        assertEquals(
            "https://simkl.com/movies/278",
            simklItemUrl(mediaType = "movie", simklId = 278)
        )
    }

    @Test
    fun `a series points at its Simkl tv page`() {
        assertEquals("https://simkl.com/tv/1396", simklItemUrl(mediaType = "series", simklId = 1396))
    }

    @Test
    fun `the slug is kept when the read carried one`() {
        assertEquals(
            "https://simkl.com/tv/1396/breaking-bad-2008",
            simklItemUrl(mediaType = "series", simklId = 1396, slug = "Breaking-Bad-2008")
        )
    }

    /** A bare id still resolves, so a slug that is not a slug must not break the link. */
    @Test
    fun `an unusable slug is dropped, not encoded into the path`() {
        listOf("Breaking Bad!", "a b", "", "   ", "tv/1396", "séries").forEach { odd ->
            assertEquals(
                "the slug $odd should be dropped",
                "https://simkl.com/tv/1396",
                simklItemUrl(mediaType = "series", simklId = 1396, slug = odd)
            )
        }
    }

    /** Without an id there is no item page: a homepage link is not attribution. */
    @Test
    fun `no Simkl id means no link at all`() {
        assertNull(simklItemUrl(mediaType = "series", simklId = null))
        assertNull(simklItemUrl(mediaType = "series", simklId = 0))
        assertNull(simklItemUrl(mediaType = "series", simklId = -1))
    }

    /**
     * The app's metadata carries only movie/series, so anything else takes the
     * tv shape - which is the shape every non-movie Simkl record resolves
     * through, and the id is what actually identifies the page.
     */
    @Test
    fun `an unknown or missing media type falls back to the tv shape`() {
        assertEquals("https://simkl.com/tv/5", simklItemUrl(mediaType = null, simklId = 5))
        assertEquals("https://simkl.com/tv/5", simklItemUrl(mediaType = "anime", simklId = 5))
        assertEquals("https://simkl.com/movies/5", simklItemUrl(mediaType = " MOVIE ", simklId = 5))
    }
}
