package com.kennyb1201.kbstream.data.catalogs

import com.kennyb1201.kbstream.data.kb.KBFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalog Builder's rules, as rules.
 *
 * Everything asserted here fails SILENTLY on a TV if it is wrong: a sort key
 * spelled for the wrong media type is an empty rail rather than an error, a
 * cleared genre travelling as an empty string is a catalog that returns nothing,
 * and an id list left with a dangling separator is a filter TMDB cannot parse.
 */
class CustomCatalogTest {

    // ------------------------------------------------------------ sort keys --

    @Test
    fun `date sorts are spelled per media type`() {
        // TMDB 400s on the movie spelling against /discover/tv, and the two
        // spellings are the reason the builder stores an agnostic id.
        assertEquals(
            "primary_release_date.desc",
            catalogSortKey(CatalogSort.NEWEST.id, CATALOG_MEDIA_MOVIE)
        )
        assertEquals(
            "first_air_date.desc",
            catalogSortKey(CatalogSort.NEWEST.id, CATALOG_MEDIA_TV)
        )
        assertEquals(
            "primary_release_date.asc",
            catalogSortKey(CatalogSort.OLDEST.id, CATALOG_MEDIA_MOVIE)
        )
        assertEquals(
            "first_air_date.asc",
            catalogSortKey(CatalogSort.OLDEST.id, CATALOG_MEDIA_TV)
        )
    }

    @Test
    fun `shared sorts are the same for both media types`() {
        listOf(CatalogSort.POPULARITY, CatalogSort.TOP_RATED, CatalogSort.MOST_VOTED)
            .forEach { sort ->
                assertEquals(
                    "${sort.id} must not depend on the media type",
                    catalogSortKey(sort.id, CATALOG_MEDIA_MOVIE),
                    catalogSortKey(sort.id, CATALOG_MEDIA_TV)
                )
            }
    }

    @Test
    fun `an unknown sort falls back to popularity instead of failing`() {
        assertEquals("popularity.desc", catalogSortKey("from-a-newer-build", CATALOG_MEDIA_MOVIE))
        assertEquals("popularity.desc", catalogSortKey(null, CATALOG_MEDIA_TV))
        assertEquals("popularity.desc", catalogSortKey(CatalogSort.POPULARITY.id, null))
    }

    @Test
    fun `every offered sort id maps to a real tmdb key`() {
        CatalogSort.entries.forEach { sort ->
            listOf(CATALOG_MEDIA_MOVIE, CATALOG_MEDIA_TV).forEach { mediaType ->
                val key = catalogSortKey(sort.id, mediaType)
                assertTrue(
                    "${sort.id}/$mediaType produced a placeholder key: $key",
                    key.endsWith(".desc") || key.endsWith(".asc")
                )
            }
        }
    }

    @Test
    fun `the catalog's own tmdb key follows its media type`() {
        val movie = CustomCatalog(id = "c1", name = "Movies", sort = CatalogSort.NEWEST.id)
        assertEquals("primary_release_date.desc", movie.tmdbSortKey())
        assertEquals(
            "first_air_date.desc",
            movie.copy(mediaType = CATALOG_MEDIA_TV).tmdbSortKey()
        )
    }

    @Test
    fun `rail type is singular per media type`() {
        assertEquals("movie", CustomCatalog(id = "c", name = "n").railType)
        assertEquals(
            "series",
            CustomCatalog(id = "c", name = "n", mediaType = CATALOG_MEDIA_TV).railType
        )
    }

    // ------------------------------------------------------- filter cleanup --

    @Test
    fun `blank filter values are dropped rather than sent as empty parameters`() {
        // `with_genres=` is a filter on nothing on TMDB's side: an empty page,
        // which from the couch reads as "this catalog is broken".
        val cleaned = normalizedFilters(
            KBFilters(
                withGenres = "  ",
                withoutGenres = "",
                withWatchProviders = "8",
                withOriginalLanguage = " ",
                withOriginCountry = "US",
                withCompanies = "",
                withNetworks = "",
                withKeywords = "",
                withoutKeywords = "",
                withoutCompanies = "",
                withoutWatchProviders = "",
                watchRegion = " ",
                releaseDateGte = "",
                releaseDateLte = " ",
                voteCountGte = 0,
                voteAverageGte = 0,
                voteAverageLte = 0,
                year = null
            )
        )

        assertNull(cleaned.withGenres)
        assertNull(cleaned.withoutGenres)
        assertNull(cleaned.withOriginalLanguage)
        assertNull(cleaned.watchRegion)
        assertNull(cleaned.releaseDateGte)
        assertNull(cleaned.releaseDateLte)
        assertNull("a zero floor is 'no floor'", cleaned.voteCountGte)
        assertNull(cleaned.voteAverageGte)
        assertNull(cleaned.voteAverageLte)
        assertEquals("real values survive", "8", cleaned.withWatchProviders)
        assertEquals("US", cleaned.withOriginCountry)
    }

