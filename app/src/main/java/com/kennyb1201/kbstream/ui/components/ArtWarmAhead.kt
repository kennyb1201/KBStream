package com.kennyb1201.kbstream.ui.components

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.reporting.ImageLoadTrace
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Warms the bitmaps of cards the viewer has NOT reached yet (round four F5,
 * 2026-11-16).
 *
 * Round three's F3 did this for TV Home's rails, where the card sizes are
 * constants. Everywhere else the drawn size is not a constant: a Library grid
 * cell is whatever `GridCells.Adaptive` produced from the pane, a phone rail's
 * card is what the density made of a dp. Guessing the size there writes a cache
 * entry beside the one the card will read, which is a warm that buys nothing.
 *
 * So the size is not guessed - it is MEASURED. The card that is on screen has
 * already been laid out, so its `visibleItemsInfo.size` is exactly the size its
 * sibling cells will be drawn at, and it is exactly the size the coming card
 * will ask Coil for. The window is the next [WARM_AHEAD_CELLS] items past the
 * last one laid out, decoded into the memory cache in parallel, bounded and
 * de-duplicated.
 *
 * A warm that cannot answer (no url, no measured size, network gone) is
 * silent: the card is still correct, just first-sight as before.
 */
internal object ArtWarm {

    /**
     * Four at a time. A grid can lay out a dozen new cells in one frame; without
     * a ceiling that is a dozen simultaneous decodes on top of the frame that
     * asked for them.
     */
    private const val MAX_PARALLEL = 4

    /**
     * How many (url, size) pairs are remembered as done. Coil is the authority
     * on whether a bitmap is resident - this only stops a scroll that wiggles
     * back and forth from enqueueing the same warm repeatedly, so a bound that
     * a long session can exceed is fine.
     */
    private const val REMEMBERED = 512

    private val semaphore = Semaphore(MAX_PARALLEL)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val done = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > REMEMBERED
    }

    // The remembered set is only valid while the cache it describes is. A trim
    // or a clear empties the bitmaps, so the next window has to warm again -
    // which is what the pressure epoch already tells us (see MemoryPressure).
    private var doneEpoch: Long = -1L

    /** Test seam: forget everything, as a fresh process would. */
    internal fun reset() {
        synchronized(done) { done.clear() }
        inFlight.clear()
        doneEpoch = -1L
    }

    internal fun rememberedForTest(): Int = synchronized(done) { done.size }

    /**
     * Decodes [urls] into the memory cache at the size a card of
     * [widthPx] x [heightPx] will ask for. Returns when every warm has finished
     * or been abandoned; nothing throws.
     */
    internal suspend fun warmAll(
        context: Context,
        urls: List<String>,
        widthPx: Int,
        heightPx: Int
    ) {
        if (urls.isEmpty() || widthPx <= 0 || heightPx <= 0) return
        val epoch = MemoryPressure.imageCacheRevision()
        synchronized(done) {
            if (epoch != doneEpoch) {
                done.clear()
                doneEpoch = epoch
            }
        }
        val app = context.applicationContext
        coroutineScope {
            urls.asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .map { url -> async { warmOne(app, url, widthPx, heightPx) } }
                .toList()
                .awaitAll()
        }
    }

    private suspend fun warmOne(
        context: Context,
        url: String,
        widthPx: Int,
        heightPx: Int
    ) {
        val key = "$url|$widthPx|$heightPx"
        if (synchronized(done) { done.containsKey(key) }) return
        if (!inFlight.add(key)) return
        try {
            semaphore.withPermit {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(widthPx, heightPx)
                        .build()
                )
                // Counted only when the warm really decoded: a warm that hit the
                // memory cache bought nothing. `cardDiskDecodes - tileWarms` is
                // what a paste leaves as remaining first-sight decodes.
                if (result is SuccessResult && result.dataSource != DataSource.MEMORY_CACHE) {
                    ImageLoadTrace.noteRailTileWarm()
                }
                synchronized(done) { done[key] = Unit }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort by construction: the card still decodes on first sight.
        } finally {
            inFlight.remove(key)
        }
    }
}

