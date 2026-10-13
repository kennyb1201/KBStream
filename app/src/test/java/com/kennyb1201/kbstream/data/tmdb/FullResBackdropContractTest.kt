package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-resolution backdrops, and where they are allowed to appear.
 *
 * A backdrop drawn across a whole 3840px panel was being pulled at w1280 - a
 * 3x upscale. The rule that fixes it is a pure function over URL shapes
 * ([TmdbRepository.fullResBackdropUrl]), so the shapes are executed here; the
 * half a unit test cannot reach is WHERE it is applied, because the fix is
 * worthless if it also drags the card surfaces up with it: those are the
 * high-count surfaces, and w1280 is already more pixels than a card shows. So
 * the second half of this file is the repo's usual source contract - the three
 * full-screen sites call the rule, and the card sites (LandscapeArt's rail
 * cards, the BYW featured strip, the hero artwork resolver that feeds both)
 * still build w1280.
 */
class FullResBackdropContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    // ── the rule ───────────────────────────────────────────────────────────

    @Test
    fun `an absent or blank url stays null`() {
        assertNull(TmdbRepository.fullResBackdropUrl(null))
        assertNull(TmdbRepository.fullResBackdropUrl(""))
        assertNull(TmdbRepository.fullResBackdropUrl("   "))
    }

    @Test
    fun `a bare tmdb path resolves against the full-size base`() {
        // Add-on metas and history rows carry these; Coil cannot load a path.
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            TmdbRepository.fullResBackdropUrl("/abc.jpg")
        )
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            TmdbRepository.fullResBackdropUrl("abc.jpg")
        )
    }

    @Test
    fun `every tmdb size becomes original, the file left alone`() {
        listOf("w1280", "w780", "w500", "w185", "original").forEach { size ->
            assertEquals(
                "the $size URL must be served at full resolution",
                "https://image.tmdb.org/t/p/original/abc.jpg",
                TmdbRepository.fullResBackdropUrl(
                    "https://image.tmdb.org/t/p/$size/abc.jpg"
                )
            )
        }
        // http, not https: TMDB's host either way, so it is still ours to size.
        assertEquals(
            "https://image.tmdb.org/t/p/original/abc.jpg",
            TmdbRepository.fullResBackdropUrl("http://image.tmdb.org/t/p/w1280/abc.jpg")
        )
    }

    @Test
    fun `art that is not tmdb's is returned untouched`() {
        // Rewriting the host of someone else's URL would turn working art into a
        // 404, so anything off TMDB's image host is left exactly as it came in.
        listOf(
            "https://assets.fanart.tv/fanart/movies/1/backdrop/abc.jpg",
            "https://cdn.example.com/art/t/p/w1280-abc.jpg",
            "http://images.example.org/backdrop.jpg"
        ).forEach { url ->
            assertEquals(url, TmdbRepository.fullResBackdropUrl(url))
        }
    }

    @Test
    fun `the default backdrop base is a second constant, not a change to the first`() {
        assertEquals("https://image.tmdb.org/t/p/w1280", TmdbRepository.BACKDROP_BASE)
        assertEquals("https://image.tmdb.org/t/p/original", TmdbRepository.BACKDROP_BASE_FULL)
        assertEquals("https://image.tmdb.org/t/p/w500", TmdbRepository.POSTER_BASE)
        assertEquals("https://image.tmdb.org/t/p/w185", TmdbRepository.PROFILE_BASE)
        assertEquals("https://image.tmdb.org/t/p/w780", TmdbRepository.LOGO_BASE)
    }

    // ── where it is used ───────────────────────────────────────────────────

    @Test
    fun `the three full-screen surfaces pull their backdrop at full resolution`() {
        assertTrue(
            "the player's loading splash is the surface the report came from",
            readSource(SPLASH).contains("TmdbRepository.fullResBackdropUrl(backdropUrl)")
        )
        assertTrue(
            "the detail hero fills the screen",
            readSource(DETAIL).contains(
                "TmdbRepository.fullResBackdropUrl(TmdbRepository.BACKDROP_BASE + it)"
            )
        )

        val home = readSource(HOME_SCREEN)
        assertTrue(
            "the home hero upgrades the backdrop where it draws it - the rung " +
                "under the viewer's own choice, which is upgraded for the same " +
                "reason the hero's own art is (see ImageOverrideArt): the hero " +
                "fills the screen either way",
            home.contains("val backdropArt = imageOverrideBackdropUrl(") &&
                home.contains("fallbackUrl = TmdbRepository.fullResBackdropUrl(")
        )
        assertTrue(
            "the poster rungs stay outside the upgrade - a poster is not a backdrop",
            home.contains("val backdrop = backdropArt")
        )
    }

    @Test
    fun `the splash's clearlogo keeps its sized base`() {
        // LOGO_BASE documents why: original wordmark PNGs run to megabytes and
        // are software-decoded for the brand analysis. Only the backdrop moves.
        val splash = readSource(SPLASH)
        assertTrue(
            splash.contains("absoluteTmdbArt(clearLogoUrl, TmdbRepository.LOGO_BASE)")
        )
        assertFalse(splash.contains("fullResBackdropUrl(clearLogoUrl)"))
    }

    @Test
    fun `the card surfaces still build their backdrops at w1280`() {
        listOf(
            "the LandscapeArt rail card" to LANDSCAPE_ART,
            "the because-you-watched featured strip" to BYW
        ).forEach { (what, path) ->
            val src = readSource(path)
            assertTrue(
                "$what must keep TMDB's card size",
                src.contains("TmdbRepository.BACKDROP_BASE + it")
            )
            assertFalse(
                "$what is not a full-screen surface",
                src.contains("fullResBackdropUrl")
            )
        }
    }

    @Test
    fun `the hero's own url is still resolved and stored at w1280`() {
        // The upgrade happens at the display site so the URL the hero art is
        // resolved to - which rail cards and the Coil cache are keyed on - is
        // untouched, and the ViewModel never learns about a second size.
        listOf(
            "the hero artwork repository" to HERO_ARTWORK_REPO,
            "the home view model" to HOME_VIEW_MODEL
        ).forEach { (what, path) ->
            val src = readSource(path)
            assertTrue(
                "$what must keep resolving w1280",
                src.contains("TmdbRepository.BACKDROP_BASE + it")
            )
            assertFalse(
                "$what must not know about the full-resolution size",
                src.contains("fullResBackdropUrl")
            )
        }
    }

    private companion object {
        const val SPLASH = "com/kennyb1201/kbstream/ui/components/AutoPlayLoadSplash.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val LANDSCAPE_ART = "com/kennyb1201/kbstream/ui/components/LandscapeArt.kt"
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val HERO_ARTWORK_REPO =
            "com/kennyb1201/kbstream/data/tmdb/TmdbHeroArtworkRepository.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
