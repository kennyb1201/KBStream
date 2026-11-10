package com.kennyb1201.kbstream.data.imdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What IMDb actually answers, pinned before a TV ever sees it.
 *
 * The bodies below are shaped exactly like the live service's (verified against
 * `api.graphql.imdb.com` on 2026-11-10: `data.title.primaryVideos.edges[0].node`
 * with `playbackURLs` of MP4/M3U8 renditions and `DEF_*` definition labels),
 * shortened to the fields this app reads. The failure that matters most is the
 * quiet one: a body with `errors` parsed as "no trailer" would fall the whole
 * feature back to YouTube on every refused request and never say why.
 */
class ImdbTrailerDataTest {

    @Test
    fun `the graphql answer yields the video and its renditions`() {
        val result = ImdbTrailerData.parseApiResponse(graphqlBody())

        val video = (result as? ImdbTrailerData.Result.Found)?.video
        assertEquals("the node's own video id", "vi3877612057", video?.id)
        assertEquals(
            "every rendition is kept - the selector decides what plays",
            listOf("mp4-1080", "mp4-480", "hls-master"),
            video?.encodings?.map { it.url.substringAfterLast('/').substringBefore('?') }
        )
        assertEquals(
            "an MP4 is recognised from its mime type",
            true,
            video?.encodings?.first()?.mp4
        )
        assertEquals("DEF_1080p is 1080", 1080, video?.encodings?.first()?.height)
        assertEquals(
            "the m3u8 is a manifest, not an MP4",
            true,
            video?.encodings?.last()?.hls
        )
        assertEquals(
            "DEF_AUTO names no rendition height",
            0,
            video?.encodings?.last()?.height
        )
    }

    @Test
    fun `a title with no primary video is a definitive miss`() {
        val body = """{"data":{"title":{"primaryVideos":{"edges":[]}}}}"""

        assertEquals(
            "IMDb answered with nothing, which is an answer: the caller may " +
                "remember it as a miss",
            ImdbTrailerData.Result.Missing,
            ImdbTrailerData.parseApiResponse(body)
        )
    }

    @Test
    fun `a graphql errors body is pending, never missing`() {
        val body = """{"errors":[{"message":"Client is not authorised"}]}"""

        assertEquals(
            "a refusal says the request was not answered - caching it as 'no " +
                "trailer' is how the fallback would outlive its cause",
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseApiResponse(body)
        )
        assertTrue("and it is readable as a refusal", ImdbTrailerData.hasErrors(body))
    }

    @Test
    fun `an unreadable body is pending`() {
        assertEquals(
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseApiResponse("<html>not json</html>")
        )
        assertEquals(
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseApiResponse(null)
        )
    }

    @Test
    fun `a node whose id is not an imdb video id is pending`() {
        val body =
            """{"data":{"title":{"primaryVideos":{"edges":[{"node":{"id":"nope",""" +
                """"playbackURLs":[{"url":"https://imdb-video.media-imdb.com/a.mp4",""" +
                """"videoMimeType":"MP4","videoDefinition":"DEF_1080p"}]}}]}}}}"""

        assertEquals(
            "a page can carry an unrelated video object; playing it is worse " +
                "than falling back",
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseApiResponse(body)
        )
    }

    @Test
    fun `the title page probe's answer is found, missing, or pending`() {
        assertEquals(
            ImdbTrailerData.Result.Missing,
            ImdbTrailerData.parseProbeJson("""{"status":"missing"}""")
        )
        assertEquals(
            "and it arrives JSON-encoded from evaluateJavascript",
            ImdbTrailerData.Result.Missing,
            ImdbTrailerData.parseProbeJson("\"{\\\"status\\\":\\\"missing\\\"}\"")
        )
        assertEquals(
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseProbeJson("pending")
        )
        assertEquals(
            ImdbTrailerData.Result.Pending,
            ImdbTrailerData.parseProbeJson(null)
        )

        // One line: the probe's JSON is what the WebView returns, verbatim.
        val probe =
            """{"status":"found","video":{"id":"vi1234567","playbackURLs":[{"url":"https://imdb-video.media-imdb.com/b.mp4","videoMimeType":"MP4","videoDefinition":"DEF_720p"}]}}"""
        val video = (ImdbTrailerData.parseProbeJson(probe) as? ImdbTrailerData.Result.Found)?.video
        assertEquals("vi1234567", video?.id)
        assertEquals(720, video?.encodings?.first()?.height)
        assertEquals(true, video?.encodings?.first()?.mp4)
    }

    @Test
    fun `definition labels name their height`() {
        assertEquals(1080, ImdbTrailerData.heightOf("DEF_1080p"))
        assertEquals(720, ImdbTrailerData.heightOf("DEF_720p"))
        assertEquals(480, ImdbTrailerData.heightOf("DEF_480p"))
        assertEquals(480, ImdbTrailerData.heightOf("DEF_SD"))
        assertEquals(720, ImdbTrailerData.heightOf("DEF_HD"))
        assertEquals(1080, ImdbTrailerData.heightOf("DEF_FHD"))
        assertEquals(2160, ImdbTrailerData.heightOf("DEF_4K"))
        assertEquals(
            "an adaptive manifest's label is not a rendition height",
            0,
            ImdbTrailerData.heightOf("DEF_AUTO")
        )
        assertEquals(
            "an unrecognised label falls back to its digits, never to a guess",
            1440,
            ImdbTrailerData.heightOf("DEF_1440p")
        )
        assertEquals(0, ImdbTrailerData.heightOf(""))
    }

    @Test
    fun `an imdb id is read only from the front of a value`() {
        assertEquals("tt15239678", ImdbTrailerData.imdbIdOf("tt15239678"))
        assertEquals("tt15239678", ImdbTrailerData.imdbIdOf(" tt15239678 "))
        assertEquals(
            "an episode-qualified id still names its title",
            "tt15239678",
            ImdbTrailerData.imdbIdOf("tt15239678:1:2")
        )
        assertNull(
            "a TMDB or add-on id is not an IMDb id",
            ImdbTrailerData.imdbIdOf("tmdb:12345")
        )
        assertNull(ImdbTrailerData.imdbIdOf(null))
    }

    @Test
    fun `the page probe names the expected title and searches for primaryVideos`() {
        val script = ImdbTrailerData.probeScript("tt15239678")

        assertTrue(
            "a page for another title must not answer for this one",
            script.contains("location.pathname.indexOf('/tt15239678')")
        )
        assertTrue(
            "IMDb's Next.js payload nests the node differently between builds",
            script.contains("find(root)")
        )
        assertTrue(
            "the projection is the small one: the bridge carries no page data",
            script.contains("playbackURLs:v.playbackURLs || []")
        )
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun graphqlBody(): String =
        """{"data":{"title":{"primaryVideos":{"edges":[{"node":{"id":"vi3877612057",""" +
            """"playbackURLs":[""" +
            """{"url":"https://imdb-video.media-imdb.com/vi3877612057/mp4-1080?Expires=1",""" +
            """"videoMimeType":"MP4","videoDefinition":"DEF_1080p"},""" +
            """{"url":"https://imdb-video.media-imdb.com/vi3877612057/mp4-480?Expires=1",""" +
            """"videoMimeType":"MP4","videoDefinition":"DEF_480p"},""" +
            """{"url":"https://imdb-video.media-imdb.com/vi3877612057/hls-master?Expires=1",""" +
            """"videoMimeType":"M3U8","videoDefinition":"DEF_AUTO"}]}}]}}}}"""
}
