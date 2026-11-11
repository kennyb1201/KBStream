package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the browse long-press "Play trailer" row.
 *
 * The row is one shared builder used by five screens, and its whole point is
 * that the fetch is LIGHT (videos only, no Detail payload) and that a press
 * with nothing to play answers with a toast instead of navigating. Every one of
 * those is a property of source text - the endpoints, which repository call the
 * builder makes, what it does when the pick is null, and who calls it - so the
 * contract reads the files rather than trying to compose a menu.
 *
 * It also pins the negative: Home rails, Detail, Library and search are NOT
 * browse surfaces, and a trailer row appearing there would be a second way to
 * do what the hero or the Detail Trailer button already does.
 */
class TrailerMenuContractTest {

    // ── F1: the videos-only fetch ─────────────────────────────────────────

    @Test
    fun `the videos endpoints are the light pair, not the detail fetch`() {
        assertTrue(
            "F1: a poster menu's trailer lookup is movie/{id}/videos",
            api.contains("@GET(\"movie/{id}/videos\")")
        )
        assertTrue(
            "F1: and the series twin is tv/{id}/videos",
            api.contains("@GET(\"tv/{id}/videos\")")
        )
        assertEquals(
            "F1: both endpoints answer with the videos shape TrailerPick already takes",
            2,
            api.count("): TmdbVideos")
        )
        assertFalse(
            "F1: the light call must not ask TMDB for the appended Detail payload",
            api.slice("@GET(\"movie/{id}/videos\")", "@GET(\"movie/{id}/external_ids\")")
                .contains("append_to_response")
        )
    }

    @Test
    fun `videosFor splits on the repository's own series normalizer`() {
        val body = videosFor
        assertTrue(
            "F1: the endpoint is chosen by the same normalizer every other call keys on, " +
                "so tv/show/anime spellings all reach the tv endpoint",
            body.contains("normalizeType(type.orEmpty()) == \"series\"")
        )
        assertTrue(
            "F1: series asks the tv endpoint",
            body.contains("api.getTvVideos(tmdbId, apiKey)")
        )
        assertTrue(
            "F1: everything else asks the movie one",
            body.contains("api.getMovieVideos(tmdbId, apiKey)")
        )
        assertTrue(
            "F1: the caller wants the video list, not the wrapper",
            body.count(".results") == 2
        )
    }

    @Test
    fun `videosFor is uncached and answers empty on failure`() {
        val body = videosFor
        assertFalse(
            "F1: one press, one call - no cache on this path (a pre-fetch/short-TTL " +
                "cache here would cost a call or a stale answer per long-press)",
            body.lowercase().contains("cache")
        )
        assertTrue(
            "F1: no key means no call, not a crash",
            body.contains("if (apiKey.isBlank()) return emptyList()")
        )
        assertTrue(
            "F1: a failed lookup answers the same thing a title with no trailer does",
            body.contains("emptyList()") && body.contains("getOrElse")
        )
    }

    // ── F2: the row ───────────────────────────────────────────────────────

    @Test
    fun `the row is labelled and described once, for every surface`() {
        val row = builder
        assertTrue("F2: label", row.contains("label = \"Play trailer\""))
        assertTrue("F2: description", row.contains("description = \"Watch this title's trailer\""))
        assertEquals(
            "F2: one builder - the other surfaces must not spell the row out themselves",
            1,
            menuSource.count("label = \"Play trailer\"")
        )
    }

    @Test
    fun `the press dismisses first, then fetches, picks and plays`() {
        val row = builder
        val dismiss = row.indexOf("onDismissFirst()")
        val launch = row.indexOf("launch {")
        val fetch = row.indexOf("videosFor(tmdbId, type)")
        val pick = row.indexOf("TrailerPick.best(")
        val play = row.indexOf("DetailTrailerLaunch.play(")
        assertTrue("F2: the dismiss happens", dismiss >= 0)
        assertTrue("F2: dismiss comes before the launch, the action contract", dismiss < launch)
        assertTrue("F2: the launch comes before the fetch", launch < fetch)
        assertTrue("F2: the fetch comes before the pick", fetch < pick)
        assertTrue("F2: the pick comes before the hand-off", pick < play)
        assertTrue(
            "F2: the hand-off passes the ids the menu has, so the player's retry can " +
                "re-resolve with the same IMDb leg",
            row.contains("imdbId = imdbId") &&
                row.contains("tmdbId = tmdbId") &&
                row.contains("type = type")
        )
    }

