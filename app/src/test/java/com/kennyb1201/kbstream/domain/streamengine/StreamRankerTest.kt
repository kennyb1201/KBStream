package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering behind the source picker and behind auto-play, which takes the
 * head of this list.
 *
 * Reported bug: the ranker "always has a problematic stream as number one" -
 * the stream that then started by itself and stalled, buffered, or played a CAM
 * capture with an impressive resolution label. Each test below pins one of the
 * ways that used to happen.
 */
class StreamRankerTest {

    private fun stream(
        title: String?,
        url: String? = null,
        name: String? = null,
        infoHash: String? = null,
        videoSize: Long? = null
    ) = Stream(
        name = name,
        title = title,
        url = url,
        infoHash = infoHash,
        behaviorHints = videoSize?.let { StreamBehaviorHints(videoSize = it) }
    )

    /** Order of the whole list, so a tie is caught as well as a head. */
    private fun order(vararg streams: Stream): List<Stream> = StreamRanker.rank(streams.toList())

    @Test
    fun `a high-resolution CAM never heads the list`() {
        val cam = stream("Some Film 2024 2160p CAM x264", url = "https://host/cam.mkv")
        val web = stream("Some Film 2024 1080p WEB-DL", url = "https://host/web.mkv")

        // The 2160p label used to be worth 200 points against the CAM penalty
        // of 300, so a "4K CAM" could outrank a real 1080p web release.
        assertEquals(listOf(web, cam), order(cam, web))
    }

    @Test
    fun `a webrip is an ordinary source, not a cam`() {
        val webrip = stream("Some Film 2024 1080p WEBRip x265", url = "https://host/webrip.mkv")
        val cam = stream("Some Film 2024 1080p CAM", url = "https://host/cam.mkv")

        // WEBRip was penalised by the same rule as CAM, which pushed good web
        // releases below unlabelled ones.
        assertEquals(listOf(webrip, cam), order(cam, webrip))
    }

    @Test
    fun `a 3D pair sits under a plain release`() {
        val sbs = stream("Some Film 2024 1080p HSBS 3D", url = "https://host/3d.mkv")
        val plain = stream("Some Film 2024 1080p", url = "https://host/plain.mkv")

        // A doubled, squashed picture on a TV without a 3D mode.
        assertEquals(listOf(plain, sbs), order(sbs, plain))
    }

    @Test
    fun `a sample or a trailer clip sits under an honest release`() {
        for (trap in listOf("1080p SAMPLE", "Official Trailer", "1080p Teaser")) {
            val clip = stream("Some Film 2024 $trap", url = "https://host/clip.mkv")
            val real = stream("Some Film 2024 720p", url = "https://host/real.mkv")

            assertEquals(
                "expected the $trap clip to rank below the release",
                listOf(real, clip),
                order(clip, real)
            )
        }
    }

    @Test
    fun `quality that lives only in the link still counts`() {
        // A direct-link addon: short title, and the release name only in the
        // path - exactly where the badge packs look, and where the ranker used
        // to look nowhere.
        val linked = stream(
            "Some Film (2024)",
            url = "https://host/Movies/Some%20Film%20(2024)%201080p%20BluRay.mkv"
        )
        val declared = stream("Some Film 2024 720p", url = "https://host/720.mkv")

        assertEquals(listOf(linked, declared), order(declared, linked))
    }

    @Test
    fun `a dvdrip does not read as Dolby Vision`() {
        // "dv" was matched as a substring, so DVDRip collected the Dolby Vision
        // bonus (+30) over a release that declared nothing.
        val plain = stream("Some Film 1999", url = "https://host/a.mkv")
        val dvdrip = stream("Some Film 1999 DVDRip XviD", url = "https://host/b.mkv")

        // Equal scores, so the stable sort must leave the addon's order alone.
        assertEquals(listOf(plain, dvdrip), order(plain, dvdrip))
    }

    @Test
    fun `a real Dolby Vision label does count`() {
        val dv = stream("Some Film 2024 1080p WEB-DL DV", url = "https://host/dv.mkv")
        val sdr = stream("Some Film 2024 1080p WEB-DL", url = "https://host/sdr.mkv")

        assertEquals(listOf(dv, sdr), order(sdr, dv))
    }