/** How many cards past the last laid-out one a window warms. */
internal const val WARM_AHEAD_CELLS = 8

/**
 * Warms the next [WARM_AHEAD_CELLS] cells of a vertical grid, at the size the
 * grid itself just laid out - see [ArtWarm].
 *
 * [urlAt] answers the art url for an item index, or null when that item has no
 * art of its own (a folder, a live channel: nothing to warm).
 */
@Composable
internal fun WarmGridAhead(
    state: LazyGridState,
    count: Int,
    // The drawn size, when the surface knows it exactly (a poster is drawn at the
    // Poster Size setting's dp, which is not the cell's size - the caption takes
    // the rest). Null measures it from the grid instead.
    // Required: a LazyListItemInfo reports a MAIN-AXIS size only (`size: Int`),
    // so there was never a width and height to fall back to - the old
    // `sizePx ?: measured` put an Int where an IntSize was expected and did not
    // compile. Every caller knows the card's box, so it is always handed in.
    sizePx: IntSize,
    ahead: Int = WARM_AHEAD_CELLS,
    urlAt: (Int) -> String?
) {
    val context = LocalContext.current
    val latestUrlAt = rememberUpdatedState(urlAt)
    LaunchedEffect(state, count, ahead) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { last ->
                val size = sizePx
                if (last < 0 || size.width <= 0 || size.height <= 0) return@collect
                val from = last + 1
                val to = minOf(count - 1, last + ahead)
                if (from > to) return@collect
                val urls = (from..to).mapNotNull { latestUrlAt.value(it) }
                ArtWarm.warmAll(context, urls, size.width, size.height)
            }
    }
}

/**
 * The same window for a grid that puts its own rows above the art
 * (`entries` = its content in grid order, each pair the item's key and the url
 * its cell draws), and that draws a shape the cell's height does not describe.
 *
 * The index-based overload above cannot serve these: a phone Library grid's
 * index 0 is a header, its 1 and 2 are filter and sort strips, and whether it
 * has "lists" or "empty" rows is a state, so `lastVisible + 1` is not the next
 * poster. The KEY can be looked up in the caller's own ordering instead, which
 * needs no offset at all.
 *
 * [sizeFor] gets the measured cell WIDTH and answers the box the artwork is
 * drawn in: a poster tile fills the cell's width and takes 2:3 (or 16:9), while
 * the cell itself is taller because the captions stack under it. Passing the
 * cell height would warm a bitmap the card never asks for.
 */
@Composable
internal fun WarmGridAhead(
    state: LazyGridState,
    entries: List<Pair<String, String?>>,
    sizeFor: (Int) -> IntSize,
    ahead: Int = WARM_AHEAD_CELLS
) {
    val context = LocalContext.current
    LaunchedEffect(state, entries, ahead) {
        val urls = entries.associate { it.first to it.second }
        val order = entries.map { it.first }
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            (last?.key as? String) to (last?.size?.width ?: 0)
        }
            .distinctUntilChanged()
            .collect { (lastKey, cellWidth) ->
                if (lastKey == null || cellWidth <= 0) return@collect
                val position = order.indexOf(lastKey)
                if (position < 0) return@collect
                val size = sizeFor(cellWidth)
                if (size.width <= 0 || size.height <= 0) return@collect
                val next = order.drop(position + 1).take(ahead)
                    .mapNotNull { urls[it] }
                ArtWarm.warmAll(context, next, size.width, size.height)
            }
    }
}

/**
 * The same window for a grid whose cell draws MORE than one image - a game card
 * carries both teams' crests (round four F5).
 *
 * Everything else is the key walk above: [keys] is the grid's own content order,
 * [urlsFor] answers the images one cell will draw, and a key the caller does not
 * know (a section header) warms nothing.
 */
