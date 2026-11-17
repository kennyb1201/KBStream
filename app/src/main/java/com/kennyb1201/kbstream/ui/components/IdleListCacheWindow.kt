package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/**
 * The lazy lists' idle pre-composition window, in one place (spec 2026-11-16,
 * scrolling round three F5; extended to the rest of the app by dig four, which
 * found no beyond-bounds window anywhere but Home).
 *
 * A press or a flick into a new row used to pay that row's FIRST composition on
 * the frame it landed - on top of its decode and its metadata lookup. Compose's
 * lazy layouts keep an out-of-viewport window of items prepared in advance; by
 * default that window keeps nothing behind, only a narrow slice ahead, and it
 * does not refill while the list is idle.
 *
 * Half a viewport each way is about one row / one screenful, which is what a walk
 * or a flick actually steps into.
 *
 * Applied where the STATE is made, not where the list is drawn: in the Compose
 * Foundation this app resolves, a lazy layout reads its window from the state it
 * was handed ([rememberLazyListState] / [rememberLazyGridState] take a
 * `cacheWindow`), and a `cacheWindow` argument on `LazyColumn` / `LazyRow` /
 * `LazyVerticalGrid` does not exist yet. [rememberIdleLazyListState] and
 * [rememberIdleLazyGridState] are that decision in one place, so a surface gets
 * the window by taking its state from here.
 *
 * Deliberately NOT applied to the Guide grid: one of its rows composes the whole
 * six-hour span (see `ProgramRow`), so pre-composing a row there is the opposite
 * of cheap.
 */
@OptIn(ExperimentalFoundationApi::class)
object IdleListCacheWindow : LazyLayoutCacheWindow {
    override fun Density.calculateAheadWindow(viewport: Int): Int = viewport / 2
    override fun Density.calculateBehindWindow(viewport: Int): Int = viewport / 2
}

/**
 * A list state that carries [IdleListCacheWindow].
 *
 * The opt-in lives here rather than at every call site: the cache-window
 * overloads are experimental foundation API, and a screen should not have to
 * annotate itself to get the app's one pre-composition rule.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberIdleLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0
): LazyListState = rememberLazyListState(
    cacheWindow = IdleListCacheWindow,
    initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
    initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset
)

/** A grid state that carries [IdleListCacheWindow]; see [rememberIdleLazyListState]. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberIdleLazyGridState(): LazyGridState =
    rememberLazyGridState(cacheWindow = IdleListCacheWindow)
