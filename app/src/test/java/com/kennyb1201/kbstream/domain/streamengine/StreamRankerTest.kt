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

    /** Order for a series request that named this episode. */
    private fun orderFor(
        season: Int,
        episode: Int,
        vararg streams: Stream
    ): List<Stream> = StreamRanker.rank(streams.toList(), season to episode)

    // ── The episode asked for ──
    //
    // Reported bug: "Paw Patrol is still playing the wrong episodes", with a
    // diagnostics report whose session lines all agreed with the id the stream
    // was resolved for. TMDB splits that show's seasons into single 12-minute
    // segments while its releases are named for broadcast half-hours, so the
    // add-on can match no release for the episode asked for and returns
    // something else - which auto-play then started, because the head of this
    // list is what it takes.

    @Test
    fun `the episode asked for heads the list over another episode`() {
        val stranger = stream(
            "Paw Patrol S03E15 2160p REMUX DV HDR 18 GB",
            url = "https://store-9.torbox.app/download/a.mkv"
        )
        val asked = stream("Paw Patrol S03E30 480p WEB-DL", url = "https://host/a.mkv")

        // A debrid-served 4K of the wrong episode still sits under an honest
        // 480p of the right one: content before labels, the same argument the
        // known-bad tier makes.
        assertEquals(listOf(asked, stranger), orderFor(3, 30, stranger, asked))
    }

    @Test
    fun `a source that names no episode outranks one that names another`() {
        val other = stream("Paw Patrol S03E15 1080p WEB-DL 8 GB", url = "https://host/a.mkv")
        val unlabeled = stream("Paw Patrol 1080p WEB-DL", url = "https://host/b.mkv")

        assertEquals(listOf(unlabeled, other), orderFor(3, 30, other, unlabeled))
    }

    @Test
    fun `a movie request is ordered exactly as before`() {
        // No episode in the request, so every source is in the same episode
        // tier and the labels decide - which is the whole ranker as it was.
        val small = stream("Some Film 2024 480p", url = "https://host/a.mkv")
        val big = stream("Some Film 2024 2160p REMUX", url = "https://host/b.mkv")

        assertEquals(listOf(big, small), order(small, big))
        assertEquals(listOf(big, small), StreamRanker.rank(listOf(small, big), null))
    }

    @Test
    fun `explain names the episode the file declares`() {
        val line = StreamRanker.explain(
            stream("Paw Patrol S03E15 1080p WEB-DL", url = "https://host/a.mkv"),
            3 to 30
        )

        assertTrue(line, line.contains("other-episode"))
        assertTrue(line, line.contains("[S03E15 != S03E30]"))
    }

    @Test
    fun `explain reports a source that declares the episode asked for`() {
        val line = StreamRanker.explain(
            stream("Paw Patrol S03E30 1080p WEB-DL", url = "https://host/a.mkv"),
            3 to 30
        )

        assertTrue(line, line.contains("[S03E30]"))
        assertTrue(line, !line.contains("other-episode"))
    }

    @Test
    fun `explain falls back to the description when the add-on sends no title`() {
        // AIOStreams and friends put the release name in `description` and
        // leave `title` empty, so every reported source line read "(no title)"
        // - the one field that answers "which file did it play?".
        val line = StreamRanker.explain(
            Stream(
                name = "AIOStreams",
                description = "Paw.Patrol.S03E15.1080p.WEB-DL.mkv",
                url = "https://host/a.mkv"
            )
        )

        assertTrue(line, line.contains("Paw.Patrol.S03E15"))
        assertTrue(line, !line.contains("(no title)"))
    }

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

        // WEBRip was penalized by the same rule as CAM, which pushed good web
        // releases below unlabeled ones.
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
        val unlabeled = stream("Some Film 2024 480p", url = "https://host/plain.mkv")

        assertEquals(listOf(megabyte, unlabeled), order(unlabeled, megabyte))

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

    // ── Availability: the criterion the sorted addons sort on first ──
    //
    // Reported bug: with AIOStreams (regex + SEL) configured, its own ordering
    // was better than this ranker's. The divergence was availability - a
    // debrid add-on puts the copy it already holds on top, and a re-sort by
    // resolution alone walks a 4K that has to find its swarm back over it.

    @Test
    fun `an uncached line does not collect the cached bonus`() {
        // "Uncached" contains "cached", so a stream that spelled out that it
        // was NOT ready collected the instant bonus for saying so - the claim
        // read backwards, in the branch that decides the head of the list.
        val spelledOut = stream("Some Film 2024 1080p WEB-DL Uncached", url = "https://host/a.mkv")
        val silent = stream("Some Film 2024 1080p WEB-DL", url = "https://host/b.mkv")

        assertEquals(listOf(spelledOut, silent), order(spelledOut, silent))

        // The cached twin still wins, so the word boundary did not disarm the
        // signal it was meant to protect.
        val cached = stream("Some Film 2024 1080p WEB-DL Cached", url = "https://host/c.mkv")
        assertEquals(listOf(cached, silent), order(silent, cached))
    }

    @Test
    fun `a cached copy outranks a higher-resolution uncached one`() {
        val cached = stream("Some Film 2024 1080p WEB-DL cached", url = "https://host/c.mkv")
        val uncached4k = stream("Some Film 2024 2160p REMUX DV HDR", url = "https://host/u4k.mkv")

        // A 4K that has to find peers stalls; the 1080p the debrid service
        // already holds starts now. This is the order AIOStreams returns.
        assertEquals(listOf(cached, uncached4k), order(uncached4k, cached))
    }

    @Test
    fun `the debrid completion marker counts as cached`() {
        val marked = stream("Some Film 2024 1080p WEB-DL [RD+]", url = "https://host/rd.mkv")
        val plain4k = stream("Some Film 2024 2160p REMUX DV HDR", url = "https://host/4k.mkv")

        assertEquals(listOf(marked, plain4k), order(plain4k, marked))
    }

    @Test
    fun `availability cannot lift a cam over an honest release`() {
        // The reason availability is a tier under the known-bad one instead of
        // a bigger bonus: a *cached* CAM would otherwise be handed the top spot
        // it was originally kept out of.
        val cachedCam = stream("Some Film 2024 1080p CAM cached", url = "https://host/cam.mkv")
        val honest = stream("Some Film 2024 480p", url = "https://host/sd.mkv")

        assertEquals(listOf(honest, cachedCam), order(cachedCam, honest))
    }

    // ── Debrid-served: whose link is it, not what does it claim ──
    //
    // Reported rule: a link the viewer's own debrid service serves has to head
    // the list. The picker kept putting a scraper addon's direct hoster links
    // (PenguPlay) above the TorBox copies AIOStreams sent, because who serves
    // the bytes is written nowhere the score looks - and the label comparison
    // then handed the top spot to the bigger resolution label.

    @Test
    fun `a debrid-served link heads a plain hoster link`() {
        val debrid = stream(
            "Some Film 2024 1080p WEB-DL 6 GB",
            url = "https://store-9.torbox.app/download/abc/Some.Film.2024.1080p.mkv"
        )
        val hoster = stream(
            "Some Film 2024 2160p REMUX DV HDR 18 GB",
            url = "https://cdn.pengu.example/Some.Film.2024.2160p.mkv"
        )

        // The hoster link is the bigger, better-labeled file and it still sits
        // second: the debrid link is a completed file on a CDN, the other is one
        // hoster's copy of the same film.
        assertEquals(listOf(debrid, hoster), order(hoster, debrid))
    }

    @Test
    fun `every debrid service's own host counts, not only TorBox`() {
        // One rule covers the account rather than naming the one service this
        // was reported with.
        for (link in listOf(
            "https://real-debrid.com/d/ABC123",
            "https://www.premiumize.me/download/abc123",
            "https://alldebrid.com/f/abc123",
            "https://debrid-link.com/dl/abc123",
            "https://offcloud.com/cloud/download/abc123",
            "https://put.io/v2/files/1/download"
        )) {
            val debrid = stream("Some Film 2024 1080p WEB-DL", url = link)
            val hoster = stream(
                "Some Film 2024 2160p REMUX DV HDR 18 GB",
                url = "https://cdn.pengu.example/4k.mkv"
            )

            assertEquals(
                "expected $link to head the list",
                listOf(debrid, hoster),
                order(hoster, debrid)
            )
        }
    }

    @Test
    fun `the debrid completion tag counts on the addon's own host too`() {
        // Some addons proxy the debrid stream and never expose the service's
        // domain; the tag they print is then the only evidence that the viewer's
        // own account is serving it.
        val tagged = stream(
            "Some Film 2024 1080p WEB-DL [TB+]",
            url = "https://aiostreams.example/proxy/abc.mkv"
        )
        val hoster = stream(
            "Some Film 2024 2160p REMUX DV HDR 18 GB",
            url = "https://cdn.pengu.example/4k.mkv"
        )

        assertEquals(listOf(tagged, hoster), order(hoster, tagged))
    }

    @Test
    fun `a plain hoster link is not debrid-served`() {
        // Same labels, same kind of host: nothing here for the ranker to
        // separate, so the addon's own order survives.
        val first = stream("Some Film 2024 1080p WEB-DL", url = "https://cdn.pengu.example/a.mkv")
        val second = stream("Some Film 2024 1080p WEB-DL", url = "https://cdn.othercdn.example/b.mkv")

        assertEquals(listOf(first, second), order(first, second))
    }

    @Test
    fun `a debrid-served cam still sits under an honest release`() {
        // The debrid tier sits under the known-bad one for a reason: being
        // served by a service the viewer pays for is not a license to hand a CAM
        // the top spot it was kept out of.
        val debridCam = stream(
            "Some Film 2024 1080p CAM",
            url = "https://store-9.torbox.app/download/cam.mkv"
        )
        val honest = stream("Some Film 2024 480p", url = "https://cdn.pengu.example/sd.mkv")

        assertEquals(listOf(honest, debridCam), order(debridCam, honest))
    }

    @Test
    fun `explain names the debrid tier`() {
        val line = StreamRanker.explain(
            stream(
                "Some Film 2024 1080p WEB-DL",
                url = "https://store-9.torbox.app/download/abc/a.mkv"
            )
        )

        assertTrue(line, line.contains("debrid-served"))
    }

    // ── Peers ──

    @Test
    fun `the swarm decides between two otherwise equal uncached copies`() {
        val busy = stream("Some Film 2024 1080p WEB-DL \uD83D\uDC65 240 seeders", url = "https://host/busy.mkv")
        val quiet = stream("Some Film 2024 1080p WEB-DL \uD83D\uDC65 4 seeders", url = "https://host/quiet.mkv")

        assertEquals(listOf(busy, quiet), order(quiet, busy))
    }

    @Test
    fun `the plain seeders wording is read too`() {
        val busy = stream("Some Film 2024 1080p WEB-DL 52 seeders", url = "https://host/busy.mkv")
        val quiet = stream("Some Film 2024 1080p WEB-DL 2 seeders", url = "https://host/quiet.mkv")

        assertEquals(listOf(busy, quiet), order(quiet, busy))

        val labeled = stream("Some Film 2024 720p Seeders: 30", url = "https://host/labeled.mkv")
        val bare = stream("Some Film 2024 720p", url = "https://host/bare.mkv")

        assertEquals(listOf(labeled, bare), order(bare, labeled))
    }

    @Test
    fun `copies the ranker cannot separate keep the addon's order`() {
        // The other half of the contract, and the whole reason the toggle
        // exists: where this ranker has no opinion, a list an addon already
        // sorted (AIOStreams + SEL) is handed back exactly as it arrived.
        val first = stream("Some Film 2024 1080p WEB-DL", url = "https://host/1.mkv")
        val second = stream("Some Film 2024 1080p WEB-DL", url = "https://host/2.mkv")

        assertEquals(listOf(first, second), order(first, second))
    }

    @Test
    fun `a link with no playable scheme and no hash is dropped`() {
        // Nothing here can open a magnet link, so it is not an option at all -
        // not a low-ranked one.
        val magnet = stream("Some Film 2024 2160p REMUX", url = "magnet:?xt=urn:btih:abcdef")

        assertTrue(StreamRanker.rank(listOf(magnet)).isEmpty())
    }

    @Test
    fun `a foreign-dubbed release sits under an English one`() {
        val dubbed = stream(
            "Some Film 2024 1080p WEB-DL Hindi Dubbed 10 GB",
            url = "https://host/dub.mkv"
        )
        val english = stream("Some Film 2024 1080p WEB-DL 3 GB", url = "https://host/en.mkv")

        // Every quality label is present on the dub, so it used to head the
        // list and then play in the wrong language.
        assertEquals(listOf(english, dubbed), order(dubbed, english))
    }

    @Test
    fun `a hardcoded-subtitle copy sits under a clean one`() {
        val hc = stream("Some Film 2024 1080p HC HDRip", url = "https://host/hc.mkv")
        val clean = stream("Some Film 2024 1080p WEB-DL", url = "https://host/clean.mkv")

        // "HC" is subtitles burned into the picture; the release is otherwise a
        // normal 1080p, so nothing used to keep it off the head.
        assertEquals(listOf(clean, hc), order(hc, clean))
    }

    @Test
    fun `a screener or cam variant sits under an honest release`() {
        for (trap in listOf("1080p PreDVD", "1080p DVDScr", "2160p NEWCAM")) {
            val fake = stream("Some Film 2024 $trap", url = "https://host/fake.mkv")
            val real = stream("Some Film 2024 720p", url = "https://host/real.mkv")

            assertEquals(
                "expected the $trap copy to rank below the release",
                listOf(real, fake),
                order(fake, real)
            )
        }
    }

    @Test
    fun `a resolution the file's size cannot support does not head the list`() {
        val fake4k = stream("Some Film 2024 2160p WEB-DL 700 MB", url = "https://host/fake.mkv")
        val real1080p = stream("Some Film 2024 1080p WEB-DL 8 GB", url = "https://host/real.mkv")

        // "4K" on a sub-2GB file is an upscale or a mislabel, not a 4K release.
        assertEquals(listOf(real1080p, fake4k), order(fake4k, real1080p))
    }

    @Test
    fun `an unlabeled size is not treated as a fake`() {
        // Nothing to contradict the label, so the real 4K release keeps its win.
        val uhd = stream("Some Film 2024 2160p REMUX DV", url = "https://host/uhd.mkv")
        val hd = stream("Some Film 2024 1080p WEB-DL", url = "https://host/hd.mkv")

        assertEquals(listOf(uhd, hd), order(hd, uhd))
    }

    @Test
    fun `a cached copy marked for any debrid service outranks a plain 4K link`() {
        // The bracketed tag is spelled with whatever service the viewer
        // configured. Only `[RD+]` and `[TB+]` used to count, so a Premiumize or
        // AllDebrid account got no availability signal at all and its cached
        // copies were ranked on labels alone - which is how a direct 4K link the
        // box cannot buffer kept heading the list over the cached copy the
        // viewer's own add-on had deliberately put first.
        val cached = stream(
            "Some Film 2024 1080p WEB-DL [PM+]",
            url = "https://debrid.example/download/abc"
        )
        val plain4k = stream("Some Film 2024 2160p REMUX DV HDR", url = "https://host/plain.mkv")

        assertEquals(listOf(cached, plain4k), order(plain4k, cached))
    }

    @Test
    fun `an unbracketed tag other than rd or tb is not a cache claim`() {
        // Only the two well-known bare spellings count: inside a release name a
        // bare "xx+" says nothing about availability, and treating every one of
        // them as a cache claim would hand the top spot to whichever file
        // happened to carry the noisiest name. Equal tiers and equal scores, so
        // the add-on's own order survives - which is the ranker's contract.
        val noisy = stream("Some Film 2024 1080p WEB-DL XV+", url = "https://host/a.mkv")
        val quiet = stream("Some Film 2024 1080p WEB-DL", url = "https://host/b.mkv")

        assertEquals(listOf(noisy, quiet), order(noisy, quiet))
    }

    @Test
    fun `explain names the tiers and the score`() {
        val cached = stream(
            "Some Film 2024 1080p WEB-DL [AD+] cached",
            url = "https://debrid.example/download/xyz"
        )
        val line = StreamRanker.explain(cached)

        assertTrue(line, line.startsWith("playable"))
        assertTrue(line, line.contains("instant"))
        assertTrue(line, line.contains("score="))
        assertTrue(line, line.contains("Some Film 2024 1080p WEB-DL"))
    }

    @Test
    fun `explain reports an entry this app cannot open at all`() {
        val hashOnly = stream("Some Film 2024 2160p REMUX", infoHash = "0f1e2d3c4b5a")
        val line = StreamRanker.explain(hashOnly)

        assertTrue(line, line.startsWith("hash-only"))
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

    // ── The device ──
    //
    // Reported: auto-play "will always pick the 40 Mbps remux on your 1.7 GB
    // TCL". The 4K bonus was unconditional, so a file the box cannot decode
    // outranked the 1080p it could.

    private fun orderConstrained(vararg streams: Stream): List<Stream> =
        StreamRanker.rank(streams.toList(), constrainedDevice = true)

    @Test
    fun `a capable device still prefers the 4K remux`() {
        val uhd = stream("Some Film 2024 2160p REMUX DV 40 GB", url = "https://host/4k.mkv")
        val hd = stream("Some Film 2024 1080p WEB-DL 6 GB", url = "https://host/1080.mkv")

        assertEquals(listOf(uhd, hd), order(uhd, hd))
    }

    @Test
    fun `a constrained device prefers the 1080p over a heavy 4K remux`() {
        val uhd = stream("Some Film 2024 2160p REMUX DV 40 GB", url = "https://host/4k.mkv")
        val hd = stream("Some Film 2024 1080p WEB-DL 6 GB", url = "https://host/1080.mkv")

        assertEquals(listOf(hd, uhd), orderConstrained(uhd, hd))
    }

    @Test
    fun `a constrained device still prefers 4K over 720p`() {
        // The 4K bonus drops below 1080p but stays above 720p: it is demoted,
        // not vetoed.
        val uhd = stream("Some Film 2024 2160p WEB-DL 8 GB", url = "https://host/4k.mkv")
        val hd = stream("Some Film 2024 720p HDTV 1 GB", url = "https://host/720.mkv")

        assertEquals(listOf(uhd, hd), orderConstrained(uhd, hd))
    }
}
