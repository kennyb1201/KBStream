package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four TMDB browse screens badge their posters without waiting on the
 * TMDB→IMDB resolution pass, and preload the id flavor that makes that possible.
 *
 * Field report 2026-10-14 (mostly series): genre / keyword, studio / network /
 * service, decade and collection pages showed many watched titles with no eye
 * and no checkmark. Each of those screens badged off a resolved IMDB id, and
 * that pass is capped at 300 ids per load while a six-rail page carries up to
 * ~840 items — so everything past the cap had no key, and because those pages
 * run MOVIES×3 before SERIES×3 the cap starved the series rails systematically.
 *
 * What has to hold for the fix, and is pinned here:
 *
 *  1. each view model asks the watched state under BOTH id flavors through the
 *     one shared rule (`WatchedStatusKeys.watchedKeysOf`) — the behavior itself
 *     is tested in `data/watched/WatchedStatusKeysTest`, this only pins that all
 *     four call it (twice: fully watched and partially watched);
 *  2. each preloads the raw `tmdb:<n>` pairs UNCAPPED — that is the whole
 *     mechanism, since it is local DB plus the already-cached snapshot and
 *     needs no network;
 *  3. the `resolved…isEmpty()` short-circuit is gone, or a load whose every
 *     resolution came back null still badges nothing;
 *  4. the tile itself checks the `tmdb:<n>` key when the IMDB one misses, and
 *     the checkmark still wins over the eye;
 *  5. the resolver's own `.take(300)` is UNTOUCHED — it is the guard against
 *     flooding TMDB's `/external_ids`, and this fix exists to make the badges
 *     independent of it, not to raise it.
 *
 * The search screen's key normalization rides along: its type was never
 * lowercased, so a result typed `"TV"` looked up and compared under a key shape
 * nothing else in the app produces.
 */
class BrowseWatchedKeyWiringContractTest {

    @Test
    fun `each browse view model badges through the shared rule, twice`() {
        VIEW_MODELS.forEach { path ->
            val vm = code(path)
            assertEquals(
                "$path: both the watched and the partially-watched set have to go " +
                    "through the shared rule",
                2,
                Regex("WatchedStatusKeys\\.watchedKeysOf\\(").findAll(vm).count()
            )
            assertTrue(
                "$path: and the tile's key has to come from the same place, or the set " +
                    "and the screen can describe one title differently",
                vm.contains(
                    "fun watchedKey(id: String, type: String): String = WatchedStatusKeys.key(id, type)"
                )
            )
        }
    }

    @Test
    fun `each browse view model preloads the tmdb flavor, uncapped`() {
        VIEW_MODELS.forEach { path ->
            val vm = code(path)
            val preload = vm.substringAfter("val tmdbPairs =").substringBefore("preload(tmdbPairs)")
            assertTrue(
                "$path: the raw pairs have to be preloaded, or the tmdb keys below match " +
                    "nothing: $preload",
                vm.contains("val tmdbPairs =") && vm.contains("watchedStatusRepository.preload(tmdbPairs)")
            )
            assertFalse(
                "$path: and UNCAPPED - this is local DB plus the cached snapshot, not a " +
                    "network pass, so a cap here reinstates the bare posters past it: $preload",
                preload.contains("take(")
            )
            assertTrue(
                "$path: the pairs must be the tmdb flavor specifically",
                preload.contains("\"tmdb:")
            )
        }
    }

    @Test
    fun `the resolver's own cap is untouched`() {
        VIEW_MODELS.forEach { path ->
            assertTrue(
                "$path: `.take(300)` is the guard against flooding TMDB's /external_ids and " +
                    "stays exactly as it was - the badges were fixed by not depending on it",
                code(path).contains(".take(300)")
            )
        }
    }

    @Test
    fun `no browse view model still short-circuits on an empty resolution map`() {
        assertEquals(
            "a load whose every resolution came back null has to still badge its rails",
            0,
            Regex("resolvedMap\\.isEmpty\\(\\)|resolved\\.isEmpty\\(\\) \\|\\|")
                .findAll(VIEW_MODELS.joinToString("\n") { code(it) })
                .count()
        )
    }

    @Test
    fun `each browse tile falls back to the tmdb key when the imdb one misses`() {
        SCREENS.forEach { (path, keyId) ->
            val screen = code(path)
            assertTrue(
                "$path: the tile needs the tmdb id flavor: $keyId",
                screen.contains(keyId)
            )
            assertTrue(
                "$path: and has to test it for the checkmark",
                screen.contains("in watchedKeys")
            )
            assertTrue(
                "$path: and for the eye",
                screen.contains("in partialWatchedKeys")
            )
            assertTrue(
                "$path: the checkmark still wins over the eye",
                screen.contains("!watched && (")
            )
        }
    }

    @Test
    fun `the search screen compares under a normalized type`() {
        val vm = code(SEARCH_VM)
        assertTrue(
            "SearchViewModel.watchedKey must lowercase the type like every other " +
                "view model's does, or its keys never match the sets",
            vm.contains("fun watchedKey(") && vm.contains("\"\${type.lowercase()}::\$id\"")
        )
        assertTrue(
            "and the helper the tiles normalize with has to be reachable from them",
            vm.contains("internal fun normalizedType(")
        )
        val screen = code(SEARCH_SCREEN)
        assertFalse(
            "the raw result type must no longer reach either the id lookup or the key build",
            screen.contains("viewModel.lookupKey(tmdbId, result.type)") ||
                screen.contains("viewModel.watchedKey(keyId, result.type)")
        )
        assertEquals(
            "both tiles have to normalize it first",
            2,
            Regex("viewModel\\.normalizedType\\(result\\.type\\) \\?: return false")
                .findAll(screen)
                .count()
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
     * The same source with every comment line dropped, so a rule can be stated
     * about the CODE. It matters here on both sides of the ledger: the tree's
     * own comments name the guard this file bans ("No `resolvedMap.isEmpty()`
     * guard") and the mechanism this file requires, and asserting against that
     * prose would grade the documentation instead of the code.
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

        val VIEW_MODELS = listOf(
            "${UI}tag/TagViewModel.kt",
            "${UI}studio/StudioViewModel.kt",
            "${UI}decade/DecadeViewModel.kt",
            "${UI}collection/CollectionViewModel.kt"
        )

        /** Each screen, and the tmdb key literal that screen's tile builds. */
        val SCREENS = listOf(
            "${UI}tag/TagScreen.kt" to "val tmdbKeyId = \"tmdb:\$tmdbId\"",
            "${UI}studio/StudioScreen.kt" to "val tmdbKeyId = \"tmdb:\$tmdbId\"",
            "${UI}decade/DecadeScreen.kt" to "val tmdbKeyId = \"tmdb:\$tmdbId\"",
            "${UI}collection/CollectionScreen.kt" to "val tmdbKeyId = \"tmdb:\${part.id}\""
        )

        const val SEARCH_VM = "${UI}search/SearchViewModel.kt"
        const val SEARCH_SCREEN = "${UI}search/SearchScreenPart2.kt"
    }
}
