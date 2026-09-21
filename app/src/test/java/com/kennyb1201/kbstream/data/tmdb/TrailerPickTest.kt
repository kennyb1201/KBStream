package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trailer selection. The failure this pins is the reported one: a series whose
 * only TMDB promo is typed "Teaser" (or "Clip") showed no trailer control at
 * all, because both the button and playback demanded exactly "Trailer".
 */
class TrailerPickTest {

    private fun video(
        key: String,
        type: String,
        site: String = "YouTube"
    ) = TmdbVideo(key = key, site = site, type = type)

    @Test
    fun `trailer wins over every other type`() {
        val picked = TrailerPick.best(
            listOf(
                video("aaa", "Teaser"),
                video("bbb", "Trailer"),
                video("ccc", "Clip")
            )
        )
        assertEquals("bbb", picked?.key)
    }

    @Test
    fun `teaser is used when the show has no trailer`() {
        val picked = TrailerPick.best(
            listOf(
                video("aaa", "Featurette"),
                video("bbb", "Teaser")
            )
        )
        assertEquals("bbb", picked?.key)
    }

    @Test
    fun `clip is used when there is no trailer or teaser`() {
        assertEquals("ccc", TrailerPick.best(listOf(video("ccc", "Clip")))?.key)
    }

    @Test
    fun `unknown types are still playable as a last resort`() {
        assertEquals("ddd", TrailerPick.best(listOf(video("ddd", "Bloopers")))?.key)
    }

    @Test
    fun `non youtube videos are never picked`() {
        assertNull(TrailerPick.best(listOf(video("aaa", "Trailer", site = "Vimeo"))))
    }

    @Test
    fun `a video with a blank key is not a candidate`() {
        assertNull(TrailerPick.best(listOf(video("  ", "Trailer"))))
    }

    @Test
    fun `type matching is case and whitespace insensitive`() {
        assertEquals("eee", TrailerPick.best(listOf(video("eee", " trailer ")))?.key)
        assertEquals("fff", TrailerPick.best(listOf(video("fff", "TRAILER")))?.key)
    }

    @Test
    fun `ties keep tmdb order`() {
        val picked = TrailerPick.best(
            listOf(
                video("first", "Trailer"),
                video("second", "Trailer")
            )
        )
        assertEquals("first", picked?.key)
    }

    @Test
    fun `no videos means no trailer`() {
        assertNull(TrailerPick.best(null))
        assertNull(TrailerPick.best(emptyList()))
    }
}
