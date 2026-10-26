package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part 2 of the watched-badge field report: the same bug class on every other
 * poster surface.
 *
 * Part 1 fixed Tag / Studio / Decade / Collection by keying badges off the
 * item's own `tmdb:<n>` id instead of waiting on the TMDB→IMDB resolution
 * pass. Kenny's 2026-10-17 report — actor screen, some shows badged and some
 * bare while the episodes were correctly marked watched — showed the same
 * shape elsewhere: the Actor screen, the Detail page's "more like this" and
 * collection rails, KBFolder rails, Library rows and Search tiles all badged
 * only under the resolved IMDB id, so a title past the resolution cap or one
 * TMDB has no `imdb_id` for stayed bare forever.
 *
 * What has to hold, and is pinned here:
 *
 *  1. every one of those view models asks the watched state under BOTH id
 *     flavors through the one shared rule (`WatchedStatusKeys.watchedKeysOf`),
 *     and preloads its raw `tmdb:<n>` pairs uncapped (local DB plus the cached
 *     snapshot — no network);
 *  2. every tile that draws the badge checks the `tmdb:<n>` key when the IMDB
 *     one misses, and the checkmark still wins over the eye;
 *  3. the resolution caps themselves are untouched: they are the guard against
 *     flooding TMDB's `/external_ids`, and the point of the fix is that badges
 *     no longer depend on them.
 */
class PartTwoWatchedKeyWiringContractTest {

    @Test
    fun `actor view model badges through the shared rule`() {
        val vm = code(ACTOR_VM)
        assertEquals(
            "both the watched and the partially-watched set have to go through the " +
                "shared rule",
            2,
            Regex("WatchedStatusKeys\\.watchedKeysOf\\(").findAll(vm).count()
        )
        assertFalse(
            "an empty resolution map must no longer mean a credit list with no badges",
            vm.contains("resolvedMap.isEmpty()")
        )
        assertTrue(
            "the preload has to cover the whole credit list, not the resolution batch",
            vm.contains("WatchedStatusKeys.tmdbKeyId(credit.id)")
        )
        assertTrue(
            "the resolution batch's own cap stays: it is the /external_ids flood guard",
            vm.contains(".take(60)")
        )
    }

    @Test
    fun `actor tile falls back to the tmdb key`() {
        val screen = code(ACTOR_SCREEN)
        assertTrue(
            "the credit tile needs the tmdb id flavor",
            screen.contains("WatchedStatusKeys.tmdbKeyId(credit.id)")
        )
        assertTrue("its checkmark compares against the watched set", screen.contains("in watchedKeys"))
        assertTrue(
            "and its eye against the partial set",
            screen.contains("in partialWatchedKeys")
        )
        assertTrue(
            "the checkmark still wins over the eye",
            screen.contains("!creditWatched && (")
        )
    }

    @Test
    fun `detail poster rails badge every poster, resolved or not`() {
        val vm = code(DETAIL_VM)
        assertEquals(
            "the collection parts and the recommendation rail both go through the rule, " +
                "once for each watched set",
            2,
            Regex("WatchedStatusKeys\\.watchedKeysOf\\(").findAll(vm).count()
        )
        assertTrue(
            "the tmdb flavor of every poster is preloaded uncapped",
            // The poster refresh builds its preload list from PosterWatchItem,
            // so the pair reads `item.tmdbId`/`item.mediaType`; the rule the
            // assertion exists for - the raw tmdb flavor beside the resolved
            // IMDb one - is the same one.
            vm.contains("WatchedStatusKeys.tmdbKeyId(item.tmdbId) to item.mediaType")
        )
        assertFalse(
            "a refresh whose every resolution came back null still has to badge the posters",
            Regex("resolvedItems\\.isEmpty\\(\\)[^\n]*\n[^\n]*_watchedKeys\\.value = emptySet")
                .containsMatchIn(vm)
        )

        val screen = code(DETAIL_SCREEN)
        assertTrue(
            "the collection rail's poster checks the tmdb key",
            Regex("WatchedStatusKeys\\s*\\.tmdbKeyId\\(part\\.id\\)").containsMatchIn(screen)
        )
        assertTrue(
            "and so does the recommendation rail",
            Regex("WatchedStatusKeys\\s*\\.tmdbKeyId\\(rec\\.id\\)").containsMatchIn(screen)
        )
        assertTrue(
            "the badge sites compare against both sets",
            screen.contains("in watchedKeys") && screen.contains("in partialWatchedKeys")
        )
    }

