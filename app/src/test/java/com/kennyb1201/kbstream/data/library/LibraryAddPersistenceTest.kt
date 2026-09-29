package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The add a browse screen makes, against the real stores.
 *
 * A network / studio / actor / tag / decade / collection rail knows a title's
 * TMDB id and nothing else. That shape is the one that produced "I picked my
 * list and my watchlist and nothing showed up": the id the trackers want is the
 * IMDB one, and nothing on those screens ever supplies it.
 *
 * `LibraryAdds.resolve` fills it in, and this pins the other half of the
 * contract — that an add keyed on a TMDB id alone still persists, and is found
 * again by a lookup carrying either id. Those are the two ways this can be
 * wrong in silence: an entry written with no usable id (invisible to the
 * trackers it mirrors to) or an entry the membership test cannot match (the
 * title saves and the menu keeps offering to save it again).
 *
 * Runs under a no-op [Application] for the same reason
 * `UpdateInstallConfirmationTest` does: the real one starts work on
 * `Dispatchers.IO` from `onCreate`, and this test mutates the stores it touches.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LibraryAddPersistenceTest.NoopApplication::class)
class LibraryAddPersistenceTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun clear() {
        LocalLibraryStore.myList(context).forEach { item ->
            LocalLibraryStore.removeFromMyList(
                context,
                item.mediaType,
                item.imdbId,
                item.tmdbId
            )
        }
        LocalLibraryStore.userLists(context).forEach { list ->
            LocalLibraryStore.deleteList(context, list.id)
        }
    }

    @Test
    fun `a TMDB id alone saves to My List and is found by either id`() {
        clear()

        // Exactly what the network screen hands over: a TMDB id, no IMDB id.
        val saved = LocalLibraryStore.addToMyList(
            context,
            mediaType = "series",
            imdbId = null,
            tmdbId = 1399,
            title = "Game of Thrones",
            year = 2011,
            posterUrl = null
        )

        assertTrue(saved)
        assertEquals(1, LocalLibraryStore.myList(context).size)

        // The store keeps what it was given...
        val item = LocalLibraryStore.myList(context).single()
        assertEquals(1399, item.tmdbId)
        assertEquals(null, item.imdbId)

        // ...and a lookup from either half of the pair finds it, which is what
        // the menu's "In Library ✓" and the picker's ✓ read.
        assertTrue(LocalLibraryStore.isInMyList(context, "series", null, 1399))
        assertTrue(LocalLibraryStore.isInMyList(context, "series", "tt0944947", 1399))
        assertFalse(LocalLibraryStore.isInMyList(context, "series", null, 1400))
    }

    @Test
    fun `adding twice is idempotent and reports success both times`() {
        clear()

        LocalLibraryStore.addToMyList(context, "movie", null, 27205, "Inception", 2010, null)
        val second = LocalLibraryStore.addToMyList(
            context,
            "movie",
            "tt1375666",
            27205,
            "Inception",
            2010,
            null
        )

        // A second press is not a failure: the row it came from has to tick, or
        // a title whose tracker mirror failed earlier can never be re-sent.
        assertTrue(second)
        assertEquals(1, LocalLibraryStore.myList(context).size)
    }

    @Test
    fun `a TMDB id alone lands in a local personal list`() {
        clear()

        val list = LocalLibraryStore.createList(context, "My Watchlist")
        assertNotNull(list)
        val listId = list!!.id
        assertTrue(listId < 0)

        val saved = LocalLibraryStore.addToLocalList(
            context,
            listId = listId,
            mediaType = "movie",
            imdbId = null,
            tmdbId = 27205,
            title = "Inception",
            year = 2010,
            posterUrl = null
        )

        assertTrue(saved)
        val items = LocalLibraryStore.listItems(context, listId)
        assertEquals(1, items.size)
        assertEquals("Inception", items.single().title)

        // The picker's ✓ for that row is read with the same either-id rule.
        assertTrue(
            items.any { LocalLibraryStore.matches(it, "movie", "tt1375666", 27205) }
        )
        assertEquals(1, LocalLibraryStore.userLists(context).single().itemCount)
    }

    @Test
    fun `an add with no id at all is refused rather than stored`() {
        clear()

        val saved = LocalLibraryStore.addToMyList(
            context,
            "movie",
            null,
            null,
            "Untitled",
            null,
            null
        )

        assertFalse(saved)
        assertTrue(LocalLibraryStore.myList(context).isEmpty())
    }
}
