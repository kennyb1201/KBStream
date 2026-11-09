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
}
