package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.library.TasteSignals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Titles rail's type filter and sorts ([TitleTypeFilter], [TitleSort]).
 *
 * The rail merges movies and series under one fixed order, so the viewer had no
 * way to ask for "just films" or "newest first". Both are pure functions over
 * the merged list - no re-fetch, no ViewModel - which is why the rules can be
 * pinned here rather than on a device.
 */
class TitlesViewTest {

    private fun hit(
        id: Int,
        name: String,
        year: Int? = null,
        type: String = "movie"
    ) = SearchTitleResult(
        id = "tmdb:$id",
        type = type,
        name = name,
        poster = null,
        year = year,
        meta = MetaPreview(id = "tmdb:$id", type = type, name = name)
    )

    /** The store's own key spelling, as the taste store writes it. */
    private fun signalled(tmdb: Int, type: String = "movie") =
        TasteSignals.keysFor(type, listOf("tmdb:$tmdb"), null, null)

    private fun ids(items: List<SearchTitleResult>) = items.map { it.id }

    @Test
    fun `the filter keeps only the selected type`() {
        val film = hit(1, "Star Wars", 1977)
        val show = hit(2, "Star Trek", 1966, type = "series")
        // An add-on's spelling of television, which must still be a series.
        val otherShow = hit(3, "Star Gate", 1997, type = "tv")
        val all = listOf(film, show, otherShow)

        assertEquals(
            listOf("tmdb:1", "tmdb:2", "tmdb:3"),
            ids(filterTitlesByType(all, TitleTypeFilter.ALL))
        )
        assertEquals(
            listOf("tmdb:1"),
            ids(filterTitlesByType(all, TitleTypeFilter.MOVIES))
        )
        assertEquals(
            listOf("tmdb:2", "tmdb:3"),
            ids(filterTitlesByType(all, TitleTypeFilter.SERIES))
        )
    }

    @Test
    fun `best match is the rail's existing order, with taste as its tiebreak`() {
        val liked = hit(1, "Dune", 2021)
        val plain = hit(2, "Dune", 2021)
        val older = hit(3, "Dune", 1984)
        val taste = TasteSignals.Snapshot(liked = signalled(1))

        assertEquals(
            "Best match must be searchTitleOrder verbatim - the fix adds sorts " +
                "beside it, it does not replace it",
            ids(listOf(older, plain, liked).sortedWith(searchTitleOrder("dune", taste))),
            ids(sortTitles(listOf(older, plain, liked), TitleSort.BEST_MATCH, "dune", taste))
        )
        assertEquals(listOf("tmdb:1", "tmdb:2", "tmdb:3"), ids(
            sortTitles(listOf(older, plain, liked), TitleSort.BEST_MATCH, "dune", taste)
        ))
    }

    @Test
    fun `newest orders by year with a missing year last`() {
        val unknown = hit(1, "Star", null)
        val old = hit(2, "Star", 1977)
        val new = hit(3, "Star", 2024)

        assertEquals(
            listOf("tmdb:3", "tmdb:2", "tmdb:1"),
            ids(sortTitles(listOf(unknown, old, new), TitleSort.NEWEST, "star"))
        )
    }

    @Test
    fun `newest breaks equal years on match quality, not on taste`() {
        // Same year, same band: the better match leads. The liked title here is
        // the WEAKER match, and taste must not lift it over the exact name.
        val exact = hit(1, "Star", 1977)
        val weaker = hit(2, "Star Wars", 1977)
        val taste = TasteSignals.Snapshot(liked = signalled(2))

        assertEquals(
            listOf("tmdb:1", "tmdb:2"),
            ids(sortTitles(listOf(weaker, exact), TitleSort.NEWEST, "star", taste))
        )
    }

    @Test
    fun `a to z orders by name ignoring case`() {
        val b = hit(1, "beta", 2001)
        val a = hit(2, "Alpha", 1999)
        val c = hit(3, "Charlie", 2020)

        assertEquals(
            listOf("tmdb:2", "tmdb:1", "tmdb:3"),
            ids(sortTitles(listOf(b, c, a), TitleSort.AZ, "a"))
        )
    }

    @Test
    fun `taste only breaks ties in best match, never in newest or a to z`() {
        // Equal year and equal match band (both "contains"), so Best match's
        // third key - taste - decides. Newest and A-Z must not consult it: the
        // liked title has the alphabetically LATER name, so A-Z and Newest put
        // it second either way.
        val liked = hit(1, "Star Wars", 1977)
        val plain = hit(2, "Star Trek", 1977)
        val taste = TasteSignals.Snapshot(liked = signalled(1))
        val hits = listOf(plain, liked)

        assertEquals(
            "taste lifts the liked hit inside its band in Best match",
            listOf("tmdb:1", "tmdb:2"),
            ids(sortTitles(hits, TitleSort.BEST_MATCH, "star", taste))
        )
        assertEquals(
            "newest ignores taste: same year, same band, so the name decides",
            listOf("tmdb:2", "tmdb:1"),
            ids(sortTitles(hits, TitleSort.NEWEST, "star", taste))
        )
        assertEquals(
            "A-Z ignores taste too",
            listOf("tmdb:2", "tmdb:1"),
            ids(sortTitles(hits, TitleSort.AZ, "star", taste))
        )
    }

    @Test
    fun `the hero count speaks in the filter's terms`() {
        assertEquals("Showing 24 of 148 titles", titleCountLabel(24, TitleTypeFilter.ALL, 148))
        assertEquals("40 titles", titleCountLabel(40, TitleTypeFilter.ALL, 40))
        assertEquals("1 title", titleCountLabel(1, TitleTypeFilter.ALL, 1))
        assertEquals("12 movies", titleCountLabel(12, TitleTypeFilter.MOVIES, 148))
        assertEquals("1 movie", titleCountLabel(1, TitleTypeFilter.MOVIES, 148))
        assertEquals("7 series", titleCountLabel(7, TitleTypeFilter.SERIES, 148))
        assertEquals("1 series", titleCountLabel(1, TitleTypeFilter.SERIES, 148))
    }

    @Test
    fun `an empty type filter says which type came up empty`() {
        assertEquals("No movies match.", titlesEmptyLabel(TitleTypeFilter.MOVIES))
        assertEquals("No series match.", titlesEmptyLabel(TitleTypeFilter.SERIES))
        assertEquals("No matches found.", titlesEmptyLabel(TitleTypeFilter.ALL))
    }
}
