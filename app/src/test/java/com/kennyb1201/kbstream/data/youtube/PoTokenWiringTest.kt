package com.kennyb1201.kbstream.data.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a PO token is spent, and what a request looks like when there is none.
 *
 * Two shapes only: `serviceIntegrityDimensions.poToken` on the InnerTube player
 * request, `pot` on a googlevideo URL. Both are additions - no existing field or
 * query parameter is rewritten - because the token layer's whole safety story is
 * that "no token" means the request is byte-identical to the pre-spec one.
 *
 * Robolectric because the URL rules are read through a real `android.net.Uri`
 * (the unit-test stub returns null from `Uri.parse`, which would make every
 * host check pass for the wrong reason).
 */
@RunWith(AndroidJUnit4::class)
class PoTokenWiringTest {

    @Test
    fun `the player payload carries serviceIntegrityDimensions only with a token`() {
        val without = InnerTubeExtractor.playerRequestPayload(VIDEO_ID, YouTubeClients.webEmbedded, null)
        val with = InnerTubeExtractor.playerRequestPayload(
            VIDEO_ID,
            YouTubeClients.webEmbedded,
            "player-token"
        )

        assertFalse(without.has("serviceIntegrityDimensions"))
        assertEquals(
            "player-token",
            with.getJSONObject("serviceIntegrityDimensions").getString("poToken")
        )
        assertEquals(
            "the token is the ONLY difference in the payload",
            without.keys().asSequence().toSet(),
            with.keys().asSequence().toSet() - "serviceIntegrityDimensions"
        )
        assertEquals(VIDEO_ID, with.getString("videoId"))
        assertEquals(
            "the Spec A context is untouched",
            YouTubeClients.webEmbedded.contextFor(VIDEO_ID).toString(),
            with.getJSONObject("context").toString()
        )
    }

    @Test
    fun `a blank player token is the tokenless payload`() {
        val payload = InnerTubeExtractor.playerRequestPayload(VIDEO_ID, YouTubeClients.android, "   ")

        assertFalse(payload.has("serviceIntegrityDimensions"))
    }

    @Test
    fun `pot is appended once and the signed query is preserved`() {
        val decorated = potDecorated(SIGNED_URL, "gvs-token")

        assertEquals("$SIGNED_URL&pot=gvs-token", decorated)
        assertEquals(
            "a second application must not add a second pot",
            decorated,
            potDecorated(decorated, "gvs-token")
        )
        assertTrue(
            "every existing parameter is still there, byte for byte",
            decorated.startsWith(SIGNED_URL)
        )
    }

    @Test
    fun `a url without a query gets the parameter`() {
        assertEquals(
            "https://rr3---sn-abc.googlevideo.com/videoplayback?pot=tok",
            potDecorated("https://rr3---sn-abc.googlevideo.com/videoplayback", "tok")
        )
    }

    @Test
    fun `only googlevideo urls are decorated`() {
        listOf(
            "https://cdn.realdebrid.example/stream/abc.mkv?x=1",
            "https://googlevideo.com.evil.example/videoplayback?x=1",
            "https://manifest.example/hls/master.m3u8"
        ).forEach { url ->
            assertEquals(url, potDecorated(url, "tok"))
        }
    }

    @Test
    fun `no token means the url is byte-identical`() {
        assertEquals(SIGNED_URL, potDecorated(SIGNED_URL, null))
        assertEquals(SIGNED_URL, potDecorated(SIGNED_URL, ""))
    }

    @Test
    fun `the token is url-encoded`() {
        assertEquals(
            "$SIGNED_URL&pot=a%2Bb%3D",
            potDecorated(SIGNED_URL, "a+b=")
        )
    }

    @Test
    fun `all three source shapes carry the token without losing their UA`() {
        val muxed = PlayableSource.Muxed(SIGNED_URL, userAgent = "ua").withPotToken("tok")
            as PlayableSource.Muxed
        assertEquals("$SIGNED_URL&pot=tok", muxed.url)
        assertEquals("ua", muxed.userAgent)

        val adaptive = PlayableSource.Adaptive(
            videoUrl = "$SIGNED_URL&kind=video",
            audioUrl = "$SIGNED_URL&kind=audio",
            userAgent = "ua"
        ).withPotToken("tok") as PlayableSource.Adaptive
        assertEquals("$SIGNED_URL&kind=video&pot=tok", adaptive.videoUrl)
        assertEquals("$SIGNED_URL&kind=audio&pot=tok", adaptive.audioUrl)
        assertEquals("ua", adaptive.userAgent)
        assertTrue(adaptive.carriesPot())

        assertTrue(muxed.carriesPot())
        assertTrue(
            "applying the token twice changes nothing",
            adaptive == adaptive.withPotToken("tok")
        )
    }

    @Test
    fun `a source without a token is returned untouched`() {
        val muxed = PlayableSource.Muxed(SIGNED_URL, userAgent = "ua")

        assertEquals(muxed, muxed.withPotToken(null))
        assertFalse(muxed.carriesPot())
    }

    @Test
    fun `with minting off the request shapes are today's`() = runBlocking {
        val minter = SilentMinter()
        val provider = WebViewPoTokenProvider(minter, enabled = { false })

        val playerToken = provider.playerToken("vis")
        val gvsToken = provider.gvsToken(VIDEO_ID, "vis")

        assertEquals("the kill switch mints nothing", 0, minter.mints)
        assertFalse(
            InnerTubeExtractor.playerRequestPayload(VIDEO_ID, YouTubeClients.android, playerToken)
                .has("serviceIntegrityDimensions")
        )
        assertEquals(
            SIGNED_URL,
            (PlayableSource.Muxed(SIGNED_URL, userAgent = "ua")
                .withPotToken(gvsToken) as PlayableSource.Muxed).url
        )
    }

    private class SilentMinter : PoTokenMinter {
        var mints = 0
        override suspend fun mint(videoId: String, visitorData: String): MintedPoTokens? {
            mints++
            return MintedPoTokens("player", "gvs", visitorData)
        }
    }

    private companion object {
        /** The 93 s hero trailer from the 2026-10-18 field capture. */
        const val VIDEO_ID = "gc0t8AfMP6k"

        /** A signed googlevideo URL, trimmed but with the signature params intact. */
        const val SIGNED_URL =
            "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1800000000&id=o-abc" +
                "&itag=18&sig=abc%3D&lsig=x&n=zzz"
    }
}
