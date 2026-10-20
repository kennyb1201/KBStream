package com.kennyb1201.kbstream.data.trakt

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * `sync/watched` is paginated at 100, and the connect screen's 100/100 tiles
 * were the app reading page 1 as the whole history.
 *
 * The rule this pins is all-or-nothing: unlike a list rail, a watched history
 * that stops at page 2 reads as "unwatched" beyond the cut, so a failed page
 * must not leave the pages already fetched behind. The loop is exercised here
 * with plain integers and real [Response]s - the rate-guard/backoff hooks are
 * parameters - so no token, network or Android context is involved.
 */
class TraktWatchedPagingTest {

    private fun headers(vararg pairs: Pair<String, String>): Headers =
        Headers.headersOf(*pairs.flatMap { listOf(it.first, it.second) }.toTypedArray())

    private fun page(
        rows: List<Int>,
        vararg headers: Pair<String, String>
    ): Response<List<Int>> = Response.success(rows, headers(*headers))

    @Test
    fun `pages accumulate in order until the page count is reached`() = runBlocking {
        val requested = mutableListOf<Int>()
        val limits = mutableListOf<Int>()
        val pages = listOf(
            page(listOf(1, 2), "X-Pagination-Page-Count" to "3"),
            page(listOf(3, 4)),
            page(listOf(5))
        )

        val result = readAllWatchedPages(
            pageSize = 2,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { page, limit ->
                requested += page
                limits += limit
                pages[page - 1]
            }
        )

        assertEquals("every page, in page order", listOf(1, 2, 3, 4, 5), result)
        assertEquals("no page skipped or repeated", listOf(1, 2, 3), requested)
        assertEquals("the loop and the API agree on the page size", listOf(2, 2, 2), limits)
    }

    @Test
    fun `the page-count header ends the read even when the page is full`() = runBlocking {
        val requested = mutableListOf<Int>()

        val result = readAllWatchedPages(
            pageSize = 2,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { page, _ ->
                requested += page
                page(listOf(page * 10, page * 10 + 1), "X-Pagination-Page-Count" to "2")
            }
        )

        assertEquals(
            "a full page 2 is still the last page when Trakt says so",
            listOf(1, 2),
            requested
        )
        assertEquals(listOf(10, 11, 20, 21), result)
    }

    @Test
    fun `the item-count header is the page count when only it is present`() = runBlocking {
        val requested = mutableListOf<Int>()

        val result = readAllWatchedPages(
            pageSize = 100,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { page, _ ->
                requested += page
                page(List(100) { page }, "X-Pagination-Item-Count" to "150")
            }
        )

        assertEquals("150 items at 100 per page is two pages", listOf(1, 2), requested)
        assertEquals(200, result?.size)
    }

    @Test
    fun `without headers a short page ends the read`() = runBlocking {
        val requested = mutableListOf<Int>()
        val pages = listOf(page(List(100) { it }), page(List(40) { it }))

        val result = readAllWatchedPages(
            pageSize = 100,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { pageNumber, _ ->
                requested += pageNumber
                pages[pageNumber - 1]
            }
        )

        assertEquals(
            "a full page means 'ask again'; the short one is the end",
            listOf(1, 2),
            requested
        )
        assertEquals(140, result?.size)
    }

    @Test
    fun `a failure after page 1 fails the whole read`() = runBlocking {
        val requested = mutableListOf<Int>()

        val failed = readAllWatchedPages(
            pageSize = 2,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { pageNumber, _ ->
                requested += pageNumber
                if (pageNumber == 1) {
                    page(listOf(1, 2), "X-Pagination-Page-Count" to "3")
                } else {
                    Response.error<List<Int>>(
                        500,
                        "boom".toResponseBody("text/plain".toMediaType())
                    )
                }
            }
        )

        assertNull("page 1 must not be published as the history", failed)
        assertEquals("the read stops at the failure", listOf(1, 2), requested)
    }

    @Test
    fun `an exception on a later page fails the whole read`() = runBlocking {
        val requested = mutableListOf<Int>()

        val failed = readAllWatchedPages(
            pageSize = 2,
            acquire = { true },
            noteRetryAfter = {},
            fetch = { pageNumber, _ ->
                requested += pageNumber
                when (pageNumber) {
                    1 -> page(listOf(1, 2), "X-Pagination-Page-Count" to "3")
                    2 -> page(listOf(3, 4))
                    else -> throw IllegalStateException("socket closed")
                }
            }
        )

        assertNull("a transport fault mid-history is not a short history", failed)
        assertEquals(listOf(1, 2, 3), requested)
    }

    @Test
    fun `a rate-guard refusal on a later page fails the whole read`() = runBlocking {
        var spent = 0
        val requested = mutableListOf<Int>()

        val refused = readAllWatchedPages(
            pageSize = 2,
            acquire = {
                spent += 1
                spent <= 2
            },
            noteRetryAfter = {},
            fetch = { pageNumber, _ ->
                requested += pageNumber
                page(listOf(pageNumber, pageNumber + 1), "X-Pagination-Page-Count" to "3")
            }
        )

        assertNull("a spent budget keeps the previous snapshot, not page 1", refused)
        assertEquals("the third page was never requested", listOf(1, 2), requested)
    }

    @Test
    fun `a 429 is reported to the backoff hook before the page is judged`() = runBlocking {
        val noted = mutableListOf<Int>()

        val failed = readAllWatchedPages(
            pageSize = 2,
            acquire = { true },
            noteRetryAfter = { response -> noted += response?.code() ?: -1 },
            fetch = { _, _ ->
                Response.error<List<Int>>(
                    429,
                    "slow down".toResponseBody("text/plain".toMediaType())
                )
            }
        )

        assertNull(failed)
        assertEquals("the 429 reached noteRetryAfter", listOf(429), noted)
        assertTrue("exactly one attempt on the first failure", noted.size == 1)
    }
}
