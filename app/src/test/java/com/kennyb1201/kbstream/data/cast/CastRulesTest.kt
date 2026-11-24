package com.kennyb1201.kbstream.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast sender's rules: what may go to a television, and what must be
 * refused with an honest reason instead.
 *
 * The verdicts are the whole feature on a bad day - the receiver is Play's
 * DEFAULT media receiver, which fetches a URL from the television's own
 * connection, so a header-gated or address-bound link does not fail here; it
 * fails on the TV as a black screen. Refusing it up front, in the phone's own
 * words, is what the spec asks for, and this is where that rule is pinned.
 */
class CastRulesTest {

    private fun verdict(
        url: String? = "https://cdn.example.com/movie.mp4",
        headers: Map<String, String>? = null,
        drm: Boolean = false,
        torrent: Boolean = false,
        phone: Boolean = true,
        kids: Boolean = false
    ) = castVerdict(
        url = url,
        headers = headers,
        drmProtected = drm,
        torrentOnly = torrent,
        title = "A Title",
        subtitle = "S1 E2",
        posterUrl = "https://cdn.example.com/poster.jpg",
        startPositionMs = 42_000L,
        phone = phone,
        kidsProfile = kids
    )

    private fun refusalOf(verdict: CastVerdict): CastRefusal =
        (verdict as CastVerdict.Refused).reason

    @Test
    fun `a plain https link is castable with what the receiver needs`() {
        val plan = (verdict() as CastVerdict.Castable).plan
        assertEquals("https://cdn.example.com/movie.mp4", plan.url)
        assertEquals("video/mp4", plan.contentType)
        assertEquals("A Title", plan.title)
        assertEquals("S1 E2", plan.subtitle)
        assertEquals("https://cdn.example.com/poster.jpg", plan.posterUrl)
        assertEquals(42_000L, plan.startPositionMs)
    }

    @Test
    fun `the sender does not exist on a television, and never on a kids profile`() {
        assertEquals(CastRefusal.NOT_A_PHONE, refusalOf(verdict(phone = false)))
        assertEquals(CastRefusal.KIDS_PROFILE, refusalOf(verdict(kids = true)))
        assertFalse(castSenderAllowed(phone = false, kidsProfile = false))
        assertFalse(castSenderAllowed(phone = true, kidsProfile = true))
        assertTrue(castSenderAllowed(phone = true, kidsProfile = false))
    }

    @Test
    fun `a source the default receiver cannot fetch is refused, with its reason`() {
        // Each of these is a real class of link the picker can hand the player.
        assertEquals(
            CastRefusal.HEADER_PROTECTED,
            refusalOf(verdict(headers = mapOf("Referer" to "https://provider.example")))
        )
        assertEquals(CastRefusal.DRM_PROTECTED, refusalOf(verdict(drm = true)))
        assertEquals(CastRefusal.NO_DIRECT_URL, refusalOf(verdict(url = null)))
        assertEquals(CastRefusal.NO_DIRECT_URL, refusalOf(verdict(url = "   ")))
        assertEquals(CastRefusal.NO_DIRECT_URL, refusalOf(verdict(torrent = true)))
        assertEquals(
            CastRefusal.UNSUPPORTED_SCHEME,
            refusalOf(verdict(url = "magnet:?xt=urn:btih:abc"))
        )
    }

    @Test
    fun `a link signed for this phone's address is refused rather than cast into a black screen`() {
        // The receiver fetches from a different device on a different
        // connection, which is exactly what such a signature refuses.
        assertTrue(addressBound("https://cdn.example.com/a.m3u8?ip=203.0.113.9&t=abc"))
        assertTrue(addressBound("https://cdn.example.com/a.mp4?client_ip=203.0.113.9"))
        assertTrue(addressBound("https://cdn.example.com/a.mp4?X_Forwarded_For=203.0.113.9"))
        assertFalse(addressBound("https://cdn.example.com/a.mp4?token=abc"))
        assertFalse(addressBound("https://cdn.example.com/a.mp4"))
        // A key that merely STARTS with one of the markers is not it.
        assertFalse(addressBound("https://cdn.example.com/a.mp4?ipad=1"))
        assertEquals(
            CastRefusal.ADDRESS_BOUND,
            refusalOf(verdict(url = "https://cdn.example.com/a.mp4?ip=203.0.113.9"))
        )
    }

    @Test
    fun `the media type comes from the link, not from a guess`() {
        // The receiver picks its player from this string: HLS announced as
        // progressive MP4 plays for a few seconds and stops.
        assertEquals("application/x-mpegURL", castContentType("https://e.com/live.m3u8"))
        assertEquals(
            "application/x-mpegURL",
            castContentType("https://e.com/live.m3u8?token=abc&expires=1")
        )
        assertEquals("application/dash+xml", castContentType("https://e.com/live.mpd"))
        assertEquals("video/webm", castContentType("https://e.com/a.webm"))
        assertEquals("video/mp4", castContentType("https://e.com/movie.mp4"))
        assertEquals("video/mp4", castContentType("https://e.com/stream"))
    }

    @Test
    fun `a negative resume point is sent as the beginning`() {
        val plan = (
            castVerdict(
                url = "https://e.com/movie.mp4",
                headers = null,
                drmProtected = false,
                torrentOnly = false,
                title = "T",
                subtitle = null,
                posterUrl = null,
                startPositionMs = -5_000L,
                phone = true,
                kidsProfile = false
            ) as CastVerdict.Castable
            ).plan
        assertEquals(0L, plan.startPositionMs)
    }
}
