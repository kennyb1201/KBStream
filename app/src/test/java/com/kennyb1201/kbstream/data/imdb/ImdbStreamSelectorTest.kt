package com.kennyb1201.kbstream.data.imdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which IMDb rendition plays, and what makes IMDb "nothing suitable".
 *
 * The rule is deliberately boring: an MP4 whenever one is at 720p or better,
 * because a single progressive file is the whole reason this source exists;
 * a manifest only when it is taller than that MP4. Everything below 720p hands
 * the trailer back to YouTube, which is the fallback the setting promises.
 */
class ImdbStreamSelectorTest {

    @Test
    fun `a 1080p MP4 wins without a manifest fetch`() {
        val video = video(mp4(1080), mp4(480), hls())

        val pick = pickFor(video, manifests = emptyMap())

        assertEquals(
            "an MP4 at the ceiling is the answer; opening a master to prove it " +
                "would be a request for nothing",
            ImdbStreamSelector.Pick.Progressive("mp4-1080", 1080),
            pick
        )
    }

    @Test
    fun `a 720p MP4 is beaten by a 1080p master`() {
        val video = video(mp4(720), hls())

        val pick = pickFor(video, manifests = mapOf("hls-master" to 1080))

        assertEquals(
            "the master is taller and still at or above the floor, so it plays",
            ImdbStreamSelector.Pick.Manifest("hls-master", 1080),
            pick
        )
    }

    @Test
    fun `a 720p MP4 stands when the master is no taller`() {
        val video = video(mp4(720), hls())

        val pick = pickFor(video, manifests = mapOf("hls-master" to 720))

        assertEquals(
            "equal resolution keeps the progressive file: no segments to walk",
            ImdbStreamSelector.Pick.Progressive("mp4-720", 720),
            pick
        )
    }

    @Test
    fun `every MP4 below the floor hands the trailer to YouTube`() {
        val video = video(mp4(480), mp4(360))

        assertNull(
            "the field's own case: an SD trailer must not be what this source " +
                "plays instead of YouTube's",
            pickFor(video, manifests = emptyMap())
        )
        assertNull(ImdbStreamSelector.progressive(video))
    }

    @Test
    fun `a title with no MP4 at all plays a tall master`() {
        val video = video(hls())

        assertEquals(
            "an HLS master at 720p or better is still one URL to the player",
            ImdbStreamSelector.Pick.Manifest("hls-master", 720),
            pickFor(video, manifests = mapOf("hls-master" to 720))
        )
    }

    @Test
    fun `the same title over a short MP4 falls back to a tall master`() {
        val video = video(mp4(480), hls())

        assertEquals(
            "the MP4 is below the floor, so the master is the only suitable source",
            ImdbStreamSelector.Pick.Manifest("hls-master", 1080),
            pickFor(video, manifests = mapOf("hls-master" to 1080))
        )
    }

    @Test
    fun `below the floor everywhere is nothing suitable`() {
        val video = video(mp4(480), hls())

        assertNull(
            "a 480p master is not better than YouTube's own rendition",
            pickFor(video, manifests = mapOf("hls-master" to 480))
        )
    }

    @Test
    fun `a master that did not parse is not a candidate`() {
        val video = video(mp4(720), hls())

        assertEquals(
            "an unreadable master has no evidence of being watchable",
            ImdbStreamSelector.Pick.Progressive("mp4-720", 720),
            pickFor(video, manifests = emptyMap())
        )
    }

    @Test
    fun `the highest in-ceiling MP4 wins when a 4K one is also listed`() {
        val video = video(mp4(2160), mp4(1080), mp4(720))

        assertEquals(
            "the TV ceiling is the decoder's, not the title's: 1080p is the pick",
            "mp4-1080",
            ImdbStreamSelector.progressive(video)?.url
        )
    }

    @Test
    fun `with only above-ceiling MP4s the lowest of them plays`() {
        val video = video(mp4(2160), mp4(1440))

        assertEquals(
            "refusing to play is never the answer, and the least tall 4K-class " +
                "file is the one to try",
            "mp4-1440",
            ImdbStreamSelector.progressive(video)?.url
        )
    }

    @Test
    fun `a master's advertised resolution is read from its playlist`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1071000,RESOLUTION=640x360
            a.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3348000,RESOLUTION=1280x720,CODECS="avc1.64001f"
            b.m3u8
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=5364000,RESOLUTION=1920x1080
            c.m3u8
        """.trimIndent()

        assertEquals(
            "the tallest variant is what the master can serve",
            1080,
            ImdbStreamSelector.highestManifestHeight(master)
        )
        assertEquals(
            "a body that is not a master playlist has no height to claim",
            0,
            ImdbStreamSelector.highestManifestHeight("<html>error</html>")
        )
        assertEquals(0, ImdbStreamSelector.highestManifestHeight(null))
    }

    @Test
    fun `the floor and the ceiling are the app's trailer numbers`() {
        assertEquals(720, ImdbStreamSelector.FLOOR_HEIGHT)
        assertEquals(1080, ImdbStreamSelector.CEILING_HEIGHT)
        assertTrue(ImdbStreamSelector.MAX_MANIFESTS in 1..3)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun pickFor(
        video: ImdbTrailerData.Video,
        manifests: Map<String, Int>
    ): ImdbStreamSelector.Pick? =
        ImdbStreamSelector.pick(
            mp4 = ImdbStreamSelector.progressive(video),
            title = video,
            manifestHeights = manifests
        )

    private fun mp4(height: Int): ImdbTrailerData.Encoding =
        ImdbTrailerData.Encoding(url = "mp4-$height", height = height, mp4 = true)

    private fun hls(): ImdbTrailerData.Encoding =
        ImdbTrailerData.Encoding(url = "hls-master", height = 0, mp4 = false, hls = true)

    private fun video(vararg encodings: ImdbTrailerData.Encoding): ImdbTrailerData.Video =
        ImdbTrailerData.Video(id = "vi1234567", encodings = encodings.toList())
}
