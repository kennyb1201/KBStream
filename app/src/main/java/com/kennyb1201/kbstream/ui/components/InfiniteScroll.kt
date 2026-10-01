package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The floor on the prefetch runway: however narrow the viewport, a page is
 * never requested closer to the end than this.
 */
const val PREFETCH_MIN_ITEMS = 6

/**
 * The cap on the prefetch runway. A wall-to-wall grid lays out far more cells
 * than a rail lays out cards, so without this a grid would issue its second
 * page request the moment its first page painted.
 */
const val PREFETCH_MAX_ITEMS = 16

/**
 * How many items should still lie ahead of the viewport when the next page is
 * requested: two viewports' worth, floored by [PREFETCH_MIN_ITEMS] and capped
 * by [PREFETCH_MAX_ITEMS].
 *
 * Two viewports is the point. The trigger used to be a flat six items on
 * every surface — roughly one screenful — so the request went out only as the
 * last card was already on screen and the page arrived a beat later, which
 * reads as the end of the list. Asking from two screenfuls out gives the
 * fetch somewhere to finish before the viewer gets there.
 */
fun paginationPrefetchRunway(viewportItems: Int): Int =
    (viewportItems * 2).coerceIn(PREFETCH_MIN_ITEMS, PREFETCH_MAX_ITEMS)

/**
 * True when the next page should be requested now.
 *
 * [lastVisibleIndex] is the index of the last laid-out item (so it already
 * includes Compose's off-screen overscan) and [viewportItems] is how many
 * items are laid out. Both are read from `LazyListState.layoutInfo`.
 */
fun shouldPrefetchNextPage(
    lastVisibleIndex: Int,
    totalItems: Int,
    viewportItems: Int
): Boolean {
    if (totalItems <= 0 || lastVisibleIndex < 0) return false
    return lastVisibleIndex >= totalItems - paginationPrefetchRunway(viewportItems)
}

/**
 * The one infinite-scroll sentinel every paginated surface shares, so Home
 * rails, catalog grids, tags, studios, decades and folder rails all ask for
 * their next page at the same distance from the end.
 *
 * [onLoadMore] is expected to self-guard (each ViewModel does) — this only
 * decides *when* the question is worth asking.
 */
@Composable
fun InfiniteScrollEffect(
    listState: LazyListState,
    itemCount: Int,
    hasMore: Boolean = true,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(listState, itemCount, hasMore, isLoadingMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to info.visibleItemsInfo.size
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, viewportItems) ->
                if (
                    hasMore &&
                    !isLoadingMore &&
                    shouldPrefetchNextPage(lastVisibleIndex, itemCount, viewportItems)
                ) {
                    onLoadMore()
                }
            }
    }
}
