package com.kennyb1201.kbstream.ui.home

import androidx.paging.PagingSource
import com.kennyb1201.kbstream.data.addon.MetaPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offset/de-dupe/end-of-list rules the grid's paging depends on. These are
 * pure JVM checks of the PagingSource itself - the screen only reacts to what
 * it returns.
 */
class CatalogGridPagingSourceTest {

    private fun meta(id: String) =
        MetaPreview(id = id, type = "movie", name = "Title $id")

    private fun refresh() =
        PagingSource.LoadParams.Refresh<Int>(
            key = null,
            loadSize = 100,
            placeholdersEnabled = false
        )

    private fun append(key: Int) =
        PagingSource.LoadParams.Append<Int>(
            key = key,
            loadSize = 100,
            placeholdersEnabled = false
        )

    @Test
    fun `seed is the first page and paging resumes where the rail stopped`() =
        runBlocking {

            val a = meta("a")
            val b = meta("b")

            val source = CatalogGridPagingSource(
                seed = listOf(a, b),
                startOffset = 100,
                loadPage = { error("the seed must not be re-fetched") }
            )

            val page =
                source.load(refresh()) as PagingSource.LoadResult.Page

            assertEquals(listOf(a, b), page.data)
            // Resume at the rail's own offset, not at the seed's size.
            assertEquals(100, page.nextKey)
            assertNull(page.prevKey)
        }

    @Test
    fun `an append asks for the resume offset and advances by what the source served`() =
        runBlocking {

            val requested = mutableListOf<Int>()

            val source = CatalogGridPagingSource(
                seed = listOf(meta("a")),
                startOffset = 100,
                loadPage = { skip ->
                    requested += skip
                    // Filtering dropped three of the five items the addon
                    // served, so the next offset must advance by five.
                    CatalogGridPage(
                        items = listOf(meta("b"), meta("c")),
                        fetchedCount = 5
                    )
                }
            )

            source.load(refresh())

            val page =
                source.load(append(100)) as PagingSource.LoadResult.Page

            assertEquals(listOf(100), requested)
            assertEquals(listOf("b", "c"), page.data.map { it.id })
            assertEquals(105, page.nextKey)
        }

    @Test
    fun `a page of already-known ids ends the list`() =
        runBlocking {

            val a = meta("a")

            val source = CatalogGridPagingSource(
                seed = listOf(a),
                startOffset = 10,
                loadPage = { CatalogGridPage(items = listOf(a), fetchedCount = 4) }
            )

            source.load(refresh())

            val page =
                source.load(append(10)) as PagingSource.LoadResult.Page

            assertTrue(page.data.isEmpty())
            assertNull(page.nextKey)
        }

    @Test
    fun `an empty fetch ends the list`() =
        runBlocking {

            val source = CatalogGridPagingSource(
                seed = listOf(meta("a")),
                startOffset = 10,
                loadPage = { CatalogGridPage(items = emptyList(), fetchedCount = 0) }
            )

            source.load(refresh())

            val page =
                source.load(append(10)) as PagingSource.LoadResult.Page

            assertTrue(page.data.isEmpty())
            assertNull(page.nextKey)
        }

    @Test
    fun `an exhausted rail shows its items and never fetches`() =
        runBlocking {

            var loaded = false

            val a = meta("a")
            val b = meta("b")

            val source = CatalogGridPagingSource(
                seed = listOf(a, b),
                startOffset = 0,
                moreAvailable = false,
                loadPage = {
                    loaded = true
                    CatalogGridPage(items = emptyList(), fetchedCount = 0)
                }
            )

            val page =
                source.load(refresh()) as PagingSource.LoadResult.Page

            assertEquals(listOf(a, b), page.data)
            assertNull(page.nextKey)
            assertFalse(loaded)
        }

    @Test
    fun `the item ceiling stops the paging`() =
        runBlocking {

            val source = CatalogGridPagingSource(
                seed = listOf(meta("a"), meta("b")),
                startOffset = 2,
                maxItems = 2,
                loadPage = { CatalogGridPage(items = emptyList(), fetchedCount = 0) }
            )

            val page =
                source.load(refresh()) as PagingSource.LoadResult.Page

            assertEquals(2, page.data.size)
            assertNull(page.nextKey)
        }

    @Test
    fun `an empty seed starts at the resume offset and can fail as a LoadResult`() =
        runBlocking {

            val requested = mutableListOf<Int>()

            val source = CatalogGridPagingSource(
                seed = emptyList(),
                startOffset = 40,
                loadPage = { skip ->
                    requested += skip
                    CatalogGridPage(items = listOf(meta("a")), fetchedCount = 1)
                }
            )

            val page =
                source.load(refresh()) as PagingSource.LoadResult.Page

            assertEquals(listOf(40), requested)
            assertEquals(41, page.nextKey)
        }

    @Test
    fun `a throwing loader becomes a LoadResult Error`() =
        runBlocking {

            val source = CatalogGridPagingSource(
                seed = emptyList(),
                startOffset = 0,
                loadPage = { throw IllegalStateException("catalog unavailable") }
            )

            val result = source.load(refresh())

            assertTrue(result is PagingSource.LoadResult.Error)
        }
}
