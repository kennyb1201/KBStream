package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.TmdbDetailLookup
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zombie Continue Watching cards: black tiles with no show name and a raw
 * id where the title should be.
 *
 * The rows behind them were filed under a parent id that names no title, so
 * every lookup keyed on it fails - no art, no name, and Detail opening on
 * `tmdb:3634822`. The fix has four legs, and each one is pinned here: no raw id
 * as a title (F2), the title printed once (F3), the zombie card dropped only on
 * a definitive 404 (F4), and the writer that stamps the id (F1, see
 * PlaybackHistoryIdsTest).
 */
class ZombieCardsTest {

    private val mainSource = findSourceRoot()
    private val projectRoot = mainSource.parentFile.parentFile.parentFile.parentFile

    private fun source(relative: String) =
        File(mainSource, relative).readText()

    private val mobileDetail = source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
    private val tvDetail = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
    private val homeViewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")

    // ── T1: never print a raw id as a title ─────────────────────────────────

    @Test
    fun `an id-like name is not a display title`() {
        assertNull(upNextDisplayTitleOrNull("tmdb:3634822"))
        assertNull(upNextDisplayTitleOrNull("imdb:tt1234567"))
        assertNull(upNextDisplayTitleOrNull("tt1234567"))
        assertNull(upNextDisplayTitleOrNull("   "))
        assertNull("a bare number is an id when nothing else identifies the card", upNextDisplayTitleOrNull("3634822", hasArtwork = false))
        assertEquals("3634822", upNextDisplayTitleOrNull("3634822", hasArtwork = true))
        assertEquals("Your Friends & Neighbors", upNextDisplayTitleOrNull("Your Friends & Neighbors"))
    }

    @Test
    fun `the mobile Detail screen never falls back to the id`() {
        assertTrue(
            "the initial meta's name is gated",
            mobileDetail.contains("name = upNextDisplayTitleOrNull(initialTarget?.displayName)")
        )
        assertTrue(
            "and says Unknown Show when there is no name",
            mobileDetail.contains("?: UNKNOWN_SHOW_NAME")
        )
        assertEquals(
            "both context menus use the gated name",
            2,
            Regex("title = displayName\\.ifBlank \\{ UNKNOWN_SHOW_NAME \\}").findAll(mobileDetail).count()
        )
        assertEquals(
            "no title or name fallback may be the raw id: " + titleFallbacksToId(mobileDetail),
            emptyList<String>(),
            titleFallbacksToId(mobileDetail)
        )
    }

    @Test
    fun `the TV Detail screen never falls back to the id`() {
        assertTrue(
            "the initial meta's name is gated",
            tvDetail.contains("name = upNextDisplayTitleOrNull(initialTarget?.displayName)")
        )
        assertTrue(
            "the auto-play splash's title is gated",
            tvDetail.contains("title = upNextDisplayTitleOrNull(initialTarget.displayName)")
        )
        assertTrue(
            "and the media menu's title chain is gated",
            tvDetail.contains("title = upNextDisplayTitleOrNull(tmdbDetail?.title)")
        )
        assertEquals(
            "no title or name fallback may be the raw id: " + titleFallbacksToId(tvDetail),
            emptyList<String>(),
            titleFallbacksToId(tvDetail)
        )
    }

    /**
     * Lines that give a `title`/`name` a value and still end on the id. The two
     * stream-id expressions that legitimately use `ifBlank { id }` are keys, not
     * titles, and are not matched because they do not assign a title/name.
     */
    private fun titleFallbacksToId(text: String): List<String> =
        text.lineSequence()
            .filter { line ->
                val trimmed = line.trim()
                (trimmed.startsWith("title =") || trimmed.startsWith("name =")) &&
                    (trimmed.contains("?: id") || trimmed.contains("ifBlank { id }"))
            }
            .map { it.trim() }
            .toList()

    // ── T2: the CW show line is gated like the card title ──────────────────

    @Test
    fun `the continue watching show line rejects an id-like name`() {
        assertTrue(
            "the big line on the card goes through the same gate as the title",
            homeViewModel.contains(
                "showTitle =\n" +
                    "                if (resolvesAsSeries) {\n" +
                    "                    upNextDisplayTitleOrNull(entry.name, rowHasArt)\n"
            )
        )
        assertTrue(
            "one art signal, so the title and the show line cannot disagree",
            homeViewModel.contains(
                "val rowHasArt =\n            !entry.poster.isNullOrBlank() || !backdropUrl.isNullOrBlank()"
            )
        )
        assertTrue(
            "the card title reads that same value",
            homeViewModel.contains("upNextDisplayTitleOrNull(\n                    entry.name,\n                    rowHasArt\n                )")
        )
    }

