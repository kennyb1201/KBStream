package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.player.bundledEpisodeCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name the scheme detector searches.
 *
 * A Stremio add-on names a file in three places, and which one holds it is the
 * add-on's choice: the release name is usually the `title`, some rows put it in
 * `description`, and a direct link's last path segment is the file name itself.
 * [Stream.detectionFileName] joins every one of those so the detector sees
 * whatever the row carries - and the detector only ever looks for the
 * `S<season>E<episode>` shape inside it, so joining more text cannot invent a
 * bundle. The last case is the one to keep honest: a magnet or infohash ends in
 * something that is not a file name, and it must simply match nothing.
 */
class PlayerDetectionFileNameTest {

    @Test
    fun `the release name in the title is what the detector searches`() {
        val stream = Stream(
            name = "Torrentio",
            title = "PAW Patrol S01E01E02 1080p WEB-DL",
            url = "https://example.test/play/123"
        )
        val fileName = stream.detectionFileName()
        assertTrue(fileName, fileName?.contains("S01E01E02") == true)
        assertEquals(2, bundledEpisodeCount(fileName))
    }

    @Test
    fun `every name the row carries is joined, so none can hide the bundle`() {
        val stream = Stream(
            name = "Addon",
            title = "Some Show",
            description = "Some.Show.S02E03E04.720p"
        )
        val fileName = stream.detectionFileName()
        assertTrue(fileName, fileName?.contains("S02E03E04") == true)
        assertEquals("E03 and E04 are the pair the file holds", 2, bundledEpisodeCount(fileName))
    }

    @Test
    fun `a bare URL still names its file`() {
        val stream = Stream(url = "https://example.test/files/Paw.Patrol.S01E01E02.mkv?token=abc")
        val fileName = stream.detectionFileName()
        assertTrue(fileName, fileName?.contains("S01E01E02") == true)
        assertEquals("the query string is not part of the name", 2, bundledEpisodeCount(fileName))
    }

    @Test
    fun `a magnet names nothing`() {
        val stream = Stream(
            name = "Torrentio",
            url = "magnet:?xt=urn:btih:1f2e3d4c5b6a79887766554433221100aabbccdd"
        )
        // The row is still named - the detector just has no episode shape to find.
        assertNull(bundledEpisodeCount(stream.detectionFileName()))
    }

    @Test
    fun `a row that named nothing answers null, not a blank to search`() {
        assertNull(Stream(url = "https://example.test/").detectionFileName())
        assertNull(Stream(name = "  ", title = "", url = "  ").detectionFileName())
    }
}
