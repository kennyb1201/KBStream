package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.OrderedRailPublisher
import com.kennyb1201.kbstream.data.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Loads a browse screen's rails, handing each one to [onSection] as soon as it
 * is ready instead of making the screen wait for the slowest of them.
 *
 * Every browse page (a genre, a keyword, a network, a studio, a streaming
 * service, a decade) is [titles].size independent discover queries that used to
 * run under one `awaitAll()` and only then reach the ViewModel - so the screen
 * sat on its skeleton until the LAST rail answered, and the cost of opening it
 * was the slowest rail's, not the fastest one's. A rail is a TMDB discover call
 * with a vote floor, a language filter and a date cap on it, and those are not
 * uniformly fast: the one that runs last is routinely the one that decides when
 * anything appears at all.
 *
 * Two things make the first frame cheap:
 *
 *  1. **A page-1 fast path.** A rail deepens through up to
 *     [TmdbRepository.RAIL_DEPTH_MAX_PAGE] pages, but the deepening pages are
 *     only requested AFTER page 1 comes back, so a rail is two sequential
 *     round-trips at best. Stage one asks for page 1 alone ([load] with
 *     [deepen] = false) and publishes that, which is all a rail needs to be
 *     usable and scrollable; stage two runs the full deepening loader and
 *     republishes the rail with the extra rows. The deeper pages are the ones
 *     the view is least likely to reach, so they no longer hold up the first.
 *
 *     The second stage costs no extra request: `deepen` = false primes
 *     [TmdbRepository.finishDeepRailPage]'s page-1 cache entry under the same
 *     request key the deepening pass then reads, so the deep call starts from
 *     the rows already fetched.
 *
 *  2. **Publishing in rail order, not completion order.** Rails finish out of
 *     order - the third one is usually ready before the first - and simply
 *     appending whichever landed would slide every later rail down the screen
 *     as earlier ones arrived, moving focus and scroll position under the
 *     viewer. So a rail is published only once every rail before it has
 *     reported (with items, or as genuinely empty, which drops out), and the
 *     order the rails are declared in is the order they appear in. The rail
 *     that decides the first frame is therefore the first rail, which is the
 *     one the screen's initial focus expects anyway.
 *
 * [onSection] is optional: without it both stages collapse to the single deep
 * loader per rail that callers had before, and only the return value is used.
 *
 * The returned list is the finished rail set, in [titles] order, with empty
 * rails dropped - the same value the loaders returned when they awaited
 * everything at once.
 */
internal suspend fun streamBrowseSections(
    titles: List<String>,
    onSection: (suspend (index: Int, section: StudioSection) -> Unit)? = null,
    load: suspend (title: String, deepen: Boolean) -> TagRailPage
): List<StudioSection> = coroutineScope {
    val slots = arrayOfNulls<StudioSection>(titles.size)

    if (onSection == null) {
        titles.mapIndexed { index, title ->
            async {
                val page = load(title, true)
                if (page.items.isNotEmpty()) {
                    slots[index] = StudioSection(title, page.items)
                }
            }
        }.awaitAll()
        return@coroutineScope slots.filterNotNull()
    }

    // Ordering - publish in rail order, not completion order, and let the
    // deepening pass grow a rail the page already has - lives in
    // OrderedRailPublisher, shared with the KB folder screen's rail fan-out.
    val publisher = OrderedRailPublisher(titles.size, onSection)

    titles.mapIndexed { index, title ->
        async {
            // A failure in the fast path is not a failure of the rail: the
            // deepening loader below runs the same request and reports the
            // exception the caller has always seen. Swallowing it here is only
            // about not turning one flaky page-1 request into a screen-level
            // error when the retry a moment later would have worked. It is also
            // why a FAILED fast path reports nothing at all - this rail has not
            // had its first report yet, so the deepening pass gets to place it.
            //
            // An ANSWERED fast path with no rows does report, as an empty rail.
            // That is a rail this page turns out not to have, and saying so
            // keeps the widening pages from reviving it later, out of order.
            runCatchingCancellable { load(title, false) }.getOrNull()?.let { shallow ->
                publisher.report(
                    index,
                    shallow.items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
                )
            }

            val deep = load(title, true)
            publisher.report(
                index,
                deep.items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
            )
        }
    }.awaitAll()

    publisher.finished()
}
