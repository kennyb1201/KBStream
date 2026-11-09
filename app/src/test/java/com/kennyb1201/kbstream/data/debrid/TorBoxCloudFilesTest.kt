package com.kennyb1201.kbstream.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves `mylist` gained for the cloud-file picker (spec F3a, F0e, T4).
 *
 * Both are read FAIL-SOFT: a response that carries neither keeps parsing as the
 * id/name list the library sync has always used, and a torrent this build cannot
 * classify as finished is hidden rather than offered as a row that cannot play.
 */
class TorBoxCloudFilesTest {

    @Test
    fun `a finished download is complete, whichever spelling says so`() {
        fun complete(json: String) = TorBoxClient.parseCloudTorrents(
            """{"data":[{"id":1,"name":"Show.S01E01",$json}]}"""
        ).single().complete

        assertTrue(complete(""""download_finished":true"""))
        assertFalse(complete(""""download_finished":false"""))
        assertTrue(complete(""""download_state":"completed""""))
        assertTrue(complete(""""download_state":"cached""""))
        assertTrue(complete(""""download_present":true"""))
        assertTrue(complete(""""progress":1.0"""))
        assertFalse(complete(""""progress":0.42"""))
        assertFalse("silence is not completeness", complete(""""hash":"abc""""))
    }

    @Test
    fun `a torrent still downloading never reads as complete`() {
        val torrent = TorBoxClient.parseCloudTorrents(
            """{"data":[{"id":1,"name":"Show.S01E01","download_state":"downloading",""" +
                """"progress":0.99,"download_present":false}]}"""
        ).single()
        assertFalse(torrent.complete)
    }

    @Test
    fun `the files are read, with the SDK's id spelling and a short name`() {
        val torrent = TorBoxClient.parseCloudTorrents(
            """{"data":[{"id":4,"name":"Landman.S01E10","download_finished":true,"files":[
                {"id":11,"name":"Landman.S01E10.mkv","size":123,"short_name":"Landman.S01E10.mkv"},
                {"id_":12,"name":"Landman.S01E10.srt","size":9}
            ]}]}"""
        ).single()
        assertEquals(listOf(11, 12), torrent.files.map { it.id })
        assertEquals("Landman.S01E10.mkv", torrent.files[0].name)
        assertEquals(123L, torrent.files[0].size)
        assertEquals(9L, torrent.files[1].size)
    }

    @Test
    fun `a response without files or state is still a list of ids and names`() {
        val torrent = TorBoxClient.parseCloudTorrents(
            """{"data":[{"id":101,"name":"The.Matrix.1999.1080p"}]}"""
        ).single()
        assertEquals(101L, torrent.id)
        assertEquals("The.Matrix.1999.1080p", torrent.name)
        assertTrue(torrent.files.isEmpty())
        assertFalse(torrent.complete)
    }
}
