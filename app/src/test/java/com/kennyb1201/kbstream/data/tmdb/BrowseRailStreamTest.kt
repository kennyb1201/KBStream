package com.kennyb1201.kbstream.data.tmdb

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progressive browse-rail loader behind every genre / keyword / network /
 * studio / service / decade screen.
 *
 * Reported problem: those screens were slow to open. They waited for their
 * slowest rail - six discover queries, each deepening through up to three TMDB
 * pages - before drawing anything at all. What these cases pin is the contract
 * that replaces that: a rail reaches the screen as soon as it has rows, page 1
 * reaches it before the deepening does, rails reach it in display order rather
 * than in the order they happen to finish, and a rail that the fast path cannot
 * answer stops holding up the ones behind it without any of it turning into a
 * screen-level failure.
 */
class BrowseRailStreamTest {

    private val titles = listOf("MOVIES · RECENT", "MOVIES · POPULAR", "SERIES · RECENT")

    private fun item(id: Int) =
        StudioItem(TmdbDiscoverItem(id = id, name = "title $id"), "movie")

    private fun page(vararg ids: Int) = TagRailPage(ids.map(::item), hasMore = true)

    private val empty = TagRailPage(emptyList(), hasMore = false)

    @Test
    fun `without a callback every rail is fetched once, deeply`() = runBlocking {
        val calls = mutableListOf<String>()

        val result = streamBrowseSections(titles) { title, deepen ->
            calls += "$title|$deepen"
            page(1)
        }

        // The single deep pass is what the callers had before rails streamed;
        // the fast path must not double their request count.
        assertEquals(
            listOf("MOVIES · RECENT|true", "MOVIES · POPULAR|true", "SERIES · RECENT|true"),
            calls
        )
        assertEquals(titles, result.map { it.title })
    }

    @Test
    fun `a rail reaches the screen on page 1 and deepens in place afterwards`() = runBlocking {
        val published = mutableListOf<Pair<Int, Int>>()

        streamBrowseSections(titles, onSection = { index, section ->
            published += index to section.items.size
        }) { _, deepen ->
            if (deepen) page(1, 2) else page(1)
        }

        // Twice per rail: the single page that makes it usable, then the
        // deepened rail replacing it.
        assertEquals(listOf(1, 2), published.filter { it.first == 0 }.map { it.second })
        assertEquals(listOf(1, 2), published.filter { it.first == 1 }.map { it.second })
        assertEquals(listOf(1, 2), published.filter { it.first == 2 }.map { it.second })
    }

    @Test
    fun `rails reach the screen in display order however they finish`() = runBlocking {
        // The LAST rail answers first and the first rail answers last - the
        // arrangement that used to put the wrong rail at the top of the page.
        val latency = mapOf(
            "MOVIES · RECENT" to 60L,
            "MOVIES · POPULAR" to 0L,
            "SERIES · RECENT" to 30L
        )
        val published = mutableListOf<Int>()

        streamBrowseSections(titles, onSection = { index, _ -> published += index }) { title, _ ->
            delay(latency.getValue(title))
            page(1)
        }

        assertEquals(listOf(0, 1, 2), published.distinct())

        // And not merely at the start: no rail is ever published while a rail
        // above it is still missing from the screen, which is what slides the
        // rows the viewer is looking at.
        val seen = mutableSetOf<Int>()
        published.forEach { index ->
            assertTrue(
                "rail $index published with ${(0 until index).filterNot { it in seen }} missing",
                (0 until index).all { it in seen }
            )
            seen += index
        }
    }

    @Test
    fun `an empty rail is dropped without holding up the rails behind it`() = runBlocking {
        val published = mutableListOf<Int>()

        val result = streamBrowseSections(titles, onSection = { index, _ -> published += index }) { title, _ ->
            if (title == "MOVIES · POPULAR") empty else page(1)
        }

        assertEquals(listOf(0, 2), published.distinct())
        assertEquals(listOf("MOVIES · RECENT", "SERIES · RECENT"), result.map { it.title })
    }

    @Test
    fun `a rail that only fills from page 2 is not slipped in mid-list`() = runBlocking {
        val published = mutableListOf<Int>()

        val result = streamBrowseSections(titles, onSection = { index, _ -> published += index }) { title, deepen ->
            when {
                title != "MOVIES · POPULAR" -> page(1)
                // Nothing on page 1 (its rows are all filtered out, say), rows
                // from the deepening - so this rail appears late and in the
                // middle of the page.
                deepen -> page(7)
                else -> empty
            }
        }

        // Publishing it when its deeper rows arrive would insert a rail above
        // the ones already on screen and push them down under the viewer, so it
        // arrives with the finished set instead.
        assertEquals(listOf(0, 2), published.distinct())
        assertEquals(titles, result.map { it.title })
        assertEquals(listOf(7), result[1].items.map { it.item.id })
    }

    @Test
    fun `a failure on the fast path is not a failure of the rail`() = runBlocking {
        val published = mutableListOf<Pair<Int, Int>>()

        val result = streamBrowseSections(titles, onSection = { index, section ->
            published += index to section.items.size
        }) { title, deepen ->
            if (title == "MOVIES · RECENT" && !deepen) throw IllegalStateException("flaky")
            page(1)
        }

        // The single failed page-1 request is retried by the deepening loader a
        // moment later, so the rail is on screen rather than the screen showing
        // an error.
        assertEquals(listOf(1), published.filter { it.first == 0 }.map { it.second })
        assertEquals(titles, result.map { it.title })
    }

    @Test
    fun `a rail that really fails still reaches the caller`() = runBlocking {
        var threw = false

        try {
            streamBrowseSections(titles) { title, _ ->
                // The FIRST rail, so the failure is the one the caller sees
                // rather than a sibling's cancellation racing it.
                if (title == "MOVIES · RECENT") throw IllegalStateException("nope")
                page(1)
            }
        } catch (e: IllegalStateException) {
            threw = true
        }

        // Today's shape, kept: a rail that cannot be loaded at all is the
        // screen's error, not a quietly missing row of posters.
        assertTrue(threw)
    }
}
