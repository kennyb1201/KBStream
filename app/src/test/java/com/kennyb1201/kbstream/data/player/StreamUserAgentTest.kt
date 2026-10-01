package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The User-Agent both engines present, and the headers that carry it.
 *
 * This is the seam that made the same stream play in the main player and fail
 * in the backup engine: ExoPlayer fell back to the app's browser User-Agent
 * while mpv, which had no fallback at all, asked as `mpv/<version>`. The cases
 * below pin the resolution order and the "sent exactly once" rule the two
 * engines now share.
 */
class StreamUserAgentTest {

    @Test
    fun `a source that names no agent gets the app's own`() {
        assertEquals(StreamUserAgent.DEFAULT, StreamUserAgent.resolve(emptyMap()))
    }

    @Test
    fun `a source's own agent wins, whichever case it is spelled in`() {
        // Signed URLs (googlevideo, a proxy naming its own client) are refused
        // when they are asked for as something else, so the source's header is
        // never overridden by the default.
        assertEquals(
            "SignedAgent/1.0",
            StreamUserAgent.resolve(mapOf("User-Agent" to "SignedAgent/1.0"))
        )
        assertEquals(
            "SignedAgent/1.0",
            StreamUserAgent.resolve(mapOf("user-agent" to "SignedAgent/1.0"))
        )
        assertEquals(
            "SignedAgent/1.0",
            StreamUserAgent.resolve(mapOf("USER-AGENT" to "SignedAgent/1.0"))
        )
    }

    @Test
    fun `a blank agent falls through rather than being sent`() {
        assertEquals(StreamUserAgent.DEFAULT, StreamUserAgent.resolve(mapOf("User-Agent" to "")))
        assertEquals(StreamUserAgent.DEFAULT, StreamUserAgent.resolve(mapOf("User-Agent" to "   ")))
    }

    @Test
    fun `the other headers are kept when the agent is stripped`() {
        val stripped = StreamUserAgent.withoutUserAgent(
            mapOf(
                "User-Agent" to "SignedAgent/1.0",
                "Referer" to "https://example.test/",
                "Cookie" to "a=b"
            )
        )

        assertFalse(stripped.keys.any { it.equals("User-Agent", ignoreCase = true) })
        assertEquals(
            mapOf("Referer" to "https://example.test/", "Cookie" to "a=b"),
            stripped
        )
    }

    @Test
    fun `headers with no agent are returned untouched`() {
        val headers = mapOf("Referer" to "https://example.test/")

        assertTrue(StreamUserAgent.withoutUserAgent(headers) == headers)
    }
}
