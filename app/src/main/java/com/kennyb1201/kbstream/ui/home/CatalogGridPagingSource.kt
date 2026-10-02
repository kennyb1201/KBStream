package com.kennyb1201.kbstream.ui.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.kennyb1201.kbstream.data.addon.MetaPreview
import kotlinx.coroutines.CancellationException

/**
 * One fetched page of a catalog: the items that survived filtering, and how
 * many items the source actually served.
 *
 * The two differ because `skip` addresses the *source* catalog, not the
 * filtered list: an upcoming/kids filter can drop entries, so the next offset
 * has to advance by what the addon returned, or the next page would re-request
 * ground the filter already threw away.
 */
data class CatalogGridPage(
    val items: List<MetaPreview>,
    val fetchedCount: Int
)

/**
 * Offset-keyed [PagingSource] behind the "Open in Grid" full-catalog screen.
 *
 * The catalog is addressed by the item offset the addon last served, so a page
 * key is simply the next offset to ask for — the same bookkeeping the manual
 * pager this replaces did by hand, now owned by one object.
 *
 * The grid is opened from a Home rail that has *already* loaded its first
 * batch, so [seed] is shown as the first page (instant paint, no re-fetch of
 * items the rail holds) and the source resumes from [startOffset], where that
 * rail stopped. [seed] and [startOffset] are deliberately separate: the seed is
 * what to display, the offset is where the catalog actually is.
 *
 * Pages whose items are all already known are treated as the end of the list —
 * addon catalogs repeat ids across offsets, and returning them again would put
 * duplicate keys in the grid.
 */
class CatalogGridPagingSource(
    private val seed: List<MetaPreview>,
    private val startOffset: Int,
    private val maxItems: Int = Int.MAX_VALUE,
    private val moreAvailable: Boolean = true,
    private val loadPage: suspend (skip: Int) -> CatalogGridPage
) : PagingSource<Int, MetaPreview>() {

    private val seenIds = HashSet<String>()

    /** The offset the next page must be fetched from. */
    private var nextSkip = startOffset

    private var firstLoad = true

    /** A grid opened from its rail's identity never refreshes in place. */
    override fun getRefreshKey(state: PagingState<Int, MetaPreview>): Int? = null

    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, MetaPreview> {

        return try {

            // A catalog the rail already exhausted has nothing left to page:
            // show what the rail had and stop.
            if (!moreAvailable) {
                return LoadResult.Page(
                    data = seed.filter { seenIds.add(it.id) },
                    prevKey = null,
                    nextKey = null
                )
            }

            if (firstLoad && seed.isNotEmpty()) {
                firstLoad = false
                return LoadResult.Page(
                    data = seed.filter { seenIds.add(it.id) },
                    prevKey = null,
                    nextKey = nextKeyFrom(seenIds.size)
                )
            }

            firstLoad = false

            val skip = params.key ?: nextSkip

            val fetched = loadPage(skip)

            if (fetched.fetchedCount <= 0) {
                return endOfList()
            }

            nextSkip = skip + fetched.fetchedCount

            val fresh = fetched.items.filter { seenIds.add(it.id) }

            if (fresh.isEmpty()) {
                return endOfList()
            }

            LoadResult.Page(
                data = fresh,
                prevKey = null,
                nextKey = nextKeyFrom(seenIds.size)
            )
        } catch (
            e: CancellationException
        ) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun nextKeyFrom(loadedItems: Int): Int? =
        if (loadedItems < maxItems) nextSkip else null

    private fun endOfList(): LoadResult.Page<Int, MetaPreview> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
}
