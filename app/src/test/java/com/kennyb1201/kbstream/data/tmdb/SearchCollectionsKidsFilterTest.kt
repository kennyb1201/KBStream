package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode on the search screen's Collections rail.
 *
 * The Movies and TV rails were certification-checked, but the Collections rail
 * published TMDB's collection search verbatim (`_collectionResults.value =
 * collectionResults...`), so a kids profile was offered adult franchises —
 * horror sagas, R-rated series — that its movies/TV rails would have hidden.
 * Browse chips were already filtered; only this rail leaked.
 *
 * A collection carries no rating of its own, so the ceiling is applied to its
 * MEMBERS: the franchise is shown when at least one film is allowed, and the
 * franchise page filters its own list (see [TmdbRepository.getCollection]).
 *
 * The identity a part is vetted under (`tmdb:<id>` / "movie") is the part a
 * compile cannot check and a device cannot easily show — a wrong id would look
 * up a different title and let the wrong franchise through — so it is tested
 * behaviourally. The wiring that could silently disappear is pinned as text.
 */
class SearchCollectionsKidsFilterTest {

    // ── behavioural: the vetting identity ─────────────────────────────────

    @Test
    fun `a part is vetted under the identity the other search paths use`() {
        val part = TmdbCollectionPart(id = 550, title = "Fight Club")

        assertEquals(
            MetaPreview(id = "tmdb:550", type = "movie", name = "Fight Club"),
            part.toMetaPreview()
        )
    }

    @Test
    fun `a part with no title falls back to name, and a nameless one to blank`() {
        // TMDB sends `title` for movie parts and `name` on some responses; a
        // record with neither still has to map to a non-null name, because the
        // ceiling lookup is keyed on the id, not the label.
        assertEquals("Se7en", TmdbCollectionPart(id = 1, name = "Se7en").toMetaPreview().name)
        assertEquals("", TmdbCollectionPart(id = 2).toMetaPreview().name)
        assertEquals(
            "the movie type is fixed, not read off the wire: a part is always a film",
            "movie",
            TmdbCollectionPart(id = 3, title = "  Alien  ").toMetaPreview().let {
                assertEquals("the label is trimmed for the log, as elsewhere", "Alien", it.name)
                it.type
            }
        )
    }

    @Test
    fun `the identity is what makes the ceiling check fetch that title`() {
        // tmdb:<numeric> is the spelling kidsFilterMetas fetches directly
        // (instead of resolving through TMDB /find); "movie" is the media-type
        // spelling the whole ceiling family agrees on.
        val meta = TmdbCollectionPart(id = 12345, title = "Some Sequel").toMetaPreview()
        assertTrue("id must be the tmdb: form", meta.id.startsWith("tmdb:"))
        assertEquals("12345", meta.id.removePrefix("tmdb:"))
        assertEquals("movie", meta.type)
    }

    // ── contract: one ceiling policy, shared caches, cap after filter ─────

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
    fun `a profile with no ceiling pays one null-check and no lookups`() {
        val repo = source(TMDB)
        assertTrue(
            "kidsFilterCollections must early-return on a null ceiling, or every " +
                "adult profile's search would fetch each collection's movies",
            repo.contains("if (collections.isEmpty() || kidsMaxAge() == null) return collections")
        )
        val search = source(SEARCH)
        assertTrue(
            "and the caller must keep the unfiltered path for a non-kids profile",
            search.contains("if (tmdbRepository.kidsMaxAge() == null) {")
        )
    }

    @Test
    fun `the ceiling decision goes through the one shared policy`() {
        val repo = source(TMDB)
        // The collection branch must reuse kidsFilterMetas rather than
        // re-implement the ordinal comparison — the scale is exactly where the
        // old age-like values let adult content through a check that looked
        // correct.
        assertTrue(
            "the collection filter must vet its parts through kidsFilterMetas",
            repo.contains("kidsFilterMetas(parts.map { it.toMetaPreview() })")
        )
        assertTrue(
            "an unreadable collection must fail OPEN on PG-13 and closed on strict " +
                "ceilings, the same known-unknown rule kidsFilterMetas applies",
            repo.contains("ceiling == KidsMode.CEIL_PG13")
        )
    }

    @Test
    fun `the collection list comes from the shared cache, not an uncached fetch`() {
        val repo = source(TMDB)
        assertTrue(
            "the per-search path must read the collection through the shared detail cache",
            repo.contains("runCatchingCancellable { cachedCollectionRaw(collection.id) }.getOrNull()")
        )
        assertTrue(
            "and that helper must be built on collectionCache (one cache with the " +
                "collection detail page, so repeat searches are cheap)",
            repo.contains("collectionCache[collectionId]")
        )
        // The vetting loop is the only place that runs per search; it must not
        // call the API itself.
        val start = repo.indexOf("suspend fun kidsFilterCollections(")
        assertTrue("kidsFilterCollections missing", start >= 0)
        val end = repo.indexOf("\n    }", repo.indexOf("awaitAll().filterNotNull()", start))
        val body = repo.substring(start, if (end > start) end else repo.length)
        assertFalse(
            "the vetting loop must not hit TMDB's collection endpoint directly",
            body.contains("api.getCollection(")
        )
    }

    @Test
    fun `the search rail filters collections instead of publishing the raw response`() {
        val search = source(SEARCH)
        assertTrue(
            "the Collections rail must run the ceiling filter",
            search.contains("tmdbRepository.kidsFilterCollections(collectionResults)")
        )
        assertFalse(
            "the raw publish path is the leak; it must be gone",
            search.contains("_collectionResults.value = collectionResults.distinctBy { it.id }")
        )
        // Cap after filter, so a strict profile still fills the rail.
        val filter = search.indexOf("kidsFilterCollections(collectionResults)")
        val distinct = search.indexOf(".distinctBy { it.id }", filter)
        val take = search.indexOf(".take(MAX_COLLECTION_RESULTS)", filter)
        assertTrue("the filtered list must still be deduped", distinct in (filter + 1) until take)
        assertTrue("and only then capped", take > distinct)
    }

    @Test
    fun `actors, studios and the title rail are left as they were`() {
        val search = source(SEARCH)
        assertTrue(
            "the title path must keep its own ceiling filter",
            search.contains("tmdbRepository.kidsFilterMetas(mergedTitles.map { it.meta })")
        )
        assertTrue(
            "actors are names, not content: no certification signal to filter on",
            search.contains("_actorResults.value = personResults.distinctBy { it.id }.take(MAX_PERSON_RESULTS)")
        )
        assertTrue(
            "same for studios",
            search.contains("_studioResults.value = studioResults.distinctBy { it.id }.take(MAX_STUDIO_RESULTS)")
        )
    }

    private companion object {
        const val TMDB = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
