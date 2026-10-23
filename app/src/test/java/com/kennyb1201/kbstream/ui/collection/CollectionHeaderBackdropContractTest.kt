package com.kennyb1201.kbstream.ui.collection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection HEADER's backdrop: read out of the source, because the three
 * halves of it are a repository call, a ViewModel flow and two Composable
 * headers that need a device.
 *
 * The rule the header needs is the card ranking with the exclusion REMOVED. A
 * collection CARD must not repeat the key art of the surface it sits on, so it
 * is handed the primary path and told to skip it - but the header IS that
 * surface, so there the primary has to compete on the ranking like everything
 * else. That is what lets a text-free still win over a primary with the
 * collection's own title burned into it (Moana's says "Moana Collection" beside
 * the header's title), while a collection whose key art is already text-free
 * keeps rendering exactly what it rendered before.
 *
 * The pieces pinned here are the ones that can silently regress: the ranking
 * being handed a primary again (the header goes back to the duplicated title),
 * the two picks sharing one cache entry (the card and the header hand each other
 * the wrong answer), and either header losing the ranked pick as its fallback
 * (the fix reaches one form factor only).
 */
class CollectionHeaderBackdropContractTest {

    private fun source(path: String): String = File(findMainSourceRoot(), path).readText()

    private fun normalized(path: String): String =
        source(path).replace(Regex("\\s+"), " ").trim()

    /** A window of [length] characters after [anchor], for asserting inside one body. */
    private fun after(path: String, anchor: String, length: Int = 2000): String {
        val raw = source(path)
        assertTrue("$anchor not found in $path", raw.contains(anchor))
        return raw.substringAfter(anchor).take(length)
    }

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the header pick ranks the primary instead of excluding it`() {
        val body = after(TMDB_REPOSITORY, "suspend fun fetchCollectionHeaderBackdropCached(")

        assertTrue(
            "the header ranks with the shared rule, not a second copy of the order",
            body.contains("bestCardBackdropPath(")
        )
        assertTrue(
            "and it hands the ranking NO primary, because the header IS the " +
                "primary surface: its own key art is a legitimate answer when it " +
                "wins",
            body.contains("primaryPath = null")
        )
        assertFalse(
            "the card pick's exclusion must not creep back in - it would make the " +
                "header unable to keep its own text-free primary",
            body.contains("?.takeIf { it != primary }")
        )
        assertTrue(
            "cache first, network second, filing last",
            body.indexOf("collectionHeaderArtCache[collectionId]") in 0..<
                body.indexOf("api.getCollectionImages(") &&
                body.indexOf("api.getCollectionImages(") <
                body.indexOf("collectionHeaderArtCache[collectionId] = now to ranked")
        )
        assertTrue(
            "a failed request or an empty image list is a miss, not a filed answer",
            body.contains("?: return null")
        )
        assertTrue(
            "the answer comes back at the same base the card pick uses, so the " +
                "screens apply the same full-res treatment to both",
            body.contains("return BACKDROP_BASE + ranked")
        )
    }

    @Test
    fun `the header pick is cached apart from the card pick`() {
        // The two answer DIFFERENTLY for the same collection id, so one entry
        // cannot serve both: the card would draw the header's primary and the
        // header the card's excluded alternate.
        val repository = source(TMDB_REPOSITORY)

        assertTrue(
            "the header has its own cache",
            repository.contains(
                "private val collectionHeaderArtCache =" +
                    " ConcurrentHashMap<Int, Pair<Long, String>>()"
            )
        )
        assertTrue(
            "with its own bound",
            repository.contains("private val collectionHeaderArtCacheMaxEntries = 64")
        )
        assertTrue(
            "and the card pick still files into its own",
            repository.contains("collectionArtCache[collectionId] = now to ranked")
        )
    }

    @Test
    fun `the ViewModel publishes the ranked header backdrop and drops it per collection`() {
        val viewModel = normalized(VIEW_MODEL)

        assertTrue(
            "the header art is exposed next to the collection it belongs to",
            viewModel.contains(
                "val headerBackdropUrl: StateFlow<String?> = _headerBackdropUrl.asStateFlow()"
            )
        )
        assertTrue(
            "a new collection starts from null, so the previous collection's art " +
                "cannot stand in for this one's fallback",
            viewModel.contains("_headerBackdropUrl.value = null")
        )
        assertTrue(
            "and the ranked pick is resolved through the repository's header call",
            viewModel.contains("tmdbRepository.fetchCollectionHeaderBackdropCached(collectionId)")
        )
        assertTrue(
            "a slower older load must not paint its art under a newer collection",
            viewModel.contains(
                "if (generation != loadGeneration) return _headerBackdropUrl.value = url"
            )
        )
    }

    @Test
    fun `both headers draw the ranked pick behind the unchanged override chain`() {
        listOf(TV_SCREEN to "detail?.backdropPath", MOBILE_SCREEN to "collection?.backdropPath")
            .forEach { (screen, primary) ->
                val source = normalized(screen)

                assertTrue(
                    "$screen must still put the viewer's image choice first",
                    source.contains("backdropUrl = imageOverrideBackdropUrl(")
                )
                assertTrue(
                    "$screen's fallback is the ranked text-free pick",
                    source.contains("fallbackUrl = TmdbRepository.fullResBackdropUrl( " +
                        "headerBackdropUrl ?:")
                )
                assertTrue(
                    "$screen falls back to the collection's own primary while the " +
                        "ranking is unresolved, so the header is never blank",
                    source.contains("$primary?.takeIf { it.isNotBlank() }")
                )
            }
    }

    private companion object {
        const val TMDB_REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/collection/CollectionViewModel.kt"
        const val TV_SCREEN =
            "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt"
        const val MOBILE_SCREEN =
            "com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt"
    }
}
