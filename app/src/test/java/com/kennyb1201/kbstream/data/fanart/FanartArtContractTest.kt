package com.kennyb1201.kbstream.data.fanart

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fanart.tv as the artwork gap-filler (spec 2026-11-13).
 *
 * Four thing classes are pinned here, and nothing else:
 *
 *  1. the PURE rules - the URLs, the pick order, the exact-season match and the
 *     cache TTLs - because those decide what an English wordmark and a season
 *     poster are, and a JVM test can exercise every branch of them;
 *  2. the KEY wiring: a project key in the build (public by construction, never
 *     named a secret) and the viewer's own key in Settings, synced;
 *  3. the SURFACES: Simkl clearlogo, Detail/picker season posters, the backdrop
 *     ladder - each reached only where TMDB answered nothing;
 *  4. the fail-soft contract: no key is silent, and a failure can never be
 *     cached as an answer.
 */
class FanartArtContractTest {

    // ── 1. The URLs ──────────────────────────────────────────────────────

    @Test
    fun `movies go by TMDB id and shows by TVDB id`() {
        assertEquals(
            "https://webservice.fanart.tv/v3/movies/603?api_key=PROJECT",
            fanartMoviesUrl(tmdbId = 603, apiKey = "PROJECT", clientKey = null)
        )
        assertEquals(
            "https://webservice.fanart.tv/v3/tv/81189?api_key=PROJECT",
            fanartTvUrl(tvdbId = 81189, apiKey = "PROJECT", clientKey = null)
        )
        assertTrue("v3.2 base", FANART_API_BASE.startsWith("https://webservice.fanart.tv/v3"))
    }

    @Test
    fun `the personal key rides only when the viewer entered one`() {
        assertTrue(
            fanartMoviesUrl(603, "PROJECT", "PERSONAL").contains("&client_key=PERSONAL")
        )
        assertFalse(
            "a blank personal key must leave the request on the project tier",
            fanartMoviesUrl(603, "PROJECT", "").contains("client_key")
        )
        assertFalse(fanartMoviesUrl(603, "PROJECT", null).contains("client_key"))
    }

    @Test
    fun `a query value is encoded, not pasted raw`() {
        assertEquals("A%20B%2F%26", fanartEncode("A B/&"))
        assertEquals("abc123", fanartEncode("abc123"))
    }

    // ── 2. The pick order ────────────────────────────────────────────────

    @Test
    fun `English wins, then a language-neutral entry, then the rest`() {
        val foreign = FanartImage("https://x/de.png", lang = "de", likes = 99)
        val neutral = FanartImage("https://x/none.png", lang = null, likes = 50)
        val english = FanartImage("https://x/en.png", lang = "en", likes = 1)

        assertEquals(english, pickFanartImage(listOf(foreign, neutral, english)))
        assertEquals(neutral, pickFanartImage(listOf(foreign, neutral)))
        // Never nothing: a title with only foreign art still gets its logo.
        assertEquals(foreign, pickFanartImage(listOf(foreign)))
    }

    @Test
    fun `likes rank inside one language`() {
        val quiet = FanartImage("https://x/a.png", lang = "en", likes = 2)
        val loud = FanartImage("https://x/b.png", lang = "en", likes = 20)
        assertEquals(loud, pickFanartImage(listOf(quiet, loud)))
        assertEquals(
            "the API's own order breaks a tie",
            quiet,
            pickFanartImage(listOf(quiet, FanartImage("https://x/c.png", lang = "en", likes = 2)))
        )
    }

    // ── 3. Season posters: exact, null-based, never another season ───────

    @Test
    fun `seasonposter matches the number exactly`() {
        val posters = listOf(
            FanartImage("https://x/s1.png", lang = "en", likes = 5, season = "1"),
            FanartImage("https://x/s2.png", lang = "en", likes = 5, season = "2"),
            FanartImage("https://x/s2b.png", lang = "en", likes = 9, season = "2")
        )
        assertEquals("https://x/s2b.png", fanartSeasonPoster(posters, 2)?.url)
        assertEquals("https://x/s1.png", fanartSeasonPoster(posters, 1)?.url)
        assertNull("never another season's art", fanartSeasonPoster(posters, 3))
    }

    @Test
    fun `the all bucket is not a season and Specials is`() {
        val posters = listOf(
            FanartImage("https://x/all.png", lang = "en", likes = 500, season = "all"),
            FanartImage("https://x/s0.png", lang = "en", likes = 1, season = "0")
        )
        assertNull("`all` must never answer a season request", fanartSeasonPoster(posters, 5))
        assertEquals(
            "season 0 is Specials, a real season - the check must be null-based",
            "https://x/s0.png",
            fanartSeasonPoster(posters, 0)?.url
        )
    }

