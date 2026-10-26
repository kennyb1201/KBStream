package com.kennyb1201.kbstream.data.tmdb

import com.kennyb1201.kbstream.data.OrderedRailPublisher
import com.kennyb1201.kbstream.data.runCatchingCancellable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Thrown when EVERY rail a browse page asked for failed to load.
 *
 * A browse page's rails are independent, and one unreachable rail is swallowed
 * on purpose: the page draws the others (see `streamBrowseSections`). But a
 * TMDB outage fails all of them, and the finished rail set then looked exactly
 * like a category that genuinely has no titles - so the screen took its empty
 * branch ("Nothing found for <name>", no retry) instead of the error branch it
 * already had, and the viewer had to back out and re-enter to try again. A page
 * with nothing to draw because every rail FAILED says so; a page whose rails
 * answered with no rows is untouched, which is why the signal has to come from
 * here rather than from `sections.isEmpty()` on the screen.
 *
 * [failedRails] and [railCount] are carried for the screen and for the tests;
 * [message] is what the browse screens render, since they publish the
 * exception's own message as the error text.
 */
internal class BrowseRailsUnavailableException(
    val failedRails: Int,
    val railCount: Int
) : IllegalStateException(
    "Couldn't reach TMDB: all $railCount rails failed."
)


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

    // How many rails failed outright (see [TagRailPage.failed]). Counted here
    // because this is the only layer that knows how many rails the page asked
    // for: a failed rail is dropped from the finished set exactly as a rail
    // that genuinely has no rows is, so the list alone cannot tell a TMDB
    // outage from an empty category.
    val failedRails = AtomicInteger(0)

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
            //
            // The two used to be the same event: every discover branch collapsed
            // a failed request to `emptyList()`, so a flaky page 1 REPORTED as an
            // empty rail, the publisher sealed it, and the deepening pass that
            // carried its rows was dropped - a rail whose first request flaked
            // could only appear once the slowest rail on the page had finished,
            // which is exactly the case the two-stage publish exists for. The
            // page now says which of the two it is (see [TagRailPage.failed]), so
            // a failed fast path stays unreported and the retry behind it places
            // the rail, in order.
            var reported = false
            runCatchingCancellable { load(title, false) }.getOrNull()?.let { shallow ->
                if (shallow.failed) return@let
                reported = true
                publisher.report(
                    index,
                    shallow.items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
                )
            }

            val deep = load(title, true)
            when {
                !deep.failed -> {
                    reported = true
                    publisher.report(
                        index,
                        deep.items.takeIf { it.isNotEmpty() }?.let { StudioSection(title, it) }
                    )
                }
                // Both stages failed: this rail has still not reported, and the
                // publisher holds every rail BELOW it until it does. Report the
                // failure as the empty answer it is - the finished set still
                // carries whatever the next open finds, and the rails behind this
                // one are not held hostage to it.
                !reported -> publisher.report(index, null)
            }
            if (deep.failed) failedRails.incrementAndGet()
        }
    }.awaitAll()

    val finished = publisher.finished()

    // Every rail failed: report that instead of handing the caller an empty
    // list. Per-rail failures stay swallowed - that is what keeps one flaky
    // rail from taking the page down with it - but with nothing at all left to
    // draw, the empty list made an outage and an empty category the same fact,
    // and the screens' error branch (the one with the retry on it) could not
    // be reached by the failure path at all. Every caller of this function is
    // a browse screen that renders that branch.
    if (finished.isEmpty() && titles.isNotEmpty() && failedRails.get() >= titles.size) {
        throw BrowseRailsUnavailableException(
            failedRails = failedRails.get(),
            railCount = titles.size
        )
    }

    finished
}