    @Test
    fun `a year filter is kept verbatim in both spellings`() {
        assertEquals("1999", normalizedFilters(KBFilters(year = "1999")).year)
        assertEquals("1990-1999", normalizedFilters(KBFilters(year = "1990-1999")).year)
    }

    @Test
    fun `effective filters are the normalized ones`() {
        val catalog = CustomCatalog(
            id = "c",
            name = "n",
            filters = KBFilters(withGenres = " ", voteCountGte = 0, withNetworks = "49")
        )
        assertEquals(normalizedFilters(catalog.filters), catalog.effectiveFilters())
        assertEquals("49", catalog.effectiveFilters().withNetworks)
    }

    @Test
    fun `an unfiltered catalog knows it has no rules`() {
        assertTrue(CustomCatalog(id = "c", name = "n").isUnfiltered)
        assertFalse(
            CustomCatalog(id = "c", name = "n", filters = KBFilters(withGenres = "28"))
                .isUnfiltered
        )
    }

    // ------------------------------------------------------------ id lists --

    @Test
    fun `an id list splits, trims and de-duplicates`() {
        assertEquals(listOf(28, 12), splitIds("28,12"))
        assertEquals(listOf(28, 12), splitIds(" 28 , 12 ,"))
        assertEquals(listOf(28), splitIds("28,28"))
        assertEquals(emptyList<Int>(), splitIds(null))
        assertEquals("junk entries are dropped, not kept as ids", emptyList<Int>(), splitIds(",,abc"))
    }

    @Test
    fun `a code list splits on the pipe and drops blanks`() {
        assertEquals(listOf("en"), splitCodes("en"))
        assertEquals(listOf("en", "ja"), splitCodes("en|ja|"))
        assertEquals(listOf("US"), splitCodes("US|US"))
        assertEquals(emptyList<String>(), splitCodes(null))
    }

    @Test
    fun `toggling an id adds it and takes it back out`() {
        assertEquals("28", csvToggle(null, 28))
        assertEquals("28,12", csvToggle("28", 12))
        assertEquals("12", csvToggle("28,12", 28))
        assertNull("an empty list is null, not an empty string", csvToggle("28", 28))
    }

    @Test
    fun `toggling a code adds it and takes it back out`() {
        assertEquals("en", codeListToggle(null, "en"))
        assertEquals("en|ja", codeListToggle("en", "ja"))
        assertEquals("ja", codeListToggle("en|ja", "en"))
        assertNull(codeListToggle("en", "en"))
    }

    @Test
    fun `adding an id is idempotent where toggling would remove it`() {
        // The keyword picker ADDS: pressing the same suggestion twice must not
        // silently drop the rule that was just chosen.
        assertEquals("28", csvAdd(null, 28))
        assertEquals("28", csvAdd("28", 28))
        assertEquals("28,12", csvAdd("28", 12))
    }

    @Test
    fun `removing an id leaves null when nothing is left`() {
        assertEquals("12", csvRemove("28,12", 28))
        assertNull(csvRemove("28", 28))
        assertNull(csvRemove(null, 28))
    }

    @Test
    fun `membership is read the same way it is written`() {
        assertTrue(csvContains("28,12", 12))
        assertFalse(csvContains("28,12", 3))
        assertTrue(codeListContains("en|ja", "ja"))
        assertFalse(codeListContains(null, "ja"))
    }

    @Test
    fun `a decade filter spells the range the loader understands`() {
        assertEquals("1990-1999", yearFilterValue(1990, 1999))
        assertEquals("1999", yearFilterValue(1999, 1999))
        assertEquals("1990", yearFilterValue(1990, null))
        assertNull(yearFilterValue(null, 1999))
    }

