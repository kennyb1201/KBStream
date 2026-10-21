package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The PO-token provider's behavioral contract, against a fake mint.
 *
 * The real mint is a WebView running BotGuard, which is exactly the part that
 * cannot be unit-tested and exactly the part that is allowed to fail. What has
 * to be pinned is everything AROUND it: a mint fills both caches from one
 * session, a cache hit never re-mints, a new visitor orphans the player token, a
 * mint that hangs answers null inside the budget, the kill switch never consults
 * the minter at all, and [PoTokenProvider.invalidate] drops both caches so a
 * refused token is never spent twice.
 */
class PoTokenProviderTest {

    @Before
    fun clearTrace() {
        PoTokenTrace.clear()
    }

    @Test
    fun `one mint fills both caches`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter)

        assertEquals("gvs", provider.gvsToken("v1", "vis"))
        assertEquals("player", provider.playerToken("vis"))
        assertEquals("one WebView session serves both contexts", 1, minter.mints.size)
    }

    @Test
    fun `a cache hit does not re-mint`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.gvsToken("v1", "vis")
        provider.gvsToken("v1", "vis")
        provider.playerToken("vis")

        assertEquals(1, minter.mints.size)
        assertTrue(PoTokenTrace.lines().any { it.startsWith("pot.gvs: state=cached") })
    }

    @Test
    fun `a new visitor orphans the player token`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.gvsToken("v1", "visA")
        assertEquals("player", provider.playerToken("visA"))

        provider.gvsToken("v2", "visB")
        assertEquals("player", provider.playerToken("visB"))
        assertNull(
            "the token minted for the old visitor must not be served again",
            provider.playerToken("visA")
        )
        assertEquals(2, minter.mints.size)
    }

    @Test
    fun `invalidate clears both caches`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.gvsToken("v1", "vis")
        assertTrue(provider.playerToken("vis") != null)

        provider.invalidate()

        assertNull("the player token is gone with the identity", provider.playerToken("vis"))
        assertEquals("the stream token is re-minted", "gvs", provider.gvsToken("v1", "vis"))
        assertEquals(2, minter.mints.size)
        assertTrue(PoTokenTrace.lines().any { it.contains("pot.cache: cleared") })
    }

    @Test
    fun `a mint that produces nothing resolves null and is not cached`() = runBlocking {
        val minter = FakeMinter(playerToken = null, gvsToken = null)
        val provider = WebViewPoTokenProvider(minter)

        assertNull(provider.gvsToken("v1", "vis"))
        assertNull(provider.playerToken("vis"))
        assertNull(provider.gvsToken("v1", "vis"))

        assertEquals("nothing to cache, so the next resolve tries again", 2, minter.mints.size)
    }

    @Test
    fun `a mint that throws resolves null and records the class`() = runBlocking {
        val minter = FakeMinter(failure = IllegalStateException("botguard changed"))
        val provider = WebViewPoTokenProvider(minter)

        assertNull(provider.gvsToken("v1", "vis"))
        assertTrue(
            PoTokenTrace.lines().any { it.contains("state=failed:IllegalStateException") }
        )
    }

    @Test
    fun `a mint that never answers times out inside the budget`() = runBlocking {
        val minter = FakeMinter(delayMs = 5_000)
        val provider = WebViewPoTokenProvider(minter, mintTimeoutMs = 50)

        val startedAt = System.nanoTime()
        assertNull(provider.gvsToken("v1", "vis"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue("a hung mint must not hold the resolve: ${elapsedMs}ms", elapsedMs < 1_000L)
        assertTrue(PoTokenTrace.lines().any { it.contains("state=failed:timeout") })
    }

    @Test
    fun `the kill switch never consults the minter`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter, enabled = { false })

        assertNull(provider.playerToken("vis"))
        assertNull(provider.gvsToken("v1", "vis"))
        assertTrue("off must mean off, not \"mint and discard\"", minter.mints.isEmpty())
    }

    @Test
    fun `a player token is never minted on its own`() = runBlocking {
        // A player token is a byproduct of the video-scoped mint (there is no
        // page to load without a video), so asking first answers null rather
        // than loading an unrelated page.
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter)

        assertNull(provider.playerToken("vis"))
        assertTrue(minter.mints.isEmpty())
    }

    @Test
    fun `a page visitor that does not match is recorded, not hidden`() = runBlocking {
        val minter = FakeMinter().apply { visitorEcho = "someone-else" }
        val provider = WebViewPoTokenProvider(minter)

        provider.gvsToken("v1", "vis")

        assertTrue(PoTokenTrace.lines().any { it.contains("bind=mismatch") })
    }

    @Test
    fun `the gvs cache is bounded`() = runBlocking {
        val minter = FakeMinter()
        val provider = WebViewPoTokenProvider(minter, maxGvsTokens = 2)

        (1..3).forEach { provider.gvsToken("v$it", "vis") }
        assertEquals(3, minter.mints.size)

        provider.gvsToken("v1", "vis")
        assertEquals("the evicted video is minted again", 4, minter.mints.size)
    }

    private class FakeMinter(
        var playerToken: String? = "player",
        var gvsToken: String? = "gvs",
        /** null echoes the visitor the mint was asked for. */
        var visitorEcho: String? = null,
        var delayMs: Long = 0L,
        var failure: Throwable? = null
    ) : PoTokenMinter {

        val mints = mutableListOf<Pair<String, String>>()

        override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens? {
            mints += videoId to visitorData
            if (delayMs > 0L) delay(delayMs)
            failure?.let { throw it }
            if (playerToken == null && gvsToken == null) return null
            return MintedPoTokens(
                playerToken = playerToken,
                gvsToken = gvsToken,
                visitorData = visitorEcho ?: visitorData
            )
        }
    }
}
