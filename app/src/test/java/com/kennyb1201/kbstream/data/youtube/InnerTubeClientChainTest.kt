package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolution chain itself: which clients are asked, what they contribute,
 * and what happens when every one of them is refused.
 *
 * The chain used to be android -> ios -> android_vr with no TV client at all,
 * while YouTube's own default chain had moved to tv -> ios -> web: android and
 * web now require a PO token for their formats, and the TV and iOS clients
 * still serve token-free ones. The pool is what matters - every client's
 * formats are merged - so a client that only ever answers LOGIN_REQUIRED adds
 * latency, not width.
 *
 * The all-clients rule keeps its meaning: LOGIN_REQUIRED from all FIVE is the
 * identity being refused, not one unlucky client, and that is what throws the
 * cached config away. Anything less still resolves from the clients that
 * answered.
 */
class InnerTubeClientChainTest {

    @Test
    fun `every client in the table is queried`() {
        val loop = extractorSource.substringAfter("private suspend fun extractInternal(")

        assertTrue(
            "the chain must be driven by the table, so adding a client asks it too",
            loop.contains("for (client in YouTubeClients.all) {")
        )
        assertEquals(5, YouTubeClients.all.size)
    }

    @Test
    fun `the all-login-required rule counts the whole table`() {
        assertTrue(
            extractorSource.contains("if (loginRequiredCount == YouTubeClients.all.size) {")
        )
        assertTrue(
            "and it must say so in the log, because that is the field evidence",
            extractorSource.contains("\"All clients LOGIN_REQUIRED; invalidating config\"")
        )
    }

    @Test
    fun `all clients refused invalidates the config before returning nothing`() {
        val source = extractorSource
        val at = source.indexOf("if (loginRequiredCount == YouTubeClients.all.size) {")
        assertTrue("the rule must exist", at >= 0)

        val invalidateAt = source.indexOf("invalidateConfig()", at)
        val returnAt = source.indexOf("return null", invalidateAt)

        assertTrue("the config must be thrown away", invalidateAt > at)
        assertTrue(
            "the refused identity must not be retried with the same visitor",
            returnAt > invalidateAt
        )
    }

    @Test
    fun `the tv client's formats enter the progressive and adaptive pools`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.tv,
            playerResponse()
        )

        assertEquals("tv", harvest.progressive.single().client)
        assertEquals("tv", harvest.video.single().client)
        assertEquals("tv", harvest.audio.single().client)
        assertEquals(YouTubeClients.tv.priority, harvest.progressive.single().priority)
    }

    @Test
    fun `the embedded client's formats enter the progressive and adaptive pools`() {
        val harvest = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.webEmbedded,
            playerResponse()
        )

        assertEquals("web_embedded", harvest.progressive.single().client)
        assertEquals("web_embedded", harvest.video.single().client)
        assertEquals("web_embedded", harvest.audio.single().client)
        assertEquals(YouTubeClients.webEmbedded.priority, harvest.progressive.single().priority)
    }

    @Test
    fun `a login-required client still leaves the others' pools intact`() {
        // The rule that must not regress: one refused client is not a refusal.
        val refused = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.androidVr,
            JSONObject("""{"playabilityStatus": {"status": "LOGIN_REQUIRED"}}""")
        )
        val answered = InnerTubeExtractor.harvestPlayerResponse(
            YouTubeClients.tv,
            playerResponse()
        )

        assertTrue(refused.loginRequired)
        assertTrue(refused.progressive.isEmpty())
        assertTrue(answered.progressive.isNotEmpty())
    }

    @Test
    fun `the config is minted for the video being resolved, before the chain runs`() {
        val source = extractorSource
        val videoIdAt = source.indexOf("val videoId = extractVideoId(youtubeUrl) ?: return null")
        val configAt = source.indexOf("ensureWatchConfig(videoId, forceRefresh = forceRefreshConfig)")
        val loopAt = source.indexOf("for (client in YouTubeClients.all)")

        assertTrue("the id must be extracted first", videoIdAt >= 0)
        assertTrue(
            "the visitor has to be minted on this video's embed page, which needs the id",
            configAt > videoIdAt
        )
        assertTrue("and before any client is asked", loopAt > configAt)
    }

    @Test
    fun `the progressive parser is handed its owning client`() {
        // The attribution fix, at the call site: no fixed index, no default.
        assertTrue(
            extractorSource.contains("parseFormats(streamingData.optJSONArray(\"formats\"), client)")
        )
    }

    private fun playerResponse(): JSONObject = JSONObject(
        """
        {
          "playabilityStatus": {"status": "OK"},
          "streamingData": {
            "formats": [{
              "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=18",
              "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
              "height": 360, "bitrate": 500000
            }],
            "adaptiveFormats": [
              {
                "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=137",
                "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                "height": 1080, "fps": 24, "bitrate": 4000000
              },
              {
                "url": "https://rr3---sn-abc.googlevideo.com/videoplayback?id=140",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000
              }
            ]
          }
        }
        """.trimIndent()
    )

    /** The extractor's own source, whitespace flattened for the wiring checks. */
    private val extractorSource: String by lazy {
        val relative = "src/main/java/com/kennyb1201/kbstream/data/youtube/InnerTubeExtractor.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return@lazy candidate.readText().replace(Regex("\\s+"), " ")
            }
            dir = dir.parentFile
        }
        throw AssertionError("InnerTubeExtractor.kt not found walking up from the project root")
    }
}