    @Test
    fun `nothing to play is a toast, never a navigation`() {
        val row = builder
        assertTrue(
            "F2: the exact words the settler's step 2 expects",
            menuSource.contains("NO_TRAILER_TOAST = \"No trailer for this title\"") &&
                row.contains("Toast.makeText(context, NO_TRAILER_TOAST, Toast.LENGTH_SHORT).show()")
        )
        assertTrue(
            "F2: both dead ends (no TMDB id, no video) answer with the toast",
            row.count("NO_TRAILER_TOAST") >= 2
        )
        assertFalse(
            "F2: a press for a trailer must not open Detail as a fallback - a surprise " +
                "navigation out of the grid is worse than the toast",
            row.contains("onNavigateDetail") ||
                row.contains("onOpenItem") ||
                row.contains("NativePlayerActivity")
        )
        assertFalse(
            "F2: and it must not restore focus into the grid while the player is starting",
            row.contains("requestFocus")
        )
    }

    @Test
    fun `the fetch cannot be cancelled by the dismiss that starts it`() {
        assertEquals(
            "F2: an own scope, not the screen's rememberCoroutineScope - the call sites " +
                "dismiss by clearing state that composes the menu block, which disposes " +
                "that remembered scope in the frame the press started it",
            0,
            builder.count("rememberCoroutineScope")
        )
        assertTrue(
            "F2: and the scope is Main.immediate so the toast and the start are on the main thread",
            menuSource.contains(
                "CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"
            )
        )
    }

    // ── F3: the call sites ────────────────────────────────────────────────

    @Test
    fun `every browse surface uses the one builder`() {
        BROWSE.forEach { (path, ids) ->
            val source = source(path)
            assertEquals(
                "F3: $path adds the row exactly once",
                1,
                source.count("trailerMenuAction(")
            )
            ids.forEach { id ->
                assertTrue("F3: $path passes $id", source.contains(id))
            }
        }
        assertEquals(
            "F3: every browse menu, and only browse menus",
            BROWSE.size,
            BROWSE.keys.count { source(it).contains("trailerMenuAction(") }
        )
    }

    @Test
    fun `the row sits directly after Go to Details`() {
        BROWSE.forEach { (path, _) ->
            val source = source(path)
            val details = source.indexOf("label = \"Go to Details\"")
            val row = source.indexOf("trailerMenuAction(")
            assertTrue("F3: $path has a Go to Details row first", details in 0 until row)
            val watched = source.indexOf("watchedMenuLabel(", row)
            // Some surfaces spell the toggle out inline (their own state name
            // is `watched`, not `isWatched`), so both shapes count.
            val literalWatched = source.indexOf("label = if (", row)
            val next = listOf(watched, literalWatched).filter { it > row }.minOrNull()
            assertTrue(
                "F3: $path places the trailer row before the watched toggle",
                next != null && row < next
            )
        }
    }

    @Test
    fun `the TV surfaces hand the focus back before the player covers them`() {
        assertTrue(
            "F3: the catalog grid dismisses through closeMenu, the same path its watched row " +
                "uses - a bare state clear would leave the D-pad with nothing focused when " +
                "the trailer's Back returns to the grid",
            source(BROWSE.keys.first()).contains("onDismissFirst = { closeMenu(targetKey) }")
        )
        assertTrue(
            "F3: the decade rail dismisses through dismissRailMenu, which hands focus back",
            source("$ROOT/ui/decade/DecadeScreen.kt")
                .contains("onDismissFirst = { dismissRailMenu() }")
        )
    }

