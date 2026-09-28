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
 * everyday unlabelled release would break auto-play everywhere else.
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
        val unlabelled = stream(title = "Paw Patrol 1080p WEB-DL 8 GB")
        assertEquals(EpisodeMatch.Verdict.UNKNOWN, EpisodeMatch.verdict(unlabelled, 3, 30))
    }

    // ── what auto-play starts ───────────────────────────────────────────────

    @Test
    fun `the episode asked for is played over a better-labelled stranger`() {
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
        // An unlabelled release may well be the episode; refusing it would stop
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
