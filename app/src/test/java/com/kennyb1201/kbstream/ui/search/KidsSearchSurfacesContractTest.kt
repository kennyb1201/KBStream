package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode on the two search surfaces that run OUTSIDE the app's screens.
 *
 * The launcher's global-search rows, a voice / system `play X`, and a tapped
 * suggestion's own `kbstream://` deep link all resolve a title against TMDB with
 * no screen of this app's in between, so neither the Detail-level kids gate nor
 * the in-app search's own filter can catch a hit that came from them. All three
 * now apply the same ceiling — the two list-shaped surfaces through
 * [kidsFilteredTmdbSearch], the single-id deep link through
 * [com.kennyb1201.kbstream.data.tmdb.TmdbRepository.kidsAllowed].
 *
 * The identity a result is vetted under is the part a compile cannot check and a
 * device cannot easily show: `tmdb:<id>` is what the in-app search uses for the
 * same results, and it is what makes the ceiling lookup fetch THAT title. That
 * mapping is tested behaviourally here; the two call sites are pinned as text,
 * because a missing one is silent - the rows simply appear.
 */
class KidsSearchSurfacesContractTest {

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

    @Test
    fun `a result is vetted under the identity the in-app search uses`() {
        assertEquals(
            listOf(
                MetaPreview(id = "tmdb:550", type = "movie", name = "Fight Club"),
                // "series", not "tv": the ceiling lookup and the app's own naming
                // agree on that spelling everywhere else.
                MetaPreview(id = "tmdb:1399", type = "series", name = "Game of Thrones")
            ),
            tmdbSearchMetas(
                movies = listOf(TmdbSearchTitleResult(id = 550, title = "Fight Club")),
                shows = listOf(TmdbSearchTitleResult(id = 1399, name = "Game of Thrones"))
            )
        )
    }

    @Test
    fun `every result of both lists is vetted, in response order`() {
        val metas = tmdbSearchMetas(
            movies = listOf(
                TmdbSearchTitleResult(id = 1, title = "One"),
                TmdbSearchTitleResult(id = 2, title = "Two")
            ),
            shows = listOf(TmdbSearchTitleResult(id = 3, name = "Three"))
        )
        assertEquals(listOf("tmdb:1", "tmdb:2", "tmdb:3"), metas.map { it.id })
        assertEquals(listOf("movie", "movie", "series"), metas.map { it.type })
    }

    @Test
    fun `the launcher's global-search rows apply the ceiling`() {
        val provider = source(PROVIDER)
        assertTrue(
            "the provider's rows must be vetted before they are built",
            provider.contains("kidsFilteredTmdbSearch(tmdb, movies, shows)")
        )
        assertFalse(
            "and must not be built from the raw search responses",
            provider.contains("buildSearchSuggestions(movies, shows)")
        )
    }

    @Test
    fun `a spoken play applies the ceiling before it resolves a match`() {
        val voice = source(VOICE)
        assertTrue(
            "the spoken match must be resolved against vetted results",
            voice.contains("kidsFilteredTmdbSearch(tmdb, movies, shows)")
        )
        val filter = voice.indexOf("kidsFilteredTmdbSearch(tmdb, movies, shows)")
        val match = voice.indexOf("pickPlayFromSearchMatch(")
        assertTrue(
            "and the vetting must come FIRST - a match already picked is a deep link",
            match in (filter + 1) until voice.length
        )
    }

    @Test
    fun `a tapped suggestion deep link applies the ceiling too`() {
        // The suggestion ROWS are already vetted before the launcher shows them,
        // but the tapped row comes back as a kbstream:// VIEW resolved here - a
        // stale or externally-supplied link must not open a blocked title's
        // Detail screen.
        val voice = source(VOICE)
        val check = voice.indexOf("tmdb.kidsAllowed(parsed.tmdbId")
        val resolve = voice.indexOf("tmdb.resolveImdbId(parsed.tmdbId")
        assertTrue("the deep link must apply the ceiling", check >= 0)
        assertTrue(
            "and apply it before it resolves the id (and opens Detail)",
            check in 0 until resolve
        )
    }

    private companion object {
        const val PROVIDER = "com/kennyb1201/kbstream/ui/search/SearchSuggestionsProvider.kt"
        const val VOICE = "com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt"
    }
}
