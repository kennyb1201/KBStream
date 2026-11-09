package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.badges.StreamBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn a TorBox cloud file into a picker row (spec T1, T3-T7,
 * T9, T11).
 *
 * Everything here is pure on purpose: the field report is a FILE that no add-on
 * returns, so "does this file belong to this episode" has to be a question with
 * an answer that does not depend on a network, an account or a screen.
 */
class TorBoxCloudPickerTest {

    private fun torrent(
        id: Long,
        name: String,
        complete: Boolean = true,
        vararg files: String
    ) = TorBoxClient.CloudTorrent(
        id = id,
        name = name,
        complete = complete,
        progress = if (complete) 1.0 else 0.42,
        files = files.mapIndexed { index, file ->
            TorBoxClient.CloudFile(id = index, name = file, size = 1_000_000L + index)
        }
    )

    // ── T1: the field case ───────────────────────────────────────────────────

    @Test
    fun `a downloaded episode names its own episode and plays through the account`() {
        val landman = torrent(
            7,
            "Landman.S01E10.1080p.WEB-DL",
            true,
            "Landman.S01E10.1080p.WEB-DL.mkv"
        )
        val matches = TorBoxCloudPicker.matches(
            torrents = listOf(landman),
            mediaType = "series",
            title = "Landman S1 E10 • The Crumbs of Hope",
            year = null,
            season = 1,
            episode = 10
        )
        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals(7L, match.torrentId)
        assertEquals(0, match.fileId)

        val row = TorBoxCloudPicker.rowStream(match)
        assertEquals("TorBox Cloud", row.name)
        assertEquals("Landman.S01E10.1080p.WEB-DL.mkv", row.title)
        // The row's address is a reference, not a link: the tap mints the link
        // through TorBoxResolve's requestdl path.
        assertEquals(
            TorBoxCloudPicker.CloudRef(torrentId = 7, fileId = 0),
            TorBoxCloudPicker.refOf(row.url)
        )
        assertTrue(TorBoxCloudPicker.isCloudRow(row))
        assertFalse("a real link is never a cloud reference", TorBoxCloudPicker.isCloudRow(
            Stream(url = "https://webtor.io/abc/Landman.mkv")
        ))
    }

    @Test
    fun `a season pack's file, not its torrent name, decides the episode`() {
        val pack = torrent(
            9,
            "Landman.S01.1080p.WEB-DL",
            true,
            "Landman.S01E09.mkv",
            "Landman.S01E10.mkv",
            "Landman.S01E11.mkv"
        )
        fun rowsFor(episode: Int) = TorBoxCloudPicker.matches(
            torrents = listOf(pack),
            mediaType = "series",
            title = "Landman S1 E$episode",
            year = null,
            season = 1,
            episode = episode
        )

        assertEquals(listOf("Landman.S01E10.mkv"), rowsFor(10).map { it.fileName })
        assertEquals("an episode with no file earns no row", 0, rowsFor(8).size)
        assertEquals("and a season pack never lands on every episode", 1, rowsFor(9).size)
    }

    @Test
    fun `a file spanning two episodes earns a row on each`() {
        val twoParter = torrent(11, "Some.Show.S01E01-E02.1080p", true, "Some.Show.S01E01-E02.mkv")
        fun rowsFor(episode: Int) = TorBoxCloudPicker.matches(
            torrents = listOf(twoParter),
            mediaType = "series",
            title = "Some Show S1 E$episode",
            year = null,
            season = 1,
            episode = episode
        )
        assertEquals(1, rowsFor(1).size)
        assertEquals(1, rowsFor(2).size)
        assertEquals(0, rowsFor(3).size)
    }

    @Test
    fun `an episode named without a season takes the torrent's season`() {
        val pack = torrent(12, "Landman.S01.1080p", true, "Landman.E10.mkv")
        assertEquals(
            1,
            TorBoxCloudPicker.matches(listOf(pack), "series", "Landman S1 E10", null, 1, 10).size
        )
        assertEquals(
            "a bare E10 does not silently cover another season",
            0,
            TorBoxCloudPicker.matches(listOf(pack), "series", "Landman S2 E10", null, 2, 10).size
        )
    }

    // ── T4 / T5 / T6: what must NOT earn a row ──────────────────────────────

    @Test
    fun `a file still downloading is hidden, never greyed`() {
        val partial = torrent(
            21,
            "Landman.S01E10.1080p",
            complete = false,
            "Landman.S01E10.1080p.mkv"
        )
        assertTrue(
            TorBoxCloudPicker.matches(listOf(partial), "series", "Landman S1 E10", null, 1, 10)
                .isEmpty()
        )
    }