    // -------------------------------------------------------- list editing --

    @Test
    fun `a fresh id never collides with a taken one`() {
        val first = newCatalogId(1000L, emptySet())
        assertEquals("the same seed gives the same id", first, newCatalogId(1000L, emptySet()))
        val second = newCatalogId(1000L, setOf(first))
        assertFalse("a taken id is never handed out twice", second == first)
        val third = newCatalogId(1000L, setOf(first, second))
        assertFalse(third == first)
        assertFalse(third == second)
    }

    @Test
    fun `upserting inserts at the end and replaces in place`() {
        val a = CustomCatalog(id = "a", name = "A")
        val b = CustomCatalog(id = "b", name = "B")
        val c = CustomCatalog(id = "c", name = "C")

        assertEquals(listOf(a, b, c), upsertCatalog(listOf(a, b), c))

        val renamed = b.copy(name = "B renamed")
        assertEquals(
            "a rename must not reorder the list",
            listOf(a, renamed, c),
            upsertCatalog(listOf(a, b, c), renamed)
        )
    }

    @Test
    fun `upserting an identical catalog reports no change`() {
        val a = CustomCatalog(id = "a", name = "A")
        val list = listOf(a)
        assertTrue(
            "an unchanged save must not dirty the synced blob",
            list === upsertCatalog(list, a)
        )
    }

    @Test
    fun `removing is by id and is a no-op when it is already gone`() {
        val a = CustomCatalog(id = "a", name = "A")
        val b = CustomCatalog(id = "b", name = "B")
        assertEquals(listOf(b), removeCatalog(listOf(a, b), "a"))
        assertEquals(listOf(a, b), removeCatalog(listOf(a, b), "zz"))
    }

    @Test
    fun `moving clamps at both ends and reports no-op moves`() {
        val a = CustomCatalog(id = "a", name = "A")
        val b = CustomCatalog(id = "b", name = "B")
        val c = CustomCatalog(id = "c", name = "C")
        val list = listOf(a, b, c)

        assertEquals(listOf(b, a, c), moveCatalog(list, "a", 1))
        assertEquals(listOf(a, c, b), moveCatalog(list, "c", -1))
        assertEquals("moving past the top is a no-op", list, moveCatalog(list, "a", -1))
        assertEquals("moving past the bottom is a no-op", list, moveCatalog(list, "c", 1))
        assertEquals("an unknown id is ignored", list, moveCatalog(list, "zz", 1))
    }

    @Test
    fun `only a named catalog is saveable`() {
        assertFalse(isSaveableCatalog(null))
        assertFalse(isSaveableCatalog(CustomCatalog(id = "c", name = "   ")))
        assertTrue(isSaveableCatalog(CustomCatalog(id = "c", name = "Cosy Sci-Fi")))
    }

    // ------------------------------------------------------ arrangement key --

    @Test
    fun `a built catalog keys into its own arrangement family`() {
        val key = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.customCatalogKey("cat7")
        assertEquals("custom:cat7", key)
        assertTrue(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.isCustomCatalogKey(key))
        assertFalse(
            "an add-on or collection key is not a built catalog",
            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
                .isCustomCatalogKey("addon:https://x/movie:top")
        )
        assertFalse(com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.isCustomCatalogKey(null))
    }

    @Test
    fun `hiding a built catalog is the shared rail-hide rule`() {
        val key = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.customCatalogKey("cat7")
        val shown = com.kennyb1201.kbstream.data.kb.KBHomeOrder(
            order = listOf(key, "addon:a"),
            pinned = listOf(key)
        )
        val hidden = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.toggleRailHidden(shown, key)
        assertTrue(key in hidden.hiddenSet)
        assertFalse("hidden leaves the arrangement", key in hidden.order)
        assertFalse("and the pinned list", key in hidden.pinned)

        val back = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.toggleRailHidden(hidden, key)
        assertFalse(key in back.hiddenSet)
        assertEquals("showing must not move anything", hidden.order, back.order)
    }

    @Test
    fun `the built-in toggle and the catalog toggle are one rule`() {
        val key = com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
        val start = com.kennyb1201.kbstream.data.kb.KBHomeOrder(order = listOf(key))
        assertEquals(
            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.toggleRailHidden(start, key),
            com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs.toggleBuiltinHidden(start, key)
        )
    }
}