    @Test
    fun `the surfaces that already play trailers are untouched`() {
        OUT_OF_SCOPE.forEach { path ->
            assertFalse(
                "F3: $path is not a browse surface - the hero or the Trailer button already " +
                    "plays trailers there, so it must not grow a second way in",
                source(path).contains("trailerMenuAction(")
            )
        }
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("missing start marker: $startMarker", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("missing end marker: $endMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
    }

    private val api: String by lazy { source(TMDB_API) }

    /** The repository's `videosFor` and nothing else. */
    private val videosFor: String by lazy {
        source(TMDB_REPO).slice(
            "suspend fun videosFor(",
            "\n    suspend fun getSeasonEpisodes("
        )
    }

    private val menuSource: String by lazy { source(MENU) }

    /** The builder's KDoc-free body: from its signature to the next declaration. */
    private val builder: String by lazy {
        menuSource.slice("fun trailerMenuAction(", "\n/**\n * One selectable row")
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

    private companion object {
        const val ROOT = "com/kennyb1201/kbstream"

        const val TMDB_API = "$ROOT/data/tmdb/TmdbApiService.kt"
        const val TMDB_REPO = "$ROOT/data/tmdb/TmdbRepository.kt"
        const val MENU = "$ROOT/ui/components/PosterContextMenu.kt"

        /**
         * The browse menus, each with the id expressions it must pass.
         *
         * The first five are the spec's table; the last three are the same rule
         * applied to the surfaces it says to extend rather than enumerate: the
         * studio and tag pages the Browse tab opens, and the phone twin of the
         * collection screen (the spec's table lists the TV one).
         */
        val BROWSE = linkedMapOf(
            "$ROOT/ui/home/CatalogGridScreen.kt" to listOf(
                "tmdbId = gridIds.tmdbId",
                "imdbId = gridIds.imdbId"
            ),
            "$ROOT/ui/collection/CollectionScreen.kt" to listOf(
                "tmdbId = part.id",
                "type = \"movie\""
            ),
            "$ROOT/ui/decade/DecadeScreen.kt" to listOf(
                "tmdbId = studioItem.item.id",
                "type = menuMediaType"
            ),
            "$ROOT/ui/mobile/BrowseSectionsMobile.kt" to listOf(
                "tmdbId = selected.item.id",
                "type = mediaType"
            ),
            "$ROOT/ui/mobile/CatalogGridScreenMobile.kt" to listOf(
                "tmdbId = gridIds.tmdbId",
                "imdbId = gridIds.imdbId"
            ),
            "$ROOT/ui/studio/StudioScreen.kt" to listOf(
                "tmdbId = studioItem.item.id",
                "type = menuMediaType"
            ),
            "$ROOT/ui/tag/TagScreen.kt" to listOf(
                "tmdbId = studioItem.item.id",
                "type = menuMediaType"
            ),
            "$ROOT/ui/mobile/CollectionScreenMobile.kt" to listOf(
                "tmdbId = part.id",
                "type = \"movie\""
            )
        )

        /** Surfaces that already play trailers, or are not browse screens. */
        val OUT_OF_SCOPE = listOf(
            "$ROOT/ui/home/HomeScreen.kt",
            "$ROOT/ui/mobile/HomeScreenMobile.kt",
            "$ROOT/ui/detail/DetailScreen.kt",
            "$ROOT/ui/mobile/DetailScreenMobile.kt",
            "$ROOT/ui/library/LibraryScreen.kt",
            "$ROOT/ui/mobile/LibraryScreenMobile.kt",
            "$ROOT/ui/search/SearchScreen.kt",
            "$ROOT/ui/mobile/SearchScreenMobile.kt",
            "$ROOT/ui/actor/ActorScreen.kt",
            "$ROOT/ui/mobile/ActorScreenMobile.kt",
            "$ROOT/ui/kb/KBFolderScreen.kt",
            "$ROOT/ui/mobile/KBFolderScreenMobile.kt"
        )
    }
}
