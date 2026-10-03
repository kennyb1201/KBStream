package com.kennyb1201.kbstream.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order-preserving publisher shared by the browse screens' rail fan-out
 * (see `streamBrowseSections`) and the KB folder loader.
 *
 * Both of those used to make a screen wait for the slowest of their rails, and
 * both publish rails that finish out of order. What these cases pin is the
 * rule that keeps that from sliding rows under the viewer: a rail reaches the
 * screen only once every rail declared before it has reported, an empty rail
 * drops out without blocking the ones behind it, a rail the page already has
 * may grow in place, and a rail whose first report said "nothing here" is never
 * slipped into the middle of the list afterwards.
 */
class OrderedRailPublisherTest {

    @Test
    fun `rails are published in declaration order however they finish`() = runBlocking {
        val order = mutableListOf<Int>()
        val publisher = OrderedRailPublisher<String>(3) { index, _ -> order += index }

        // The LAST rail answers first and the first one answers last - the
        // arrangement that used to put the wrong rail at the top of the page.
        publisher.report(2, "c")
        publisher.report(1, "b")

        // Nothing can be published yet: rail 0 has not reported.
        assertEquals(emptyList<Int>(), order)

        publisher.report(0, "a")

        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `no rail is published while an earlier rail is still missing`() = runBlocking {
        val order = mutableListOf<Int>()
        val publisher = OrderedRailPublisher<String>(3) { index, _ ->
            // Everything below this rail must already be on screen; publishing
            // above a gap is what pushes the rows the viewer is looking at
            // further down.
            assertTrue(
                "rail $index published with ${(0 until index).filterNot { it in order }} missing",
                (0 until index).all { it in order }
            )
            order += index
        }

        publisher.report(1, "b")
        publisher.report(2, "c")
        publisher.report(0, "a")

        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `an empty rail drops out without blocking the rails behind it`() = runBlocking {
        val order = mutableListOf<Int>()
        val publisher = OrderedRailPublisher<String>(3) { index, _ -> order += index }

        publisher.report(2, "c")
        // Rail 0 arrives out of order and turns out to be empty: that is a rail
        // this page does not have, not a reason to hold back rail 1.
        publisher.report(0, null)
        publisher.report(1, "b")

        assertEquals(listOf(1, 2), order)
        assertEquals(listOf("b", "c"), publisher.finished())
    }

    @Test
    fun `a published rail grows in place on a later report`() = runBlocking {
        val order = mutableListOf<Pair<Int, String>>()
        val publisher = OrderedRailPublisher<String>(2) { index, item -> order += index to item }

        publisher.report(0, "a, page 1")
        publisher.report(1, "b")
        // The widening pass over a rail already on screen: it is replaced where
        // it sits, so the rails around it do not move.
        publisher.report(0, "a, deepened")

        assertEquals(
            listOf(0 to "a, page 1", 1 to "b", 0 to "a, deepened"),
            order
        )
        assertEquals(listOf("a, deepened", "b"), publisher.finished())
    }

    @Test
    fun `a rail that first reported empty is not slipped in mid-list`() = runBlocking {
        val order = mutableListOf<Pair<Int, String>>()
        val publisher = OrderedRailPublisher<String>(3) { index, item -> order += index to item }

        publisher.report(0, "a")
        publisher.report(1, null)
        publisher.report(2, "c")
        // Rail 1's widening pass finds rows, but the page has already drawn
        // without it.
        publisher.report(1, "b")

        assertEquals(listOf(0 to "a", 2 to "c"), order)
        // It is not lost either: it arrives with the finished set.
        assertEquals(listOf("a", "b", "c"), publisher.finished())
    }

    @Test
    fun `finished returns the rails in declaration order`() = runBlocking {
        val publisher = OrderedRailPublisher<String>(3) { _, _ -> }

        publisher.report(2, "c")
        publisher.report(0, "a")
        publisher.report(1, "b")

        assertEquals(listOf("a", "b", "c"), publisher.finished())
    }

    @Test
    fun `a publish callback may report another rail`() = runBlocking {
        val order = mutableListOf<Int>()
        lateinit var publisher: OrderedRailPublisher<String>

        // The publish callback hands rows to the screen; a screen that reacts
        // by asking for one more rail ("the page has its first row, fetch the
        // rest") reports from inside it. Under the old publish-inside-the-lock
        // shape this re-entered a non-reentrant Mutex and deadlocked.
        publisher = OrderedRailPublisher(2) { index, _ ->
            order += index
            if (index == 0) publisher.report(1, "b")
        }

        publisher.report(0, "a")

        assertEquals(listOf(0, 1), order)
        assertEquals(listOf("a", "b"), publisher.finished())
    }

    @Test
    fun `rails reporting at once still reach the screen in declaration order`() = runBlocking {
        val order = mutableListOf<Int>()
        val publisher = OrderedRailPublisher<Int>(6) { index, _ -> order += index }

        val latencies = listOf(30L, 0L, 10L, 25L, 5L, 15L)
        coroutineScope {
            latencies.forEachIndexed { index, latency ->
                launch {
                    delay(latency)
                    publisher.report(index, index)
                }
            }
        }

        assertEquals(listOf(0, 1, 2, 3, 4, 5), order)
    }
}