@Composable
internal fun WarmGridAhead(
    state: LazyGridState,
    keys: List<String>,
    urlsFor: (String) -> List<String>,
    sizePx: IntSize,
    ahead: Int = WARM_AHEAD_CELLS
) {
    val context = LocalContext.current
    LaunchedEffect(state, keys, ahead) {
        val latestUrlsFor = urlsFor
        snapshotFlow {
            (state.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String)
        }
            .distinctUntilChanged()
            .collect { lastKey ->
                if (lastKey == null) return@collect
                val position = keys.indexOf(lastKey)
                if (position < 0) return@collect
                val urls = keys.drop(position + 1).take(ahead)
                    .flatMap { latestUrlsFor(it) }
                if (urls.isEmpty()) return@collect
                ArtWarm.warmAll(context, urls, sizePx.width, sizePx.height)
            }
    }
}

/**
 * The landscape half of the same window (round four F5).
 *
 * In landscape mode a card's art is not a url it already holds: it is a resolve -
 * memo, disk row, then one TMDB lookup - so composing a screenful of cards is a
 * screenful of lookups arriving a frame late. This walks the same content keys
 * as [WarmGridAhead] and files the answers ahead of the cards, so the shared
 * resolver's `peek` can answer synchronously when they compose.
 *
 * Keyed like the warm for the same reason: a grid's index 0 may be a header.
 */
@Composable
internal fun PrefetchLandscapeAhead(
    state: LazyGridState,
    entries: List<Pair<String, LandscapeArtRequest?>>,
    ahead: Int = WARM_AHEAD_CELLS
) = PrefetchLandscapeWalk(entries, ahead) {
    state.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
}

/**
 * The same window for a horizontal RAIL (a `LazyRow`), added for spec
 * 2026-11-18 Warm-ahead F6: Home's rails are LazyRows, and the landscape half of
 * their warm was unreachable because the walk above takes a grid state.
 *
 * It is the same walk on the same keys - not a second implementation of the
 * rule - so a rail and a grid prefetch identically.
 */
@Composable
internal fun PrefetchLandscapeAhead(
    state: LazyListState,
    entries: List<Pair<String, LandscapeArtRequest?>>,
    ahead: Int = WARM_AHEAD_CELLS
) = PrefetchLandscapeWalk(entries, ahead) {
    state.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
}

/** The window both overloads share: the next [ahead] keys after the last laid out one. */
@Composable
private fun PrefetchLandscapeWalk(
    entries: List<Pair<String, LandscapeArtRequest?>>,
    ahead: Int,
    lastKey: () -> String?
) {
    val context = LocalContext.current
    LaunchedEffect(entries, ahead) {
        val requests = entries.associate { it.first to it.second }
        val order = entries.map { it.first }
        snapshotFlow { lastKey() }
            .distinctUntilChanged()
            .collect { key ->
                if (key == null) return@collect
                val position = order.indexOf(key)
                if (position < 0) return@collect
                val next = order.drop(position + 1).take(ahead).mapNotNull { requests[it] }
                if (next.isEmpty()) return@collect
                GlobalLandscapeArtCache.prefetch(context, next)
            }
    }
}

/**
 * The same window for a horizontal rail (a `LazyRow`): Detail's people and
 * media rows, the phone's Home rails.
 */
@Composable
internal fun WarmRowAhead(
    state: LazyListState,
    count: Int,
    // Required: a LazyListItemInfo reports a MAIN-AXIS size only (`size: Int`),
    // so there was never a width and height to fall back to — the old
    // `sizePx ?: measured` put an Int where an IntSize was expected and did not
    // compile. Every caller knows the card's box, so it is always handed in.
    sizePx: IntSize,
    ahead: Int = WARM_AHEAD_CELLS,
    urlAt: (Int) -> String?
) {
    val context = LocalContext.current
    val latestUrlAt = rememberUpdatedState(urlAt)
    LaunchedEffect(state, count, ahead) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { last ->
                val size = sizePx
                if (last < 0 || size.width <= 0 || size.height <= 0) return@collect
                val from = last + 1
                val to = minOf(count - 1, last + ahead)
                if (from > to) return@collect
                val urls = (from..to).mapNotNull { latestUrlAt.value(it) }
                ArtWarm.warmAll(context, urls, size.width, size.height)
            }
    }
}
