package com.kennyb1201.kbstream.data.imdb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal pauses the data service for half an hour; two dropped requests pause
 * it for five minutes; success clears both.
 *
 * This is what keeps one IMDb `errors` body from turning every trailer lookup
 * into a four-second dead request for the rest of the session - the title page
 * keeps answering meanwhile, so the pause costs a slower trailer, never a
 * missing one.
 */
class ImdbTrailerBackoffTest {

    private val start = 1_000_000L

    @Test
    fun `a refusal pauses the service for half an hour`() {
        val backoff = ImdbApiBackoff()

        backoff.refused(start)

        assertFalse(
            "a GraphQL errors body is a refusal, and re-asking immediately only " +
                "adds latency to every hero rotation",
            backoff.allowed(start + ImdbApiBackoff.REFUSAL_PAUSE_MS - 1)
        )
        assertTrue(
            "and the pause expires on its own - no restart, no cache clear",
            backoff.allowed(start + ImdbApiBackoff.REFUSAL_PAUSE_MS)
        )
    }

    @Test
    fun `one dropped request does not pause anything`() {
        val backoff = ImdbApiBackoff()

        backoff.failed(start)

        assertTrue(
            "one blip is not a verdict; the next lookup may simply work",
            backoff.allowed(start)
        )
    }

    @Test
    fun `two dropped requests in a row pause for five minutes`() {
        val backoff = ImdbApiBackoff()

        backoff.failed(start)
        backoff.failed(start + 1_000L)

        assertFalse(
            backoff.allowed(start + 1_000L + ImdbApiBackoff.FAILURE_PAUSE_MS - 1)
        )
        assertTrue(
            backoff.allowed(start + 1_000L + ImdbApiBackoff.FAILURE_PAUSE_MS)
        )
    }

    @Test
    fun `a success forgets the failures before it`() {
        val backoff = ImdbApiBackoff()

        backoff.failed(start)
        backoff.succeeded()
        backoff.failed(start + 1_000L)

        assertTrue(
            "the count is of consecutive failures: a success in between starts " +
                "the pair over",
            backoff.allowed(start + 2_000L)
        )
    }

    @Test
    fun `a success lifts a refusal pause`() {
        val backoff = ImdbApiBackoff()

        backoff.refused(start)
        backoff.succeeded()

        assertTrue(
            "IMDb answering is the only evidence that matters, and it says the " +
                "pause is over",
            backoff.allowed(start + 1L)
        )
    }
}
