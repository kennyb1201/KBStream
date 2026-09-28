package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a source is the episode that was asked for.
 *
 * Reported bug: "Paw Patrol is still playing the wrong episodes", with a
 * diagnostics report in which every session line agreed with the id its stream
 * was resolved for. The identity plumbing was fine; the *file* the add-on
 * handed back for that id was another episode, and auto-play started the head
 * of the list anyway. These tests pin the reading that stops that — and, just
 * as importantly, the cases it must stay silent about, because demoting the
 * everyday unlabeled release would break auto-play everywhere else.
 */
class EpisodeMatchTest {

    private fun stream(
        title: String? = null,
        description: String? = null,
        name: String? = null,
        url: String? = null
    ) = Stream(name = name, title = title, description = description, url = url)

    // ── reading the source's own text ────────────────────────────────────────

    @Test
    fun `an SxxExx in the release name is read`() {
        assertEquals(
            EpisodeMatch.Declared(3, 15),
            EpisodeMatch.declared(stream(title = "Paw Patrol S03E15 1080p WEB-DL"))
        )
    }

    @Test
    fun `the spellings add-ons actually emit are all read`() {
        val declared = EpisodeMatch.Declared(3, 15)
        for (name in listOf(
            "Paw Patrol S03E15 1080p",
            "Paw.Patrol.S03.E15.1080p.WEB-DL",
            "Paw Patrol s03e15",
            "Paw Patrol S03 - E15 1080p",
            "Paw Patrol 3x15 1080p",
            "Paw Patrol Season 3 Episode 15",
            "Paw Patrol Season 3 Ep. 15 1080p"
        )) {
            assertEquals("expected $name to name S03E15", declared, EpisodeMatch.declared(stream(title = name)))
        }
    }

    @Test
    fun `a resolution is not a season and an episode`() {
        // "4K x265 1920x1080 DD5.1" is where a careless pattern reads a season
        // and an episode out of a codec line.
        for (name in listOf(
            "Some Film 2024 2160p REMUX x265 DD5.1",
            "Some Film 2024 1080p 1920x1080",
            "Some Film 2024 1080p HEVC 10bit",
            "Some Film 2024"
        )) {
            assertNull(name, EpisodeMatch.declared(stream(title = name)))
        }
    }

