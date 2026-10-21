package com.kennyb1201.kbstream.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one YouTube client table.
 *
 * Before this there were two: the extractor's `CLIENTS` list and a hand-copied
 * UA ladder in [YoutubeChunkedDataSourceFactory]. Both were frozen on Feb-Mar
 * 2025 client versions, which is the identity YouTube had started bot-walling -
 * and because the two were independent literals, a version bump in one would
 * have left the other serving the old UA (a guaranteed false 403, since
 * googlevideo binds a signed URL to the UA that asked for it).
 *
 * These pin the shape a later change could quietly break: the chain order and
 * priorities, each client's UA being its own (never a copy of another's), the
 * factory's ladder being the table, and the legacy hint-less default staying
 * the android_vr identity the NewPipe/Piped path has always been resolved
 * under.
 */
class YouTubeClientsTest {

    @Test
    fun `the chain is five clients, android first and android_vr last`() {
        assertEquals(
            listOf("android", "tv", "ios", "web_embedded", "android_vr"),
            YouTubeClients.all.map { it.key }
        )
        assertEquals(listOf(0, 1, 2, 3, 4), YouTubeClients.all.map { it.priority })
    }

    @Test
    fun `the ids are the InnerTube client numbers`() {
        assertEquals(
            listOf("3", "7", "5", "56", "28"),
            YouTubeClients.all.map { it.id }
        )
        assertEquals(
            listOf("ANDROID", "TVHTML5", "IOS", "WEB_EMBEDDED_PLAYER", "ANDROID_VR"),
            YouTubeClients.all.map { it.context.getString("clientName") }
        )
    }

    @Test
    fun `keys, ids and priorities are unique`() {
        assertEquals(YouTubeClients.all.size, YouTubeClients.all.map { it.key }.toSet().size)
        assertEquals(YouTubeClients.all.size, YouTubeClients.all.map { it.id }.toSet().size)
        assertEquals(
            YouTubeClients.all.size,
            YouTubeClients.all.map { it.priority }.toSet().size
        )
    }

    @Test
    fun `every client's UA carries the version it presents`() {
        // The native clients: the UA is the app's own identity, and a UA that
        // disagrees with `x-youtube-client-version` is a mismatched client.
        YouTubeClients.all.filter { it.kind == ClientKind.APP }.forEach { client ->
            assertTrue(
                "${client.key} presents ${client.version} but its UA is ${client.userAgent}",
                client.userAgent.contains(client.version)
            )
        }
    }

    @Test
    fun `the context carries the same version as the entry`() {
        YouTubeClients.all.forEach { client ->
            assertEquals(
                client.key,
                client.version,
                client.context.getString("clientVersion")
            )
        }
    }

    @Test
    fun `the embedded client is a desktop browser, not a native app`() {
        val embedded = YouTubeClients.webEmbedded

        assertEquals(YouTubeClients.DEFAULT_USER_AGENT, embedded.userAgent)
        assertTrue(embedded.userAgent.contains("Chrome/"))
        assertFalse(embedded.userAgent.contains("com.google."))
        assertEquals(ClientKind.WEB_EMBEDDED, embedded.kind)
    }

    @Test
    fun `the tv client presents a TV-class UA, never the desktop one`() {
        val ua = YouTubeClients.tv.userAgent

        assertTrue("Cobalt is the TV app's browser: $ua", ua.contains("Cobalt/"))
        assertFalse("the TV client must not look like desktop Chrome: $ua", ua.contains("Chrome/"))
    }

    @Test
    fun `the embedded client declares the video's own embed page`() {
        val context = YouTubeClients.webEmbedded.contextFor(VIDEO_ID)

        assertEquals(
            "WEB_EMBEDDED_PLAYER",
            context.getJSONObject("client").getString("clientName")
        )
        assertEquals(
            "https://www.youtube.com/embed/$VIDEO_ID",
            context.getJSONObject("thirdParty").getString("embedUrl")
        )
    }

    @Test
    fun `a client that is not a web page sends no thirdParty context`() {
        YouTubeClients.all.filter { it.kind != ClientKind.WEB_EMBEDDED }.forEach { client ->
            assertFalse(client.key, client.contextFor(VIDEO_ID).has("thirdParty"))
        }
    }

    @Test
    fun `contexts are copies, so one request cannot mutate the table`() {
        val first = YouTubeClients.android.contextFor(VIDEO_ID)
        first.getJSONObject("client").put("clientVersion", "0.0.0")

        assertEquals(
            YouTubeClients.android.version,
            YouTubeClients.android.contextFor(VIDEO_ID)
                .getJSONObject("client").getString("clientVersion")
        )
    }

    @Test
    fun `the factory UA ladder is the table, in chain order`() {
        assertEquals(
            YouTubeClients.userAgents,
            YoutubeChunkedDataSourceFactory.KNOWN_CLIENT_USER_AGENTS
        )
        assertEquals(
            YouTubeClients.all.size,
            YoutubeChunkedDataSourceFactory.KNOWN_CLIENT_USER_AGENTS.size
        )
        assertEquals(
            "no two clients may share a UA string",
            YouTubeClients.all.size,
            YouTubeClients.userAgents.toSet().size
        )
    }

    @Test
    fun `the legacy hint-less default is still the android_vr entry`() {
        // Hint-less sources come from the NewPipe/Piped path. Their signing
        // expectations must not change with this table.
        assertEquals(
            YouTubeClients.androidVr.userAgent,
            YoutubeChunkedDataSourceFactory.LEGACY_USER_AGENT
        )
    }

    @Test
    fun `a client is addressable by key`() {
        YouTubeClients.all.forEach { client ->
            assertEquals(client.userAgent, YouTubeClients.userAgentFor(client.key))
            assertEquals(client, YouTubeClients.clientFor(client.key))
        }
        assertNull(YouTubeClients.userAgentFor("mweb"))
    }

    @Test
    fun `android is still the preferred client for adaptive picks`() {
        assertEquals("android", InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT)
        assertEquals(YouTubeClients.android.key, InnerTubeExtractor.PREFERRED_SEPARATE_CLIENT)
        assertEquals(0, YouTubeClients.android.priority)
    }

    private companion object {
        /** The 93 s hero trailer from the 2026-10-18 field capture. */
        const val VIDEO_ID = "gc0t8AfMP6k"
    }
}