    // ── T3: 404 drops the card, a transient failure keeps it ───────────────

    @Test
    fun `a definitive miss with no art and no name drops the card`() {
        assertTrue(
            dropsZombieCard(
                lookup = TmdbDetailLookup.NotFound,
                hasArtwork = false,
                resolvedName = null
            )
        )
    }

    @Test
    fun `everything else keeps the card`() {
        assertFalse(
            "a 404 on a row that still has art keeps it",
            dropsZombieCard(TmdbDetailLookup.NotFound, hasArtwork = true, resolvedName = null)
        )
        assertFalse(
            "a 404 on a row whose name resolved keeps it",
            dropsZombieCard(TmdbDetailLookup.NotFound, hasArtwork = false, resolvedName = "The Bear")
        )
        assertFalse(
            "a transient failure is not a verdict: dropping here would empty CW in an outage",
            dropsZombieCard(TmdbDetailLookup.Transient, hasArtwork = false, resolvedName = null)
        )
    }

    @Test
    fun `the builder asks the lookup for the miss kind and drops through the rule`() {
        assertTrue(
            "the row's id is resolved with the miss kind kept, for EVERY id flavor",
            homeViewModel.contains("tmdbRepository.lookupDetailForRawId(")
        )
        assertTrue(
            "and the drop is the shared rule, not an inline predicate",
            homeViewModel.contains(
                "dropsZombieCard(\n                        lookup = detailLookup,"
            )
        )
        assertTrue(
            "only a 404 reaches it",
            homeViewModel.contains("var detailLookup: TmdbDetailLookup = TmdbDetailLookup.Transient")
        )
        assertTrue(
            "a verdict that names no title is the only thing that drops, and it takes " +
                "no art and no name with it",
            source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
                .contains("lookup.namesNoTitle && !hasArtwork && resolvedName == null")
        )
    }

    // ── T4: the title is printed once ──────────────────────────────────────

    @Test
    fun `the poster tile can suppress its own title placeholder`() {
        val tile = source("com/kennyb1201/kbstream/ui/components/PosterCard.kt")
        val touchTile = source("com/kennyb1201/kbstream/ui/components/touch/TouchPosterCard.kt")

        listOf(tile to "PosterCard", touchTile to "touch PosterCard").forEach { (text, label) ->
            assertTrue(
                "$label defaults the placeholder on, so every rail tile is unchanged",
                text.contains("showFallbackText: Boolean = true")
            )
            assertTrue(
                "$label gates the placeholder on it",
                text.contains("if (showFallbackText &&")
            )
        }
    }

    @Test
    fun `both continue watching cards pass the flag and keep their spoken label`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
        val tv = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")

        val mobileCard = mobile.windowAfter("private fun ContinueWatchingCard(", 6000)
        assertTrue("the mobile CW card prints its title in the overlay", mobileCard.contains("showFallbackText = false"))
        assertTrue("and keeps the accessibility label", mobileCard.contains("contentDescription = title"))

        val tvCard = tv.windowAfter("private fun CompactUpNextCard(", 6000)
        assertTrue("the TV CW card prints its title in the overlay", tvCard.contains("showFallbackText = false"))
        assertTrue("and keeps the accessibility label", tvCard.contains("contentDescription = item.title"))
    }

    // ── T6: one definition of the no-name literal ──────────────────────────

    @Test
    fun `the no-name literal is defined once and every fallback references it`() {
        val definition = "\"Unknown Show\""
        val hits = textFiles(projectRoot)
            .filter { it.readText().contains(definition) }
            .map { it.relativeTo(projectRoot).invariantSeparatorsPath }

        assertEquals(
            "the constant is the only place the words are written: $hits",
            listOf("app/src/main/java/com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"),
            hits
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun textFiles(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != ".gradle" && it.name != "build" }
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .toList()

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.windowAfter(marker: String, chars: Int): String {
        val start = indexOf(marker)
        assertTrue("$marker is missing", start >= 0)
        return substring(start, minOf(length, start + chars))
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
}
