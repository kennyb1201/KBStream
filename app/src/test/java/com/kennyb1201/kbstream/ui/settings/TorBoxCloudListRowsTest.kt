package com.kennyb1201.kbstream.ui.settings

import com.kennyb1201.kbstream.data.debrid.TorBoxClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows the TorBox cloud list draws (spec F2a, T5, T10).
 *
 * The list's whole job is to make a file the picker cannot place VISIBLE
 * without guessing what it is: a name that parses is labelled with its title and
 * episode, a name that does not is shown raw with no action.
 */
class TorBoxCloudListRowsTest {

    @Test
    fun `a parseable file names its title and episode`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 7,
                    name = "Landman.S01E10.1080p.WEB-DL",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "Landman.S01E10.1080p.WEB-DL.mkv", 1L)
                    )
                )
            )
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Landman", row.title)
        assertEquals(1, row.season)
        assertEquals(10, row.episode)
        assertTrue("the line has to name the episode", row.subtitle.contains("S01E10"))
        assertTrue(row.subtitle.contains("Landman"))
    }

    @Test
    fun `an unparseable file is shown raw and offers no action`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 8,
                    name = "1080p",
                    complete = true,
                    files = listOf(TorBoxClient.CloudFile(0, "1080p.mkv", 1L))
                )
            )
        )
        val row = rows.single()
        assertEquals("1080p.mkv", row.fileName)
        assertNull("no title means no play action", row.title)
        assertTrue(row.subtitle.contains("Not recognised"))
    }

    @Test
    fun `a still-downloading file is not listed at all`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 9,
                    name = "Landman.S01E11.1080p",
                    complete = false,
                    files = listOf(TorBoxClient.CloudFile(0, "Landman.S01E11.mkv", 1L))
                )
            )
        )
        assertTrue(rows.isEmpty())
    }

    // ── playable files only (spec F2/F4) ──────────────────────────────────

    @Test
    fun `only containers a play can use are listed`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 11,
                    name = "Jury.Duty.S01E03.1080p",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "Jury.Duty.S01E03.mkv", 1L),
                        TorBoxClient.CloudFile(1, "screen0001.jpg", 2L),
                        TorBoxClient.CloudFile(2, "Jury.Duty.S01E03.nfo", 3L),
                        TorBoxClient.CloudFile(3, "notes.txt", 4L),
                        TorBoxClient.CloudFile(4, "Jury.Duty.S01E03.mp4", 5L)
                    )
                )
            )
        )
        assertEquals(
            "the field photo's exact case: the two video files, and nothing the picker " +
                "would never offer",
            listOf("Jury.Duty.S01E03.mkv", "Jury.Duty.S01E03.mp4"),
            rows.map { it.fileName }
        )
    }

    @Test
    fun `the extension check ignores case and needs an extension`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 12,
                    name = "Show.S01E01",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "Show.S01E01.MKV", 1L),
                        TorBoxClient.CloudFile(1, "Show.S01E01", 2L),
                        TorBoxClient.CloudFile(2, "archive.mkv.bak", 3L)
                    )
                )
            )
        )
        assertEquals(
            "an uppercase container is the same container; a name with no extension, or " +
                "one whose last dot is not a container, is not",
            listOf("Show.S01E01.MKV"),
            rows.map { it.fileName }
        )
    }

    @Test
    fun `a torrent holding only non-video files contributes nothing`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 13,
                    name = "Jury.Duty.S01E04.1080p",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "screen0001.jpg", 1L),
                        TorBoxClient.CloudFile(1, "screen0002.jpg", 2L)
                    )
                )
            )
        )
        assertTrue(
            "no ifEmpty fallback here - unlike the picker, the list has no obligation " +
                "to offer something, and a screenshot is not a substitute",
            rows.isEmpty()
        )
    }

    @Test
    fun `a playable file with an unparseable name is still listed raw`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 14,
                    name = "1080p",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "1080p.mkv", 1L),
                        TorBoxClient.CloudFile(1, "cover.jpg", 2L)
                    )
                )
            )
        )
        val row = rows.single()
        assertEquals("the playable one survives", "1080p.mkv", row.fileName)
        assertNull("and an unparseable TITLE is still visible, never guessed", row.title)
        assertTrue(row.subtitle.contains("Not recognised"))
    }

    @Test
    fun `the row carries the file's size`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 15,
                    name = "Jury.Duty.S01E05.1080p",
                    complete = true,
                    files = listOf(
                        TorBoxClient.CloudFile(0, "Jury.Duty.S01E05.mkv", 4_000_000_000L),
                        TorBoxClient.CloudFile(1, "Jury.Duty.S01E05.1080p.mkv", 0L)
                    )
                )
            )
        )
        assertEquals(
            "F3: size comes straight off the file, and an answer that omitted it stays 0",
            listOf(4_000_000_000L, 0L),
            rows.map { it.size }
        )
    }

    // ── search and sort (spec F2, F3, F4, F7) ──────────────────────────────

    @Test
    fun `an empty query in the default order is the list exactly as it arrived`() {
        val rows = listOf(
            row("b.mkv", title = "Bravo", episode = 2),
            row("a.mkv", title = "Alpha", episode = 1)
        )
        assertEquals(rows, cloudFileListRows(rows, "", CloudFileSort.NEWEST))
        assertEquals(
            "whitespace alone is an empty query",
            rows,
            cloudFileListRows(rows, "   ", CloudFileSort.NEWEST)
        )
    }

    @Test
    fun `a query matches the title, the file, the torrent and the episode span`() {
        val jury = row("Jury.Duty.S01E08.mkv", title = "Jury Duty", episode = 8)
        val landman = row(
            "Landman.S01E10.mkv",
            title = "Landman",
            episode = 10,
            torrent = "Landman.S01E10.1080p"
        )
        val rows = listOf(jury, landman)

        assertEquals("title", listOf(jury), cloudFileListRows(rows, "jury", CloudFileSort.NEWEST))
        assertEquals(
            "file name",
            listOf(landman),
            cloudFileListRows(rows, "landman.s01e10", CloudFileSort.NEWEST)
        )
        assertEquals(
            "torrent name - the only place this file's row says 1080p",
            listOf(landman),
            cloudFileListRows(rows, "1080p", CloudFileSort.NEWEST)
        )
        assertEquals(
            "the episode span the subtitle prints, in the case the viewer saw it",
            listOf(jury),
            cloudFileListRows(rows, "s01e08", CloudFileSort.NEWEST)
        )
        assertEquals(
            "and the query is trimmed and case-insensitive",
            listOf(jury),
            cloudFileListRows(rows, "  JuRy  ", CloudFileSort.NEWEST)
        )
        assertTrue(
            "F4: nothing matching is an empty answer, not the whole list",
            cloudFileListRows(rows, "nothing here", CloudFileSort.NEWEST).isEmpty()
        )
    }

    @Test
    fun `each sort orders the way the spec says`() {
        val zedLate = row("zed.e02.mkv", title = "Zed", episode = 2, size = 900L)
        val alphaEarly = row("alpha.e01.mkv", title = "Alpha", episode = 1, size = 100L)
        val alphaNoEpisode = row("alpha.extra.mkv", title = "Alpha", season = null, episode = null)
        val untitled = row("1080p.mkv", title = null, season = null, episode = null, size = 0L)
        val rows = listOf(zedLate, alphaEarly, alphaNoEpisode, untitled)

        assertEquals(
            "NAME: A-Z on what the row calls itself, the untitled row by its torrent",
            listOf(alphaEarly, alphaNoEpisode, untitled, zedLate),
            cloudFileListRows(rows, "", CloudFileSort.NAME)
        )
        assertEquals(
            "EPISODE: title, then season, then episode - one episode's files together, " +
                "and the rows with nothing to order by last",
            listOf(alphaEarly, alphaNoEpisode, zedLate, untitled),
            cloudFileListRows(rows, "", CloudFileSort.EPISODE)
        )
        assertEquals(
            "LARGEST: biggest first, unknown sizes (0) last",
            listOf(zedLate, alphaEarly, alphaNoEpisode, untitled),
            cloudFileListRows(rows, "", CloudFileSort.LARGEST)
        )
    }

    @Test
    fun `a query and a sort compose`() {
        val one = row("Jury.Duty.S01E01.mkv", title = "Jury Duty", episode = 1, size = 10L)
        val two = row("Jury.Duty.S01E02.mkv", title = "Jury Duty", episode = 2, size = 20L)
        val other = row(
            "Landman.S01E01.mkv",
            title = "Landman",
            episode = 1,
            size = 99L,
            torrent = "Landman.S01E01.1080p"
        )
        val rows = listOf(one, two, other)

        assertEquals(
            "the filter runs before the order, so sorting never resurrects a filtered row",
            listOf(two, one),
            cloudFileListRows(rows, "jury", CloudFileSort.LARGEST)
        )
    }

    @Test
    fun `the sort cycle is newest, name, episode, largest and back`() {
        assertEquals(CloudFileSort.NAME, CloudFileSort.NEWEST.next())
        assertEquals(CloudFileSort.EPISODE, CloudFileSort.NAME.next())
        assertEquals(CloudFileSort.LARGEST, CloudFileSort.EPISODE.next())
        assertEquals("wraps", CloudFileSort.NEWEST, CloudFileSort.LARGEST.next())
        assertEquals(
            "the control shows the mode, so the labels are part of the contract",
            listOf("Newest", "Name", "Episode", "Largest"),
            CloudFileSort.entries.map { it.label }
        )
    }

    @Test
    fun `a file spanning two episodes says so`() {
        val rows = cloudFileRows(
            listOf(
                TorBoxClient.CloudTorrent(
                    id = 10,
                    name = "Some.Show.S01E01-E02.1080p",
                    complete = true,
                    files = listOf(TorBoxClient.CloudFile(0, "Some.Show.S01E01-E02.mkv", 1L))
                )
            )
        )
        assertTrue(rows.single().subtitle.contains("S01E01-02"))
    }

    /** A row as the dialog would build it, for the pure filter/sort tests. */
    private fun row(
        fileName: String,
        title: String?,
        season: Int? = 1,
        episode: Int? = 1,
        size: Long = 0L,
        torrent: String = "Jury.Duty.S01"
    ) = CloudFileRow(
        torrentId = 1L,
        fileId = fileName.hashCode(),
        torrentName = torrent,
        fileName = fileName,
        title = title,
        season = season,
        episode = episode,
        episodeEnd = episode,
        size = size
    )
}
