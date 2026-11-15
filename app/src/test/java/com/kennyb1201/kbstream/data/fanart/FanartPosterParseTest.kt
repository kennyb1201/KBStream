package com.kennyb1201.kbstream.data.fanart

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle's poster list (spec 2026-11-15, fanart in the Media rail F2).
 *
 * The rail's Posters page had nothing from fanart to merge because the parser
 * read no general poster type at all - only `seasonposter`, which describes one
 * season rather than the title. These cases pin the new list: `movieposter` and
 * `tvposter` are the title's own art, they stay distinct from the season set,
 * and everything the bundle deliberately ignores stays ignored.
 */
class FanartPosterParseTest {

    private fun body(vararg entries: Pair<String, String>): JSONObject =
        JSONObject().apply {
            entries.forEach { (key, json) -> put(key, org.json.JSONArray(json)) }
        }

    @Test
    fun `movie and tv posters are read into the bundle`() {
        val art = readFanartArt(
            body(
                "movieposter" to """[{"url":"https://assets.fanart.tv/fanart/movies/1/poster/a.jpg","lang":"en","likes":"3"}]""",
                "tvposter" to """[{"url":"https://assets.fanart.tv/fanart/tv/2/poster/b.jpg","lang":"en","likes":"1"}]"""
            )
        )

        assertEquals(
            listOf(
                "https://assets.fanart.tv/fanart/movies/1/poster/a.jpg",
                "https://assets.fanart.tv/fanart/tv/2/poster/b.jpg"
            ),
            art.posters.map { it.url }
        )
        assertEquals("and the pick rule still ranks English first", "en", art.posters.first().lang)
    }

    @Test
    fun `a season poster is not the title's poster`() {
        val art = readFanartArt(
            body(
                "seasonposter" to """[{"url":"https://assets.fanart.tv/fanart/tv/2/seasonposter/1.jpg","season":"1"}]"""
            )
        )

        assertEquals(1, art.seasonPosters.size)
        assertEquals(
            "one is a season, the other is the show: merging them would offer a season as the title",
            0,
            art.posters.size
        )
    }

    @Test
    fun `the types the bundle ignores stay ignored`() {
        val art = readFanartArt(
            body(
                "characterart" to """[{"url":"https://assets.fanart.tv/fanart/character.jpg"}]""",
                "moviedisc" to """[{"url":"https://assets.fanart.tv/fanart/disc.png"}]""",
                "moviebanner" to """[{"url":"https://assets.fanart.tv/fanart/banner.jpg"}]""",
                "moviethumb" to """[{"url":"https://assets.fanart.tv/fanart/thumb.jpg"}]"""
            )
        )

        assertTrue("no surface consumes them, so the parser stays out of them", art.isEmpty)
    }

    @Test
    fun `a poster-only bundle is not a miss`() {
        val art = readFanartArt(
            body("movieposter" to """[{"url":"https://assets.fanart.tv/fanart/movies/1/poster/a.jpg"}]""")
        )

        assertFalse(
            "a title with only posters must count as art, or the lookup would cache a miss",
            art.isEmpty
        )
    }

    @Test
    fun `an entry with no url is skipped`() {
        val art = readFanartArt(
            body(
                "movieposter" to """[{"lang":"en"},{"url":"  "},{"url":"https://assets.fanart.tv/fanart/movies/1/poster/a.jpg"}]"""
            )
        )

        assertEquals(1, art.posters.size)
    }

    @Test
    fun `the disk codec keeps the poster list`() {
        val art = FanartArt(
            posters = listOf(
                FanartImage(url = "https://assets.fanart.tv/fanart/movies/1/poster/a.jpg", lang = "en", likes = 4)
            )
        )

        val restored = fanartArtFromJson(fanartArtToJson(art))

        assertEquals(1, restored?.posters?.size)
        assertEquals("en", restored?.posters?.first()?.lang)
        assertEquals(4, restored?.posters?.first()?.likes)
    }

    @Test
    fun `a row written before the poster list existed still parses`() {
        val legacy = """{"logos":[],"backgrounds":[{"url":"https://x/bg.jpg"}],"backgrounds4k":[],"seasonPosters":[]}"""

        val restored = fanartArtFromJson(legacy)

        assertEquals(
            "an older cache row must lose nothing but the list it never had",
            1,
            restored?.backgrounds?.size
        )
        assertNull("no posters is empty, not null", restored?.posters?.firstOrNull())
    }
}
