package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request URL a scheme-only external player is handed.
 *
 * VidHub registers no video VIEW filter, so it is invisible to the MIME probe
 * and is reached by its own URL instead. That URL is the whole integration, and
 * getting it wrong fails in the least debuggable way there is: the app simply
 * never opens, or opens and refuses the request (its own error 102 is "invalid
 * position"), and nothing on the TV says why. So it is pinned here, exactly,
 * against VidHub's documented `/play` shape.
 */
class ExternalPlayerVidHubTest {

    @Test
    fun `the request is VidHub's documented play url`() {
        assertEquals(
            "open-vidhub://x-callback-url/play" +
                "?url=https%3A%2F%2Fcdn.example%2Fmovie.mkv" +
                "&position=120" +
                "&filename=Example%20Movie" +
                "&x-source=KBStream",
            ExternalPlayer.vidHubPlayUrl(
                url = "https://cdn.example/movie.mkv",
                title = "Example Movie",
                positionMs = 120_000L
            )
        )
    }

    @Test
    fun `a position is handed over in whole seconds`() {
        // VidHub takes seconds, and a millisecond value passed as seconds would
        // resume a film a thousand times too far in.
        val url = ExternalPlayer.vidHubPlayUrl("https://x/y.mp4", null, 3_600_000L)
        assertTrue(url, url.contains("&position=3600"))
        assertEquals("0", ExternalPlayer.vidHubPositionSeconds(0L))
        assertEquals("1", ExternalPlayer.vidHubPositionSeconds(1_000L))
        // Floored, never rounded up: rounding up could resume past the end.
        assertEquals("119", ExternalPlayer.vidHubPositionSeconds(119_999L))
        // A negative resume point is a start-from-the-beginning, not an error.
        assertEquals("0", ExternalPlayer.vidHubPositionSeconds(-5_000L))
    }

    @Test
    fun `an absurd position is clamped into the range VidHub accepts`() {
        // One year of seconds is the top of its documented range; anything past
        // it is refused with error 102 rather than clamped by the player.
        assertEquals("31536000", ExternalPlayer.vidHubPositionSeconds(Long.MAX_VALUE))
        assertEquals(
            "31536000",
            ExternalPlayer.vidHubPositionSeconds(31_536_000_000L + 999_999L)
        )
    }

    @Test
    fun `values are percent encoded exactly once`() {
        // A query value with spaces, an ampersand and a non-ASCII title: the
        // space must be %20 (not \"+\", which a query parser reads literally),
        // and the ampersand must not be able to split the query.
        val url = ExternalPlayer.vidHubPlayUrl(
            url = "https://cdn.example/a b.mkv?token=x&y=z",
            title = "Amélie & Co",
            positionMs = 0L
        )
        assertFalse("a raw space leaked into the query", url.contains(" "))
        assertFalse("a raw ampersand leaked out of the url value", url.contains("token=x&y"))
        assertTrue(url, url.contains("url=https%3A%2F%2Fcdn.example%2Fa%20b.mkv%3Ftoken%3Dx%26y%3Dz"))
        assertTrue(url, url.contains("filename=Am%C3%A9lie%20%26%20Co"))
        assertFalse("the encoder's form-encoding '+' survived", url.contains("+"))
    }

    @Test
    fun `a blank title is left out instead of sent empty`() {
        val withoutTitle = ExternalPlayer.vidHubPlayUrl("https://x/y.mp4", null, 0L)
        assertFalse(withoutTitle, withoutTitle.contains("filename="))
        val blankTitle = ExternalPlayer.vidHubPlayUrl("https://x/y.mp4", "   ", 0L)
        assertFalse(blankTitle, blankTitle.contains("filename="))
    }

    @Test
    fun `the probed scheme is the one the request is addressed to`() {
        // Discovery and launch have to agree on the scheme, or the player is
        // found and then cannot be opened.
        assertTrue(
            "the probe must use the scheme the request is built on",
            ExternalPlayer.VIDHUB_PLAY_PROBE.startsWith("${ExternalPlayer.VIDHUB_SCHEME}://")
        )
        assertTrue(
            "the request must be built on the probed URL",
            ExternalPlayer.vidHubPlayUrl("https://x/y.mp4", null, 0L)
                .startsWith(ExternalPlayer.VIDHUB_PLAY_PROBE)
        )
        assertTrue(
            "the scheme list must name VidHub's scheme",
            ExternalPlayer.schemePlayerSchemes.contains(ExternalPlayer.VIDHUB_SCHEME)
        )
    }
}
