package com.kennyb1201.kbstream.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The PO-token pre-warm, against a fake warm page.
 *
 * The field capture this exists for (0.9.23, Moto G) is a GVS mint that spent
 * `6027ms` of its 6 s budget on a COLD WebView and produced nothing, so the
 * resolve went tokenless and the chunks hit the mid-play wall. The fix is not a
 * wider budget - the ladder (5 s script < 6 s GVS < 10 s provider) is unchanged -
 * but moving the page load out of the resolve. What has to be pinned is that the
 * warm path is a LAYER: a warmed provider spends what it has, a resolve for a
 * video nobody warmed still mints (on the warm page when one exists), a cold
 * provider behaves byte-identically to before, the kill switch warms nothing, and
 * memory pressure takes the page without taking the tokens.
 */
class PoTokenWarmTest {

    @Before
    fun clearTrace() {
        PoTokenTrace.clear()
    }

    @Test
    fun `a warm-up leaves the player token ready to spend`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")

        assertTrue(
            "the warm line has to be able to say the page is up AND a player token is in hand",
            PoTokenTrace.lines().any {
                it.startsWith("pot.warm: state=ready") && it.contains("playerToken=yes")
            }
        )
        assertEquals("player", provider.playerToken("vis"))
        assertTrue(
            "and the spend says where the token came from",
            PoTokenTrace.lines().any {
                it.startsWith("pot.player: state=cached") && it.contains("source=warm")
            }
        )
        assertEquals("one warm mint, not one per ask", 1, minter.warmCalls)
    }

    @Test
    fun `a warm-up for the trailer's own video means the resolve mints nothing`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")
        assertEquals("gvs", provider.gvsToken("v1", "vis"))

        assertEquals("v1", minter.warmVideo)
        assertTrue(
            "the critical path paid no mint at all - this is the whole point",
            minter.mints.isEmpty()
        )
        assertTrue(
            PoTokenTrace.lines().any {
                it.startsWith("pot.gvs: state=cached") && it.contains("source=warm")
            }
        )
    }

    @Test
    fun `a resolve for another video mints on the warm page`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")
        assertEquals("gvs", provider.gvsToken("v2", "vis"))

        assertEquals("the resolve's mint still runs", listOf("v2"), minter.mints)
        assertEquals(
            "but on the page that is already loaded, not a fresh WebView",
            1,
            minter.pagesOpened
        )
        assertTrue(
            PoTokenTrace.lines().any {
                it.startsWith("pot.gvs: state=minted") && it.contains("source=warm")
            }
        )
    }

    @Test
    fun `a cold provider behaves exactly as before`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter)

        assertEquals("gvs", provider.gvsToken("v1", "vis"))
        assertEquals("player", provider.playerToken("vis"))

        assertEquals("no warm-up was ever asked for", 0, minter.warmCalls)
        assertEquals(
            "the cold path still creates (and destroys) its own page",
            1,
            minter.pagesOpened
        )
        assertTrue(
            "and the trace says this mint was live, with the same state/latency shape",
            PoTokenTrace.lines().any {
                it.startsWith("pot.gvs: state=minted") && it.contains("source=live")
            }
        )
        assertTrue(
            "no pre-warm happened, and the report says so rather than omitting it",
            PoTokenTrace.lines().contains("pot.warm: state=cold")
        )
    }

    @Test
    fun `the kill switch warms nothing and mints nothing`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter, enabled = { false })

        assertFalse("callers check this before paying for a fetch", provider.isEnabled())
        provider.warmUp("vis", "v1")

        assertEquals(0, minter.warmCalls)
        assertEquals(0, minter.pagesOpened)
        assertNull(provider.gvsToken("v1", "vis"))
        assertEquals("off means off, not \"mint and discard\"", 0, minter.mints.size)
    }

    @Test
    fun `memory pressure drops the page, keeps the token, and re-warms once`() = runBlocking {
        val minter = FakeWarmMinter()
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")
        provider.warmUp("vis", "v1")
        assertEquals("a repeated trigger re-navigates the SAME page", 1, minter.pagesOpened)
        assertEquals(2, minter.warmCalls)

        provider.releaseWarm()
        assertEquals(1, minter.pagesClosed)
        assertTrue(
            "the token is a string and survives; only the renderer goes",
            provider.playerToken("vis") != null
        )
        assertTrue(
            "and the report says the page is gone while the token is in hand",
            PoTokenTrace.lines().any {
                it.startsWith("pot.warm: state=cold") && it.contains("playerToken=yes")
            }
        )

        provider.warmUp("vis", "v1")
        assertEquals("the next trigger re-warms", 2, minter.pagesOpened)
    }

    @Test
    fun `a warm-up that produces nothing is reported cold`() = runBlocking {
        val minter = FakeWarmMinter(warmFails = true)
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")

        assertTrue(
            "an empty page must not read as ready - the next capture has to be able " +
                "to say the warm-up never produced a token on this box",
            PoTokenTrace.lines().any {
                it.startsWith("pot.warm: state=cold") && it.contains("playerToken=no")
            }
        )
        assertNull(provider.playerToken("vis"))
    }

    @Test
    fun `a warm mint for another visitor is withheld like any other`() = runBlocking {
        val minter = FakeWarmMinter().apply { visitorEcho = "someone-else" }
        val provider = WebViewPoTokenProvider(minter)

        provider.warmUp("vis", "v1")

        assertTrue(
            "the binding rule is the same on both paths",
            PoTokenTrace.lines().any { it.contains("bind=mismatch") && it.contains("spent=no") }
        )
        assertNull("and nothing was cached from it", provider.playerToken("vis"))
        assertTrue(
            PoTokenTrace.lines().any { it.startsWith("pot.warm: state=cold") }
        )
    }

    @Test
    fun `an unwarmed trace still prints the warm line`() {
        assertEquals(
            "a capture from a device that never warmed must say so, not omit the line",
            true,
            PoTokenTrace.lines().contains("pot.warm: state=cold")
        )
    }

    /**
     * A minter that models the one thing the real one guarantees: at most one page
     * exists, and a mint reuses it ([MintedPoTokens.warm]).
     */
    private class FakeWarmMinter(
        var playerToken: String? = "player",
        var gvsToken: String? = "gvs",
        var warmFails: Boolean = false
    ) : WarmingPoTokenMinter {

        /** null echoes the visitor the mint was asked for. */
        var visitorEcho: String? = null

        var pagesOpened = 0
        var pagesClosed = 0
        var warmCalls = 0
        var warmVideo: String? = null
        val mints = mutableListOf<String>()

        private var page = false

        private fun openPage() {
            if (!page) {
                page = true
                pagesOpened++
            }
        }

        override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens? {
            val warm = page
            openPage()
            mints += videoId
            if (playerToken == null && gvsToken == null) return null
            return tokens(visitorData, warm)
        }

        override suspend fun warm(visitorData: String, videoId: String?): MintedPoTokens? {
            warmCalls++
            warmVideo = videoId
            openPage()
            if (warmFails) return null
            return tokens(visitorData, warm = true)
        }

        override fun releaseWarm() {
            if (page) {
                page = false
                pagesClosed++
            }
        }

        private fun tokens(visitorData: String, warm: Boolean) = MintedPoTokens(
            playerToken = playerToken,
            gvsToken = gvsToken,
            visitorData = visitorEcho ?: visitorData,
            warm = warm
        )
    }
}
