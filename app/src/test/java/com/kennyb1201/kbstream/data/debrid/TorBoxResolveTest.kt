package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind "press a cached hash row and it plays", tested without a
 * network.
 *
 * The three things that can go wrong here are all decidable before any request
 * is made, so they are pinned in isolation: which rows are worth resolving at
 * all, which file of a torrent a play should ask for, and how TorBox's answers
 * are read. A wrong answer to any of them either plays the wrong episode or
 * turns a working row into a dead one.
 */
class TorBoxResolveTest {

    private val hash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"

    private fun file(id: Int, name: String, size: Long) =
        TorBoxResolve.TorrentFile(id = id, name = name, size = size)

    @Test
    fun `only a hash row with no url of its own needs resolving`() {
        assertTrue(
            TorBoxResolve.isUnresolved(Stream(title = "1080p", infoHash = hash))
        )
        assertFalse(
            "a source that already has a URL is playable as it is; routing it" +
                " through debrid would spend an account write to replace it",
            TorBoxResolve.isUnresolved(
                Stream(title = "1080p", url = "https://cdn/example.mkv", infoHash = hash)
            )
        )
        assertFalse(
            "a blank placeholder row has nothing to resolve",
            TorBoxResolve.isUnresolved(Stream(title = "Direct"))
        )
    }

    @Test
    fun `the hash is normalized and a short one is refused`() {
        assertEquals(hash, TorBoxResolve.normalizedHash("  ${hash.uppercase()} "))
        assertNull(TorBoxResolve.normalizedHash("abc123"))
        assertNull(TorBoxResolve.normalizedHash(null))
    }

    @Test
    fun `the add-on's file index wins over every guess`() {
        val files = listOf(
            file(0, "Show.S01E01.mkv", 1_000),
            file(1, "Show.S01E02.mkv", 2_000)
        )
        assertEquals(
            "the add-on chose that file for this episode",
            1,
            TorBoxResolve.pickFile(files, fileIdx = 1, filename = null)?.id
        )
        assertEquals(
            "an index the torrent does not have falls through instead of failing",
            1,
            TorBoxResolve.pickFile(files, fileIdx = 9, filename = null)?.id
        )
    }

    @Test
    fun `a hinted filename is matched full and by basename`() {
        val files = listOf(
            file(0, "Show.S01E01.mkv", 5_000),
            file(1, "Show.S01E02.mkv", 1_000)
        )
        assertEquals(
            1,
            TorBoxResolve.pickFile(
                files,
                fileIdx = null,
                filename = "Show.S01E02.mkv"
            )?.id
        )
        assertEquals(
            "the hint can name a path inside the torrent",
            0,
            TorBoxResolve.pickFile(
                files,
                fileIdx = null,
                filename = "Season 1/Show.S01E01.mkv"
            )?.id
        )
    }

    @Test
    fun `without a hint the largest video file is played`() {
        val files = listOf(
            file(0, "sample.mkv", 10),
            file(1, "Show.S01E01.mkv", 900),
            file(2, "readme.nfo", 5_000),
            file(3, "Show.S01E01.subs.srt", 20)
        )
        assertEquals(
            "a bigger non-video file must not win",
            1,
            TorBoxResolve.pickFile(files, fileIdx = null, filename = null)?.id
        )
        assertEquals(
            "with no video at all the largest file still beats nothing",
            1,
            TorBoxResolve.pickFile(
                listOf(file(0, "a.txt", 1), file(1, "b.bin", 2)),
                fileIdx = null,
                filename = null
            )?.id
        )
        assertNull(TorBoxResolve.pickFile(emptyList(), fileIdx = 0, filename = "x.mkv"))
    }

    @Test
    fun `the created torrent id is read from the documented envelope`() {
        assertEquals(
            4242L,
            TorBoxResolve.parseCreatedTorrentId("""{"success":true,"data":{"torrent_id":4242}}""")
        )
        assertEquals(
            "a string id is accepted: TorBox's SDKs spell ids both ways",
            7L,
            TorBoxResolve.parseCreatedTorrentId("""{"data":{"torrent_id":"7"}}""")
        )
        assertNull(TorBoxResolve.parseCreatedTorrentId("""{"success":false,"error":"DUPLICATE_ITEM"}"""))
        assertNull(TorBoxResolve.parseCreatedTorrentId("not json"))
    }

    @Test
    fun `a torrent list is read from both the array and object shapes`() {
        val array = """
            {"data":[
              {"id":11,"hash":"${hash.uppercase()}","files":[
                {"id":0,"name":"E01.mkv","size":100},
                {"id_":1,"name":"E02.mkv","size":200}
              ]}
            ]}
        """.trimIndent()
        val listed = TorBoxResolve.parseTorrentList(array)
        assertEquals(1, listed.size)
        assertEquals(hash.uppercase(), listed[0].first)
        assertEquals(11L, listed[0].second)
        assertEquals(
            "the `id_` spelling the generated SDK uses is read too",
            listOf(0, 1),
            listed[0].third.map { it.id }
        )

        val single = TorBoxResolve.parseTorrent(
            """{"data":{"id":12,"hash":"$hash","files":[{"id":0,"name":"E01.mkv","size":1}]}}"""
        )
        assertEquals(12L, single?.id)
        assertEquals(1, single?.files?.size)

        assertTrue(TorBoxResolve.parseTorrentList("""{"success":false}""").isEmpty())
        assertNull(TorBoxResolve.parseTorrent("not json"))
    }

    @Test
    fun `the download link is read out of the envelope`() {
        assertEquals(
            "https://cdn.torbox.app/file.mkv",
            TorBoxResolve.parseDownloadUrl(
                """{"success":true,"data":"https://cdn.torbox.app/file.mkv"}"""
            )
        )
        assertEquals(
            "the object spelling is tolerated rather than read as nothing",
            "https://cdn.torbox.app/file.mkv",
            TorBoxResolve.parseDownloadUrl("""{"data":{"url":"https://cdn.torbox.app/file.mkv"}}""")
        )
        assertNull(TorBoxResolve.parseDownloadUrl("""{"success":false,"error":"ITEM_NOT_FOUND"}"""))
        assertNull(TorBoxResolve.parseDownloadUrl("""{"data":"not-a-link"}"""))
    }

    @Test
    fun `the resolved row is playable and stops advertising its hash`() {
        val stream = Stream(
            title = "1080p",
            infoHash = hash,
            fileIdx = 2,
            behaviorHints = StreamBehaviorHints(filename = "E03.mkv")
        )
        val playable = TorBoxResolve.playable(stream, "https://cdn.torbox.app/file.mkv")
        assertEquals("https://cdn.torbox.app/file.mkv", playable.url)
        assertEquals("nothing else about the row changes", stream.title, playable.title)
        assertNull(
            "the hash has done its job and must not read as an address again",
            playable.infoHash
        )
        assertNull(playable.fileIdx)
        assertNull(
            "the filename hint pointed at the torrent, not at the CDN file",
            playable.behaviorHints?.filename
        )
        assertFalse(
            "the row is no longer a hash row, so pressing it again plays it",
            TorBoxResolve.isUnresolved(playable)
        )
    }
}