    @Test
    fun `previews swap the path, not the request`() {
        assertEquals(
            "https://assets.fanart.tv/bigpreview/tv/81189/seasonposter/1.jpg",
            fanartBigPreviewUrl("https://assets.fanart.tv/fanart/tv/81189/seasonposter/1.jpg")
        )
        assertEquals(
            "https://assets.fanart.tv/preview/movies/x.jpg",
            fanartPreviewUrl("https://assets.fanart.tv/fanart/movies/x.jpg")
        )
    }

    // ── 4. The cache ─────────────────────────────────────────────────────

    @Test
    fun `the TTLs are the entity-logo pair, and a failure can never outlive a miss`() {
        assertEquals(12L * 60L * 60L * 1000L, FANART_MEMORY_TTL_MS)
        assertEquals(30L * 24L * 60L * 60L * 1000L, FANART_DISK_TTL_MS)
        assertEquals(24L * 60L * 60L * 1000L, FANART_MISS_TTL_MS)
        assertEquals(60L * 60L * 1000L, FANART_FAILURE_TTL_MS)
        assertTrue(FANART_FAILURE_TTL_MS < FANART_MISS_TTL_MS)
        assertTrue(FANART_MEMORY_TTL_MS < FANART_DISK_TTL_MS)
    }

    @Test
    fun `one key per title, one spelling, and the id spaces cannot collide`() {
        assertEquals("fanart_art_v1:movie:603", fanartCacheKey(FanartKind.Movie, 603))
        assertEquals("fanart_art_v1:tv:603", fanartCacheKey(FanartKind.Tv, 603))
        assertTrue(fanartCacheKey(FanartKind.Tv, 5).startsWith(FANART_CACHE_DISK_PREFIX))
    }

    // ── 5. The key wiring ────────────────────────────────────────────────

    @Test
    fun `the project key is a build value wired like TMDB's, never a secret`() {
        val gradle = File(projectRoot(), "app/build.gradle.kts").readText()
        assertTrue(
            gradle.contains("localProps.getProperty(\"FANART_API_KEY\")") &&
                gradle.contains("System.getenv(\"FANART_API_KEY\")")
        )
        assertTrue(gradle.contains("buildConfigField(\"String\", \"FANART_API_KEY\""))

        val tv = source("$FANART_PKG/FanartTv.kt")
        assertTrue("the build's key is what the client reads", tv.contains("BuildConfig.FANART_API_KEY"))
        assertFalse(
            "the project key is public by construction - no SECRET naming may creep in",
            tv.contains("FANART_API_KEY_SECRET")
        )
        assertTrue(
            "no key means no request at all",
            tv.contains("if (apiKey.isBlank()) return FanartResult.Failed")
        )
    }

    @Test
    fun `the workflow passes the secret through to the build`() {
        val workflow = File(
            projectRoot(),
            ".github/workflows/build.yml"
        ).readText()
        assertTrue(workflow.contains("FANART_API_KEY: \${{ secrets.FANART_API_KEY }}"))
    }

    @Test
    fun `the personal key lives in the synced, encrypted key store`() {
        val prefs = source(
            "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        )
        assertTrue(prefs.contains("private const val KEY_FANART_CLIENT_KEY = \"fanart_client_key\""))
        val synced = prefs.substringAfter("val SYNCED_API_KEYS: List<String> = listOf(")
            .substringBefore(")")
        assertTrue(
            "paste it once, it arrives on the other device",
            synced.contains("KEY_FANART_CLIENT_KEY")
        )
        val setter = prefs.substringAfter("fun setFanartClientKey(")
            .substringBefore("fun getOpenSubtitles")
        assertTrue(setter.contains("stampApiKeyEdit(context, KEY_FANART_CLIENT_KEY"))
        assertTrue("publishes the blob now, so the other device need not wait", setter.contains("pushApiKeysBlob()"))
        assertTrue(setter.contains("prefs(context).edit().remove(KEY_FANART_CLIENT_KEY)"))
    }

    @Test
    fun `both Settings surfaces carry the row`() {
        val tv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
        val phone = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")
        listOf(tv, phone).forEach { screen ->
            assertTrue(screen.contains("\"fanart.tv Personal Key\""))
            assertTrue(
                "the help text says what it buys, plainly",
                screen.contains("new artwork appears in 2 days") ||
                    screen.contains("artwork appears in 2 days")
            )
            assertTrue(screen.contains("AppPreferences.setFanartClientKey(context"))
        }
    }

    // ── 6. Fail-soft ─────────────────────────────────────────────────────

    @Test
    fun `a failed call is never an answer`() {
        val client = source("$FANART_PKG/FanartTv.kt")
        assertTrue(client.contains("} catch (_: Exception) {"))
        assertTrue("429 parks the client for Retry-After", client.contains("response.code == 429"))
        assertTrue(client.contains("response.header(\"Retry-After\")"))
        assertTrue("404 is a proven miss", client.contains("response.code == 404"))

        val repository = source("$FANART_PKG/FanartArtRepository.kt")
        assertTrue(repository.contains("if (!FanartTv.isConfigured) return null"))
        assertTrue(
            "the miss marker is its own row, so it can never parse as art",
            repository.contains("FANART_DISK_MISS_JSON")
        )
        assertTrue(
            "a failure is only a reason not to hammer the service",
            repository.contains("failures[key] = System.currentTimeMillis()")
        )
    }

    // ── 7. The surfaces ──────────────────────────────────────────────────

    @Test
    fun `Simkl cards reach fanart only for the art they lack`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val builder = viewModel.substringAfter("private suspend fun buildSimklUpNextItem(")
            .substringBefore("private suspend fun ")
        assertTrue(builder.contains("fanartClearlogoFallback("))
        assertTrue(
            "the backdrop is a fallback too, and only a fallback",
            builder.contains("backdropUrl") &&
                builder.contains("?: fanartBackdropFallback(")
        )
        assertTrue(
            "shows go by their TVDB twin - fanart's TV endpoint has no TMDB id space",
            viewModel.contains("resolveTvdbId(it, \"series\")")
        )
    }