    @Test
    fun `kbfolder badges add-on ids directly and tmdb ids under both flavors`() {
        val vm = code(KB_VM)
        assertEquals(
            "both watched sets go through the shared rule",
            2,
            Regex("WatchedStatusKeys\\.watchedKeysOf\\(").findAll(vm).count()
        )
        assertTrue(
            "the rail items' tmdb flavor is preloaded",
            vm.contains("WatchedStatusKeys.tmdbKeyId(it) to normalized")
        )
        assertTrue(
            "the resolution pass keeps its own cap",
            vm.contains(".take(300)")
        )

        val layouts = code(KB_LAYOUTS)
        assertTrue(
            "the card's watched lookup falls back to the tmdb key",
            layouts.contains("WatchedStatusKeys.tmdbKeyId(tmdbId)")
        )
        assertEquals(
            "both badge helpers need the fallback (checkmark and eye)",
            2,
            Regex("WatchedStatusKeys\\.tmdbKeyId\\(tmdbId\\)").findAll(layouts).count()
        )
    }

    @Test
    fun `library rows badge under either id flavor`() {
        val vm = code(LIBRARY_VM)
        assertTrue(
            "the per-row enrichment asks the tmdb flavor when the imdb check missed",
            vm.contains("WatchedStatusKeys.tmdbKeyId(it)")
        )
        assertTrue(
            "and the UNWATCHED filter judges a row by every key it can have",
            vm.contains("item.watchedKeyCandidates().none { it in watchedKeys }")
        )
        assertTrue(
            "the screen hands the card the same rule",
            code(LIBRARY_SCREEN).contains("item.watchedKeyCandidates().any { it in watchedKeys }")
        )
    }

    @Test
    fun `search preloads and checks the tmdb flavor`() {
        val vm = code(SEARCH_VM)
        assertTrue(
            "every visible TMDB id's raw flavor is preloaded, not just the resolved batch",
            vm.contains("WatchedStatusKeys.tmdbKeyId(tmdbId) to mediaType")
        )

        val screen = code(SEARCH_SCREEN)
        assertEquals(
            "the checkmark tile and the eye tile both fall back to the tmdb key",
            2,
            Regex("watchedKeyOf\\(tmdbId, normalizedType\\) in (watchedKeys|partialWatchedKeys)")
                .findAll(screen)
                .count()
        )
        assertFalse(
            "an unresolved TMDB result must not be dropped before the fallback runs",
            Regex("resolvedIds\\[viewModel\\.lookupKey\\(tmdbId, normalizedType\\)\\]\\s*\\?:\\s*return false")
                .containsMatchIn(screen)
        )
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /**
     * The same source with every comment line dropped, so the rules above are
     * stated about the CODE: these files' own comments name the mechanism the
     * test looks for ("see WatchedStatusKeys", the `tmdb:<n>` flavor), and
     * asserting against that prose would grade the documentation.
     */
    private fun code(path: String): String =
        source(path)
            .lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")

    private companion object {
        const val UI = "com/kennyb1201/kbstream/ui/"

        const val ACTOR_VM = "${UI}actor/ActorViewModel.kt"
        const val ACTOR_SCREEN = "${UI}actor/ActorScreen.kt"
        const val DETAIL_VM = "${UI}detail/DetailViewModel.kt"
        const val DETAIL_SCREEN = "${UI}detail/DetailScreen.kt"
        const val KB_VM = "${UI}kb/KBFolderViewModel.kt"
        const val KB_LAYOUTS = "${UI}kb/KBFolderLayouts.kt"
        const val LIBRARY_VM = "${UI}library/LibraryViewModel.kt"
        const val LIBRARY_SCREEN = "${UI}library/LibraryScreen.kt"
        const val SEARCH_VM = "${UI}search/SearchViewModel.kt"
        const val SEARCH_SCREEN = "${UI}search/SearchScreenPart2.kt"
    }
}