    @Test
    fun `a season with no episode names no episode`() {
        // The ordinary season-pack file: it says which season it is, not which
        // episode, and that is unknown rather than wrong.
        val pack = stream(title = "Paw Patrol S03 1080p WEB-DL")
        assertNull(EpisodeMatch.declared(pack))
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(pack, 3, 30))
    }

    @Test
    fun `the filename in the link is read when nothing else names the episode`() {
        // A direct-link add-on: a badge for a name, and the release name only
        // in the path - percent-encoded, as these links are.
        val linked = stream(
            title = "AIOStreams",
            url = "https://cdn.example/Paw%20Patrol%20S03E15%201080p%20WEB-DL.mkv?token=abc"
        )

        assertEquals(EpisodeMatch.Declared(3, 15), EpisodeMatch.declared(linked))
    }

    @Test
    fun `an absolutely numbered episode is not read as one`() {
        // Releases number a long-running show's episodes absolutely ("S01E1100"
        // for what the metadata calls S22E17). Reading a truncated 110 out of
        // that would invent a mismatch, so nothing is read at all.
        val absolute = stream(title = "One Piece S01E1100 1080p")
        assertNull(EpisodeMatch.declared(absolute))
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(absolute, 22, 17))
    }

    @Test
    fun `a bare episode number names no season`() {
        val bare = stream(title = "Paw Patrol E15 1080p")
        assertEquals(EpisodeMatch.Declared(null, 15), EpisodeMatch.declared(bare))

        // It can confirm the episode it names and it can say nothing; it can
        // never contradict a request, because it never says which season it is.
        assertEquals(EpisodeMatch.Verdict.MATCHES, EpisodeMatch.verdict(bare, 3, 15))
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(bare, 3, 16))
    }

    @Test
    fun `the first field that names an episode is the one that counts`() {
        // The picker shows the title above the description, and the two
        // disagreeing is itself the interesting case: the file's own name wins.
        val file = stream(
            title = "Paw Patrol S03E15 1080p",
            description = "Paw Patrol S03E16 720p"
        )
        assertEquals(EpisodeMatch.Declared(3, 15), EpisodeMatch.declared(file))
    }

    // ── the verdict ─────────────────────────────────────────────────────────

    @Test
    fun `the episode asked for matches`() {
        val right = stream(title = "Paw Patrol S03E30 1080p WEB-DL 0.9 GB")
        assertEquals(EpisodeMatch.Verdict.MATCHES, EpisodeMatch.verdict(right, 3, 30))
    }

    @Test
    fun `another episode of the same season is a contradiction`() {
        // The Paw Patrol case: releases of season 3 are named for broadcast
        // half-hours, so the file holding segment 30 is named episode 15.
        val other = stream(title = "Paw Patrol S03E15 1080p WEB-DL")
        assertEquals(EpisodeMatch.Verdict.DIFFERENT, EpisodeMatch.verdict(other, 3, 30))
    }

    @Test
    fun `another season is not a contradiction`() {
        val otherSeason = stream(title = "Some Show S01E15 1080p")
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(otherSeason, 3, 15))
    }

    @Test
    fun `a source that names nothing is not a contradiction`() {
        val unlabeled = stream(title = "Paw Patrol 1080p WEB-DL 8 GB")
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(unlabeled, 3, 30))
    }

    // ── what auto-play starts ───────────────────────────────────────────────

    @Test
    fun `the episode asked for is played over a better-labeled stranger`() {
        // The whole point of the tier: auto-play takes the 480p file that says
        // it is S03E30 over the 4K one that says it is S03E15.
        val stranger = stream(
            title = "Paw Patrol S03E15 2160p REMUX DV HDR 18 GB cached",
            url = "https://store-9.torbox.app/download/a.mkv"
        )
        val asked = stream(
            title = "Paw Patrol S03E30 480p WEB-DL",
            url = "https://host/a.mkv"
        )

        val picked = EpisodeMatch.autoplayPick(listOf(stranger, asked), 3, 30)
        assertEquals(asked, picked)
    }

    @Test
    fun `a source that names nothing still plays when nothing names the episode`() {
        // An unlabeled release may well be the episode; refusing it would stop
        // auto-play for every show whose releases carry no episode numbers.
        val stranger = stream(title = "Paw Patrol S03E15 1080p", url = "https://host/a.mkv")
        val quiet = stream(title = "Paw Patrol 1080p WEB-DL", url = "https://host/b.mkv")

        val picked = EpisodeMatch.autoplayPick(listOf(stranger, quiet), 3, 30)
        assertEquals(quiet, picked)
    }

    @Test
    fun `nothing plays when every source is another episode`() {
        val sources = listOf(
            stream(title = "Paw Patrol S03E15 1080p", url = "https://host/a.mkv"),
            stream(title = "Paw Patrol S03E16 1080p", url = "https://host/b.mkv")
        )

        assertNull(EpisodeMatch.autoplayPick(sources, 3, 30))
        assertTrue(EpisodeMatch.onlyOtherEpisodes(sources, 3, 30))
    }

    @Test
    fun `a movie request keeps the first playable source`() {
        val first = stream(title = "Some Film 2024 1080p", url = "https://host/a.mkv")
        val second = stream(title = "Some Film 2024 720p", url = "https://host/b.mkv")

        assertEquals(first, EpisodeMatch.autoplayPick(listOf(first, second), null, null))
        assertFalse(EpisodeMatch.onlyOtherEpisodes(listOf(first, second), null, null))
    }

    @Test
    fun `a source this app cannot open is never the pick`() {
        val torrent = stream(title = "Paw Patrol S03E30 2160p REMUX")
        val playable = stream(title = "Paw Patrol S03E30 480p", url = "https://host/a.mkv")

        assertEquals(playable, EpisodeMatch.autoplayPick(listOf(torrent, playable), 3, 30))
    }

    // ── season packs ────────────────────────────────────────────────────────

    @Test
    fun `a season pack does not play in place of the episode that was asked for`() {
        // The reported capture, to scale: sources for S03E35 whose head is a
        // 13.48 GB usenet file at 0% availability, the only source naming an
        // episode naming S03E34, and the episode itself 12 minutes long. The
        // pack declares no episode at all, so it read as "says nothing" - the
        // tier auto-play falls back to - and a season plays from its own first
        // episode.
        val pack = stream(
            title = "Paw Patrol S03 COMPLETE 1080p WEB-DL",
            description = "13.48 GB | 3 Mbps | usenet | NZBGeek",
            url = "https://host/pack.nzb"
        )
        val previous = stream(
            title = "Paw Patrol S03E34 1080p WEB-DL",
            description = "716.38 MB | 8 Mbps | EN | debrid",
            url = "https://store-9.torbox.app/download/e34.mkv"
        )

        assertNull(EpisodeMatch.autoplayPick(listOf(pack, previous), 3, 35, 12))
    }

    @Test
    fun `a pack whose size cannot fit the episode is refused on its own`() {
        val pack = stream(
            title = "Paw Patrol S03 COMPLETE 1080p",
            description = "13.48 GB",
            url = "https://host/pack.nzb"
        )

        assertTrue(EpisodeMatch.isSeasonPack(pack, runtimeMinutes = 12))
        assertNull(EpisodeMatch.autoplayPick(listOf(pack), 3, 35, 12))
    }

    @Test
    fun `an episode-sized file whose name omits the number still plays`() {
        // The size read against the length is a fact about the file, and it says
        // one episode: a name that happens to be spelled for the season is not
        // enough on its own to refuse it.
        val quiet = stream(
            title = "Paw Patrol S03 1080p WEB-DL",
            description = "716 MB",
            url = "https://host/a.mkv"
        )

        assertFalse(EpisodeMatch.isSeasonPack(quiet, runtimeMinutes = 12))
        assertEquals(quiet, EpisodeMatch.autoplayPick(listOf(quiet), 3, 35, 12))
    }

    @Test
    fun `with no length to read a size against, the name decides`() {
        val pack = stream(
            title = "Paw Patrol S03 Complete 1080p WEB-DL",
            url = "https://host/pack.nzb"
        )
        val quiet = stream(title = "Paw Patrol 1080p WEB-DL", url = "https://host/a.mkv")

        assertTrue(EpisodeMatch.isSeasonPack(pack, runtimeMinutes = null))
        assertNull(EpisodeMatch.autoplayPick(listOf(pack), 3, 35))
        assertEquals(quiet, EpisodeMatch.autoplayPick(listOf(quiet), 3, 35))
    }

    @Test
    fun `a source that names the episode is played however large it is`() {
        // It says it is the episode, so the size cannot outvote it: the pack
        // rule only ever judges the files that name nothing.
        val remux = stream(
            title = "Paw Patrol S03E35 2160p REMUX",
            description = "13.48 GB",
            url = "https://host/e35.mkv"
        )

        assertEquals(remux, EpisodeMatch.autoplayPick(listOf(remux), 3, 35, 12))
    }

    @Test
    fun `the pack rule does not touch a movie request`() {
        val film = stream(
            title = "Some Film 2024 2160p REMUX",
            description = "60 GB",
            url = "https://host/a.mkv"
        )

        assertFalse(EpisodeMatch.isSeasonPack(film, runtimeMinutes = null))
        assertEquals(film, EpisodeMatch.autoplayPick(listOf(film), null, null, 180))
    }

    // ── the id's own pair ───────────────────────────────────────────────────

    @Test
    fun `the episode a request id names is read from its tail`() {
        assertEquals(3 to 30, EpisodeMatch.requestedFrom("tt3121722:3:30"))
        assertEquals(12 to 5, EpisodeMatch.requestedFrom("kitsu:12:5"))

        assertNull(EpisodeMatch.requestedFrom("tt3121722"))
        assertNull(EpisodeMatch.requestedFrom("tt3121722:3"))
        assertNull(EpisodeMatch.requestedFrom("tt3121722:3:special"))
        assertNull(EpisodeMatch.requestedFrom(null))
    }
}
