package com.kennyb1201.kbstream.data.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the account's TorBox torrent list (`mylist`). Same silent-failure
 * shape as the cached-list parse: reading the wrong field yields no torrents
 * and the Library simply never gains a row, so the response shape is pinned.
 */
class TorBoxCloudListTest {

    @Test
    fun `the data array yields id and name`() {
        val body = """{"success":true,"data":[
            {"id":101,"name":"The.Matrix.1999.1080p","hash":"abc"},
            {"id":202,"name":"Some.Show.S01.1080p"}
        ]}"""
        val torrents = TorBoxClient.parseCloudTorrents(body)
        assertEquals(listOf(101L, 202L), torrents.map { it.id })
        assertEquals("The.Matrix.1999.1080p", torrents[0].name)
    }

    @Test
    fun `names are trimmed`() {
        val body = """{"data":[{"id":1,"name":"  Padded Name  "}]}"""
        assertEquals("Padded Name", TorBoxClient.parseCloudTorrents(body)[0].name)
    }

    @Test
    fun `an entry without an id is dropped`() {
        val body = """{"data":[{"name":"No id"},{"id":5,"name":"Has id"}]}"""
        assertEquals(listOf(5L), TorBoxClient.parseCloudTorrents(body).map { it.id })
    }

    @Test
    fun `an entry without a name is dropped`() {
        val body = """{"data":[{"id":1,"name":"   "},{"id":2,"name":"Named"}]}"""
        assertEquals(listOf(2L), TorBoxClient.parseCloudTorrents(body).map { it.id })
    }

    @Test
    fun `an empty, null or malformed body yields nothing`() {
        assertTrue(TorBoxClient.parseCloudTorrents("").isEmpty())
        assertTrue(TorBoxClient.parseCloudTorrents("nope").isEmpty())
        assertTrue(TorBoxClient.parseCloudTorrents("""{"success":false,"data":null}""").isEmpty())
    }
}