    @Test
    fun `an unplayable torrent can never outrank a playable link`() {
        // This app has no torrent engine, so an infoHash-only entry cannot play
        // at all. Playability is a tier of its own, not a score bonus.
        val torrent = stream(
            "Some Film 2024 2160p REMUX DV HDR",
            infoHash = "8f3c1d2e4b5a69788796a5b4c3d2e1f0"
        )
        val playable = stream("Some Film 2024 720p", url = "https://host/720.mkv")

        assertEquals(listOf(playable, torrent), order(torrent, playable))
    }

    @Test
    fun `even a CAM outranks an unplayable torrent`() {
        // The strongest form of the rule: "can this app open it" beats every
        // quality question, including the CAM penalty.
        val cam = stream("Some Film 2024 2160p CAM", url = "https://host/cam.mkv")
        val torrent = stream(
            "Some Film 2024 2160p REMUX",
            infoHash = "8f3c1d2e4b5a69788796a5b4c3d2e1f0"
        )

        assertEquals(listOf(cam, torrent), order(torrent, cam))
    }

    @Test
    fun `the bigger release wins inside one quality tier`() {
        val big = stream("Some Film 2024 1080p WEB-DL 15.4 GB", url = "https://host/big.mkv")
        val small = stream("Some Film 2024 1080p WEB-DL 1.4 GB", url = "https://host/small.mkv")

        assertEquals(listOf(big, small), order(small, big))
    }

    @Test
    fun `the server's own size hint beats a size written in the name`() {
        val hinted = stream(
            "Some Film 2024 1080p WEB-DL",
            url = "https://host/hinted.mkv",
            videoSize = 12L * 1024 * 1024 * 1024
        )
        val smallish = stream("Some Film 2024 1080p WEB-DL 1 GB", url = "https://host/name.mkv")

        assertEquals(listOf(hinted, smallish), order(smallish, hinted))
    }

    @Test
    fun `a megabyte or terabyte release is measured, not ignored`() {
        // Only "N gb" used to match, so anything listed in MB or TB scored as
        // if it declared no size at all.
        val megabyte = stream("Some Film 2024 480p 700 MB", url = "https://host/sd.mkv")
        val unlabelled = stream("Some Film 2024 480p", url = "https://host/plain.mkv")

        assertEquals(listOf(megabyte, unlabelled), order(unlabelled, megabyte))

        val terabyte = stream("Some Film 2024 2160p REMUX 1.2 TB", url = "https://host/huge.mkv")
        val gigabytes = stream("Some Film 2024 2160p REMUX 8 GB", url = "https://host/big.mkv")

        assertEquals(listOf(terabyte, gigabytes), order(gigabytes, terabyte))
    }

    @Test
    fun `an instant source wins the tie`() {
        val cached = stream("Some Film 2024 1080p WEB-DL \u26a1 cached", url = "https://host/c.mkv")
        val uncached = stream("Some Film 2024 1080p WEB-DL", url = "https://host/u.mkv")

        assertEquals(listOf(cached, uncached), order(uncached, cached))
    }

    @Test
    fun `a link with no playable scheme and no hash is dropped`() {
        // Nothing here can open a magnet link, so it is not an option at all -
        // not a low-ranked one.
        val magnet = stream("Some Film 2024 2160p REMUX", url = "magnet:?xt=urn:btih:abcdef")

        assertTrue(StreamRanker.rank(listOf(magnet)).isEmpty())
    }

    @Test
    fun `the head of the list is the stream auto-play starts`() {
        // The picker and auto-play both take the head, which is why the tests
        // above are written as whole-list assertions.
        val streams = listOf(
            stream("Some Film 2024 1080p CAM", url = "https://host/cam.mkv"),
            stream("Some Film 2024 1080p WEB-DL 8 GB", url = "https://host/web.mkv"),
            stream("Some Film 2024 720p HDTV", url = "https://host/hdtv.mkv")
        )

        assertEquals("https://host/web.mkv", StreamRanker.rank(streams).first().url)
    }
}