    @Test
    fun `Detail draws the season poster beside the heading it follows`() {
        listOf(
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        ).forEach { path ->
            val screen = source(path)
            assertTrue("$path asks the shared resolver", screen.contains("FanartArtRepository"))
            assertTrue(
                "$path follows the season selection",
                screen.contains("LaunchedEffect(normalizedType, tmdbDetail?.id, effectiveSeason)") ||
                    screen.contains("LaunchedEffect(normalizedType, tmdbDetail?.id, currentSeason)")
            )
            assertTrue(
                "$path writes the new poster only when it answers (no flash)",
                screen.contains("seasonPoster(")
            )
        }
    }

    @Test
    fun `both engines and both layouts carry the picker poster`() {
        val picker = source("com/kennyb1201/kbstream/ui/player/PlayerEpisodePicker.kt")
        assertTrue(picker.contains("suspend fun seasonPoster("))

        val mpv = source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        val native = source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        listOf(mpv, native).forEach { activity ->
            assertTrue(activity.contains("PlayerEpisodePicker.seasonPoster("))
            assertTrue(activity.contains("bindEpisodeSeasonPoster()"))
        }
        assertTrue(mpv.contains("R.id.mpv_picker_season_poster"))
        assertTrue(native.contains("R.id.picker_season_poster"))

        val tvLayout = File(projectRoot(), "app/src/main/res/layout/activity_mpv_player.xml").readText()
        val phoneLayout = File(projectRoot(), "app/src/main/res/layout/activity_player.xml").readText()
        assertTrue(tvLayout.contains("@+id/mpv_picker_season_poster"))
        assertTrue(phoneLayout.contains("@+id/picker_season_poster"))
        // Under the strip, above the rows.
        assertTrue(
            tvLayout.indexOf("mpv_picker_season_poster") > tvLayout.indexOf("mpv_picker_seasons") &&
                tvLayout.indexOf("mpv_picker_season_poster") < tvLayout.indexOf("mpv_picker_list")
        )
        assertTrue(
            phoneLayout.indexOf("picker_season_poster") > phoneLayout.indexOf("picker_seasons") &&
                phoneLayout.indexOf("picker_season_poster") < phoneLayout.indexOf("picker_list")
        )
    }

    @Test
    fun `the clearlogo and backdrop fallbacks keep TMDB first on both Detail pages`() {
        listOf(
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        ).forEach { path ->
            val screen = source(path)
            assertTrue("$path: TMDB's logo is the first rung", screen.contains("val clearLogoUrl = tmdbLogoUrl ?: fanartLogoUrl"))
            assertTrue(
                "$path: the backdrop ladder puts fanart below TMDB's art",
                screen.contains("?: fanartBackdropUrl") &&
                    screen.indexOf("backdropArtUrl") < screen.indexOf("?: fanartBackdropUrl")
            )
        }
    }

    @Test
    fun `nothing prefetches fanart`() {
        val repository = source("$FANART_PKG/FanartArtRepository.kt")
        assertTrue(
            "lazy only: the repository exposes lookups, never a sweep",
            !repository.contains("fun prefetch"))
        val tv = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
        assertTrue(
            "the Detail hero asks once per title, on a state/season key",
            tv.contains("LaunchedEffect(backdropArtUrl, tmdbDetail?.id, normalizedType)")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private companion object {
        const val FANART_PKG = "com/kennyb1201/kbstream/data/fanart"
    }

    private fun projectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/build.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("project root not found")
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("source root not found for $relative")
    }
}
