package com.kennyb1201.kbstream.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes one item per rail as the rails become ready, in the order they
 * were declared rather than the order they happen to finish.
 *
 * [count] is the number of rails and [publish] is handed `(index, item)` for
 * each rail that belongs on screen, at the moment it is safe to put it there.
 * Rails finish out of order - a slow discover query behind a fast list source
 * - and appending whichever landed would slide every later rail down the
 * screen as earlier ones arrived, moving focus and scroll position under the
 * viewer. So an item is published only once every rail before it has reported
 * (with an item, or as genuinely empty, which drops out of the page).
 *
 * The first report decides whether a rail is one the page has rows for. A rail
 * that reported empty and only later found rows is deliberately NOT slipped in
 * mid-list: inserting it above the rails already on screen is exactly the
 * shift this exists to prevent, and it reaches the caller through [finished]
 * instead. A rail that has already been published does grow in place, which is
 * what a second, wider pass over a rail the viewer can already see is.
 *
 * Deciding what to publish runs under one lock, so two rails reporting at once
 * can never interleave and hand the caller a list in an order the screen has
 * not agreed to. The [publish] CALLBACK, though, runs after the lock is
 * released: a report that is ready to publish collects its callbacks under the
 * lock, then emits them outside it. Publishing while holding the lock made a
 * slow callback - and these hand rows to the screen - stall every other rail's
 * report behind it, and a callback that re-entered [report] (or awaited
 * anything that did) deadlocked outright.
 *
 * The cost is that two callbacks may now run concurrently if two reports are
 * processed at once. The caller's [publish] is a callback onto the UI stream
 * and is expected to be safe to call from more than one coroutine; the ORDER
 * within a single report is still exactly the declaration order the class
 * promises.
 */
internal class OrderedRailPublisher<T>(
    private val count: Int,
    private val publish: suspend (index: Int, item: T) -> Unit
) {
    /** Rails that have reported at least once, with an item or as empty. */
    private val reported = BooleanArray(count)

    /**
     * Whether a rail had rows the first time it reported, which is what makes
     * it a rail the page gets. Decided once and never revisited (see the class
     * comment).
     */
    private val hasRows = BooleanArray(count)

    private val rows: MutableList<T?> = MutableList(count) { null }

    /**
     * How many rails from the front have been consumed: published if they have
     * rows, stepped over if they turned out to be empty.
     */
    private var published = 0

    private val mutex = Mutex()

    /**
     * Record rail [index]'s latest [item] (null = this rail has none) and
     * publish whatever that makes publishable.
     */
    suspend fun report(index: Int, item: T?) {
        // Collected under the lock, emitted after it (see the class comment).
        val ready = mutex.withLock {
            val pending = mutableListOf<Pair<Int, T>>()

            if (item != null) rows[index] = item

            if (!reported[index]) {
                reported[index] = true
                hasRows[index] = item != null
            } else if (index < published && hasRows[index] && item != null) {
                // A later pass over a rail the page already has. It grows in
                // place, so the rails around it - and therefore the focus and
                // scroll position - are untouched.
                pending += index to item
                return@withLock pending
            }

            while (published < count && reported[published]) {
                if (hasRows[published]) {
                    rows[published]?.let { item2 -> pending += published to item2 }
                }
                published++
            }

            pending
        }

        ready.forEach { (rail, railItem) -> publish(rail, railItem) }
    }

    /** The rails that reported rows, in declaration order. */
    fun finished(): List<T> = rows.filterNotNull()
}
