package com.kennyb1201.kbstream.data.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which client a format belongs to.
 *
 * The defect this pins: `parseFormats()` attributed the progressive formats of
 * EVERY client's player response to `CLIENTS[0]` - android - so an iOS- or
 * VR-signed muxed URL was probed and then played under the Android UA.
 * googlevideo binds a signed URL to the UA that asked for it, so that is a
 * guaranteed 403 on a stream that would have played (the chunked source's UA
 * cycling masked it by eventually guessing the right UA, but it wasted the
 * first attempts and the diagnostics all pointed at android).
 *
 * The pools here are built from the same fixtures a real response would carry,
 * one per client, so a regression that goes back to a fixed client is caught by
 * the client key and the priority, not just by the URL.
 */
class InnerTubeFormatAttributionTest {

    @Test
    fun `progressive formats carry the client whose response carried them`() {
        listOf(
            YouTubeClients.android,
            YouTubeClients.tv,
            YouTubeClients.ios,
            YouTubeClients.webEmbedded,
            YouTubeClients.androidVr
        ).forEach { client ->
            val harvest = InnerTubeExtractor.harvestPlayerResponse(client, response())

            assertEquals(client.key, 1, harvest.progressive.size)
            assertEquals(client.key, client.key, harvest.progressive.single().client)
            assertEquals(client.key, client.priority, harvest.progressive.single().priority)
        }
    }

    @Test
    fun `an ios response is not attributed to android`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(YouTubeClients.ios, response())

        assertFalse(harvest.progressive.any { it.client == "android" })
        assertFalse(
            "the adaptive pools are attributed too",
            harvest.video.any { it.client == "android" } || harvest.audio.any { it.client == "android" }
        )
        assertTrue(harvest.progressive.all { it.client == "ios" })
    }

    @Test
    fun `adaptive video and audio carry their own client as well`() {
        listOf(YouTubeClients.tv, YouTubeClients.webEmbedded).forEach { client ->
            val harvest = InnerTubeExtractor.harvestPlayerResponse(client, response())

            assertEquals(client.key, 1, harvest.video.size)
            assertEquals(client.key, 1, harvest.audio.size)
            assertEquals(client.key, client.key, harvest.video.single().client)
            assertEquals(client.key, client.key, harvest.audio.single().client)
            assertEquals(client.key, client.priority, harvest.video.single().priority)
        }
    }

    @Test
    fun `the hls manifest is tagged for the client that received it`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(YouTubeClients.ios, response())

        assertEquals(HLS_URL, harvest.hlsUrl)
    }

    @Test
    fun `a login-required response contributes no formats`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.androidVr,
            JSONObject("""{"playabilityStatus": {"status": "LOGIN_REQUIRED"}}""")
        )

        assertTrue(harvest.loginRequired)
        assertNull(harvest.hlsUrl)
        assertTrue(harvest.progressive.isEmpty())
        assertTrue(harvest.video.isEmpty())
        assertTrue(harvest.audio.isEmpty())
    }

    @Test
    fun `a genuine error status contributes no formats`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.android,
            JSONObject(
                """
                {
                  "playabilityStatus": {"status": "UNPLAYABLE"},
                  "streamingData": {"formats": [$PROGRESSIVE_FORMAT]}
                }
                """.trimIndent()
            )
        )

        assertFalse(harvest.loginRequired)
        assertFalse(harvest.playable)
        assertTrue(harvest.progressive.isEmpty())
    }

    @Test
    fun `a format without a url is skipped rather than misattributed`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.tv,
            JSONObject(
                """
                {
                  "playabilityStatus": {"status": "OK"},
                  "streamingData": {"formats": [{"mimeType": "video/mp4", "height": 360}]}
                }
                """.trimIndent()
            )
        )

        assertTrue(harvest.playable)
        assertTrue(harvest.progressive.isEmpty())
    }

    /**
     * The shape every client answers with: one muxed progressive format, one
     * adaptive video and one adaptive audio format, plus an HLS manifest. The
     * URLs differ so a mixed-up attribution shows up as a mixed-up URL too.
     */
    private fun response(): JSONObject = JSONObject(
        """
        {
          "playabilityStatus": {"status": "OK"},
          "streamingData": {
            "expiresInSeconds": "21540",
            "hlsManifestUrl": "$HLS_URL",
            "formats": [$PROGRESSIVE_FORMAT],
            "adaptiveFormats": [$ADAPTIVE_VIDEO, $ADAPTIVE_AUDIO]
          }
        }
        """.trimIndent()
    )

    private companion object {
        const val HLS_URL =
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/gc0t8AfMP6k"

        const val PROGRESSIVE_FORMAT =
            """{"itag": 18, "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=18",
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
                "qualityLabel": "360p", "height": 360, "bitrate": 500000}"""

        const val ADAPTIVE_VIDEO =
            """{"itag": 137, "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=137",
                "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                "qualityLabel": "1080p", "height": 1080, "fps": 24, "bitrate": 4000000}"""

        const val ADAPTIVE_AUDIO =
            """{"itag": 140, "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=140",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000,
                "audioSampleRate": "44100"}"""
    }
}
