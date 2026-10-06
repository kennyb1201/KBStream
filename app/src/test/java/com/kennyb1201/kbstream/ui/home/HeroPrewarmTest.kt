package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.HeroArtwork
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbVideo
import com.kennyb1201.kbstream.data.tmdb.TmdbVideos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the Home hero pre-warms with: which TMDB video counts as its
 * trailer, and which artwork URLs get their bytes warmed.
 *
 * Both are read from more than one place in HomeViewModel (the trailer key by
 * the early publish and the final one, the targets by the prefetch), so a
 * change in either rule is a real behaviour change and is pinned here.
 */
class HeroPrewarmTest {

    private fun detail(vararg videos: TmdbVideo) =
        TmdbDetail(id = 603, videos = TmdbVideos(results = videos.toList()))

    private fun video(
        key: String,
        site: String = "YouTube",
        type: String = "Trailer"
    ) = TmdbVideo(key = key, site = site, type = type)

    @Test
    fun `no detail means no trailer`() {
        assertNull(heroTrailerKeyOf(null))
    }

    @Test
    fun `a detail with no videos has no trailer`() {
        assertNull(heroTrailerKeyOf(TmdbDetail(id = 603)))
        assertNull(heroTrailerKeyOf(detail()))
    }

    @Test
    fun `the first YouTube trailer in TMDB order wins`() {
        assertEquals(
            "aaa",
            heroTrailerKeyOf(
                detail(
                    video("aaa"),
                    video("bbb")
                )
            )
        )
    }

    @Test
    fun `only a real trailer qualifies - the hero autoplays what it finds`() {
        // Deliberately stricter than TrailerPick, which the Detail page's
        // trailer button uses: a teaser or a clip must not autoplay over Home.
        assertNull(heroTrailerKeyOf(detail(video("t1", type = "Teaser"))))
        assertNull(heroTrailerKeyOf(detail(video("c1", type = "Clip"))))
        assertNull(heroTrailerKeyOf(detail(video("f1", type = "Featurette"))))
    }

    @Test
    fun `site and type are matched case-insensitively`() {
        assertEquals(
            "up1",
            heroTrailerKeyOf(
                detail(
                    video("up1", site = "youtube", type = "trailer")
                )
            )
        )
    }

    @Test
    fun `non-YouTube and keyless candidates are skipped, not returned`() {
        assertEquals(
            "right",
            heroTrailerKeyOf(
                detail(
                    video("vimeo", site = "Vimeo"),
                    video(""),
                    video("right")
                )
            )
        )
        assertNull(
            heroTrailerKeyOf(
                detail(
                    video("vimeo", site = "Vimeo"),
                    video("")
                )
            )
        )
    }

    @Test
    fun `an unknown artwork object warms nothing`() {
        assertTrue(heroArtWarmTargets(null).isEmpty())
    }

    @Test
    fun `blank artwork urls are not warmed`() {
        assertTrue(heroArtWarmTargets(HeroArtwork(backdropUrl = null, logoUrl = null)).isEmpty())
        assertTrue(heroArtWarmTargets(HeroArtwork(backdropUrl = "", logoUrl = "  ")).isEmpty())
    }

    @Test
    fun `the backdrop is warmed before the clearlogo, at the documented sizes`() {
        val targets =
            heroArtWarmTargets(
                HeroArtwork(
                    backdropUrl = "https://image.tmdb.org/t/p/w1280/back.jpg",
                    logoUrl = "https://image.tmdb.org/t/p/w780/logo.png"
                )
            )

        assertEquals(2, targets.size)
        assertEquals("https://image.tmdb.org/t/p/w1280/back.jpg", targets[0].url)
        assertEquals("https://image.tmdb.org/t/p/w780/logo.png", targets[1].url)

        // Thumbnails, not the hero's own sizes: the warm exists for the disk
        // entry (keyed by URL), so the decode is meant to stay cheap.
        targets.forEach { target ->
            assertTrue("warm size must stay a thumbnail", target.width <= 640)
            assertTrue("warm size must stay a thumbnail", target.height <= 360)
        }
    }

    @Test
    fun `a backdrop with no clearlogo still warms that backdrop`() {
        val targets =
            heroArtWarmTargets(
                HeroArtwork(
                    backdropUrl = "https://image.tmdb.org/t/p/w1280/back.jpg",
                    logoUrl = null
                )
            )

        assertEquals(listOf("https://image.tmdb.org/t/p/w1280/back.jpg"), targets.map { it.url })
    }

    @Test
    fun `the image warm is bounded well inside the URL prefetch`() {
        // The URL prefetch is TMDB JSON; the image warm is real traffic. It
        // must stay a small fraction of it or a Home build would spend
        // megabytes filling a cache for rows nobody has reached yet.
        assertTrue(HERO_ART_IMAGE_WARM_LIMIT in 1..24)
    }
}
