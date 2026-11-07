package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watched-marker pass only badges what it preloads, so "which rows are in
 * that set" is the whole bug: a watched title on a pinned Library rail wore no
 * eye or checkmark because its key was never in the input.
 *
 * These are the pure rules behind the combined set - both id forms, the type
 * normalization, one row per title however many sources carry it, and the
 * content comparison that keeps a re-delivery of the same rows from starting a
 * refresh.
 */
class WatchedMarkerItemsTest {

    private fun meta(
        id: String,
        type: String = "movie",
        name: String = ""
    ): MetaPreview =
        MetaPreview(id = id, type = type, name = name)

    @Test
    fun `both id forms resolve, and the type is normalized once`() {
        assertEquals("tt123" to "movie", watchedMarkerItem(meta(" tt123 ", "movie")))
        assertEquals("tmdb:603" to "movie", watchedMarkerItem(meta("tmdb:603", "movie")))
        assertEquals("tmdb:603" to "series", watchedMarkerItem(meta("tmdb:603", "tv")))
        assertEquals("tt9" to "series", watchedMarkerItem(meta("tt9", "show")))
    }

    @Test
    fun `a row no card could be drawn for never reaches the preload`() {
        // T5: the loader drops these before they can become a rail row, and the
        // marker pass drops them again rather than asking the caches for an id
        // that cannot resolve.
        assertNull(watchedMarkerItem(meta("")))
        assertNull(watchedMarkerItem(meta("   ")))
        assertNull(watchedMarkerItem(meta("kitsu:12")))
        assertNull(watchedMarkerItem(meta("tt1", "person")))
    }

    @Test
    fun `a title that exists only on a pinned list is preloaded`() {
        // T1's boundary: it is in NO catalog rail and NO Top Pick.
        val preload =
            watchedMarkerPreload(
                watchedMarkerMetas(
                    railItems = listOf(meta("tt1", "movie", "A catalog title")),
                    topPickItems = emptyList(),
                    libraryItems =
                        listOf(
                            meta("tt777", "series", "Only on a pinned Trakt list"),
                            meta("tmdb:555", "movie", "Only on a pinned MDBList list")
                        )
                )
            )

        assertTrue(
            "a title only the pinned rails carry must be in the preload, " +
                "or its card can never be resolved watched",
            preload.contains("tt777" to "series")
        )
        assertTrue(
            "and the same holds for a tmdb-keyed row - the form the shoulder " +
                "seasons and every TMDB-backed Library row use",
            preload.contains("tmdb:555" to "movie")
        )
    }

    @Test
    fun `a title on a catalog rail and a pinned list preloads once`() {
        // T2.
        val row = meta("tt42", "movie", "Both places")
        val preload =
            watchedMarkerPreload(
                watchedMarkerMetas(
                    railItems = listOf(row),
                    topPickItems = listOf(row),
                    libraryItems = listOf(row.copy(name = "Both places, again"))
                )
            )

        assertEquals(
            "one title, one key: a duplicate would be double work, and the " +
                "badge itself is drawn from a set",
            1,
            preload.count { item -> item.first == "tt42" }
        )
    }

    @Test
    fun `the same rows in new list instances are the same set`() {
        // T4, first half: Compose re-delivers the rows on every recompose, and
        // the screens' effects hand over a fresh `flatMap` list each time.
        val rows =
            listOf(
                meta("tt1", "movie", "One"),
                meta("tt2", "series", "Two")
            )

        assertEquals(libraryRailItemKeys(rows), libraryRailItemKeys(rows.toList()))
        assertEquals(
            "order is not identity: a re-sorted rail is the same titles",
            libraryRailItemKeys(rows),
            libraryRailItemKeys(rows.reversed())
        )
        assertEquals(
            "and neither is artwork, which cannot change a marker",
            libraryRailItemKeys(rows),
            libraryRailItemKeys(
                rows.map { row -> MetaPreview(id = row.id, type = row.type, name = row.name) }
            )
        )
    }

    @Test
    fun `a cleared or grown set is a different set`() {
        // T4, second half: an empty report must clear the store (a profile with
        // no pins cannot keep the last profile's rows), and a newly pinned list
        // must refresh.
        val rows = listOf(meta("tt1", "movie", "One"))

        assertTrue(libraryRailItemKeys(emptyList()) != libraryRailItemKeys(rows))
        assertTrue(libraryRailItemKeys(rows) != libraryRailItemKeys(rows + meta("tt2", "movie")))
        assertTrue(
            "the same id under another type is another title to resolve",
            libraryRailItemKeys(rows) !=
                libraryRailItemKeys(listOf(meta("tt1", "series", "One")))
        )
    }
}