    @Test
    fun `an unparseable release name earns nothing`() {
        val junk = torrent(22, "1080p", true, "1080p.mkv")
        assertTrue(
            TorBoxCloudPicker.matches(listOf(junk), "series", "Landman S1 E10", null, 1, 10)
                .isEmpty()
        )
        assertTrue(TorBoxCloudPicker.matches(listOf(junk), "movie", "Landman", null, null, null)
            .isEmpty())
    }

    @Test
    fun `a different title is not guessed at, whatever the episode says`() {
        val other = torrent(23, "Landmen.S01E10.1080p", true, "Landmen.S01E10.mkv")
        assertTrue(
            TorBoxCloudPicker.matches(listOf(other), "series", "Landman S1 E10", null, 1, 10)
                .isEmpty()
        )
    }

    @Test
    fun `a low-confidence year is the sync's bar, not a near miss`() {
        val parsed = TorBoxCloudRules.parseName("The.Matrix.1999.1080p")!!
        assertTrue(TorBoxCloudRules.confidentMatch(parsed, "The Matrix", 1999))
        assertTrue(TorBoxCloudRules.confidentMatch(parsed, "The Matrix", 2000))
        assertFalse(TorBoxCloudRules.confidentMatch(parsed, "The Matrix", 2021))
        assertFalse(TorBoxCloudRules.confidentMatch(parsed, "The Matrix Reloaded", 1999))
    }

    // ── T9: films ───────────────────────────────────────────────────────────

    @Test
    fun `a film torrent earns a row on the film's picker`() {
        val matrix = torrent(
            31,
            "The.Matrix.1999.1080p.BluRay",
            true,
            "The.Matrix.1999.1080p.BluRay.mkv"
        )
        val matches = TorBoxCloudPicker.matches(
            torrents = listOf(matrix),
            mediaType = "movie",
            title = "The Matrix",
            year = null,
            season = null,
            episode = null
        )
        assertEquals(1, matches.size)
        assertEquals("The.Matrix.1999.1080p.BluRay.mkv", matches.single().fileName)
    }

    @Test
    fun `a series release is never a film of the same name`() {
        val show = torrent(32, "The.Matrix.S01E01.1080p", true, "The.Matrix.S01E01.mkv")
        assertTrue(
            TorBoxCloudPicker.matches(listOf(show), "movie", "The Matrix", null, null, null)
                .isEmpty()
        )
    }

    @Test
    fun `the largest video file is what a film torrent plays`() {
        val film = TorBoxClient.CloudTorrent(
            id = 33,
            name = "The.Matrix.1999.1080p",
            complete = true,
            files = listOf(
                TorBoxClient.CloudFile(id = 0, name = "sample.mkv", size = 5_000_000L),
                TorBoxClient.CloudFile(id = 1, name = "The.Matrix.mkv", size = 9_000_000_000L),
                TorBoxClient.CloudFile(id = 2, name = "readme.nfo", size = 1_000L)
            )
        )
        val match = TorBoxCloudPicker.matches(
            listOf(film), "movie", "The Matrix", null, null, null
        ).single()
        assertEquals(1, match.fileId)
    }

    // ── T11: placement and the player's hand-off ────────────────────────────

    @Test
    fun `cloud rows sort after the add-on-matched TorBox rows, before HTTP`() {
        val cachedTorBox = Stream(name = "Torrentio", title = "1080p", infoHash = "abc")
            .copy(
                badges = listOf(StreamBadge(name = "Cached"))
            )
        val http = Stream(name = "Web-DL", title = "1080p", url = "https://host/file.mkv")
        val rows = listOf(http, cachedTorBox, http)
        assertEquals(2, TorBoxCloudPicker.insertIndex(rows))
        assertFalse("a plain HTTP row is not the insertion point", TorBoxCloudPicker.isTorBoxRow(http))
        assertTrue(TorBoxCloudPicker.isTorBoxRow(cachedTorBox))
    }

    @Test
    fun `with no TorBox row the cloud row leads the list`() {
        val http = Stream(name = "Web-DL", url = "https://host/file.mkv")
        assertEquals(0, TorBoxCloudPicker.insertIndex(listOf(http)))
        assertEquals(0, TorBoxCloudPicker.insertIndex(emptyList()))
    }

    @Test
    fun `the player is never handed a cloud reference in place of a link`() {
        val cloud = TorBoxCloudPicker.rowStream(
            TorBoxCloudPicker.CloudMatch(1, 2, "Release", "Release.mkv", 10L)
        )
        val http = Stream(name = "Web", url = "https://host/file.mkv")
        val handed = TorBoxCloudPicker.forPlayer(listOf(cloud, http))
        assertEquals(null, handed[0].url)
        assertEquals("its place in the list is kept, so add-on names stay aligned", 2, handed.size)
        assertEquals("https://host/file.mkv", handed[1].url)
    }
}
