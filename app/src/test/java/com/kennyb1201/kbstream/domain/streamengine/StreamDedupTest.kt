package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same torrent offered by more than one addon is ONE row.
 *
 * Reported problem: several addons that index the same releases made the picker
 * three times as long as the choice it offered - the same file, same size, same
 * labels, same hash - and moving between those "rows" wasted remote presses.
 *
 * Pinned here: the identity is the torrent FILE (info hash plus file index), the
 * surviving row is the first in the order given (which is why the resolve path
 * collapses AFTER the addon reorder), a different file index stays its own row,
 * and a hash-less link is never collapsed with anything.
 */
class StreamDedupTest {

    private val hash = "8F3C1D2E4B5A69788796A5B4C3D2E1F0"

    private fun torrent(addon: String, hash: String?, fileIdx: Int? = null) = Stream(
        name = addon,
        title = "Some Show S2E5 1080p",
        infoHash = hash,
        fileIdx = fileIdx
    )

    private fun link(addon: String, url: String) = Stream(
        name = addon,
        title = "Some Show S2E5 1080p",
        url = url
    )

    @Test
    fun `the same torrent from three addons is one row`() {
        val aio = torrent("AIOStreams", hash, fileIdx = 1)
        val flix = torrent("FlixStreams", hash, fileIdx = 1)
        val tor = torrent("Torrentio", hash, fileIdx = 1)

        val result = StreamDedup.collapse(listOf(aio, flix, tor))

        assertEquals(listOf(aio), result)
    }

    @Test
    fun `the survivor is the best-placed row, not the addon that answered first`() {
        // The resolve path reorders first - the addon that last worked this
        // title leads - so the row that survives the collapse is the one the
        // viewer would have picked from anyway.
        val aio = torrent("AIOStreams", hash)
        val tor = torrent("Torrentio", hash)
        val flix = torrent("FlixStreams", hash)

        val ordered = SourceAddonPreference.ordered(
            streams = listOf(aio, tor, flix),
            addonOf = { it.name },
            outcomes = SourceAddonPreference.Outcomes(
                worked = setOf(SourceAddonPreference.normalize("FlixStreams"))
            )
        )

        assertEquals(listOf(flix), StreamDedup.collapse(ordered))
    }

    @Test
    fun `the same hash at another file index is another choice`() {
        // One file of a multi-file torrent is not another: index 0 and index 1
        // of the same hash are the same swarm and two different videos, and
        // collapsing them would drop a source the viewer can play.
        val episode = torrent("AIOStreams", hash, fileIdx = 0)
        val sample = torrent("FlixStreams", hash, fileIdx = 1)

        assertEquals(listOf(episode, sample), StreamDedup.collapse(listOf(episode, sample)))
    }

    @Test
    fun `hash-less links are never collapsed`() {
        // A direct link is one host's URL, not a swarm: two of them are two
        // different files whatever their labels say.
        val a = link("AIOStreams", "https://a.example/e5.mp4")
        val b = link("FlixStreams", "https://b.example/e5.mp4")

        assertEquals(listOf(a, b), StreamDedup.collapse(listOf(a, b)))
    }

    @Test
    fun `a hash written in another case is the same torrent`() {
        val lower = torrent("AIOStreams", hash.lowercase())
        val upper = torrent("FlixStreams", hash)

        assertEquals(listOf(lower), StreamDedup.collapse(listOf(lower, upper)))
    }

    @Test
    fun `a blank hash is not an identity`() {
        val blankA = torrent("AIOStreams", "   ", fileIdx = 0)
        val blankB = torrent("FlixStreams", "   ", fileIdx = 0)

        assertEquals(listOf(blankA, blankB), StreamDedup.collapse(listOf(blankA, blankB)))
    }

    @Test
    fun `a single source is returned untouched`() {
        val streams = listOf(torrent("AIOStreams", hash))

        assertTrue("the very list must come back", streams === StreamDedup.collapse(streams))
    }

    @Test
    fun `different torrents are still different rows`() {
        val one = torrent("AIOStreams", hash)
        val two = torrent("AIOStreams", "0123456789abcdef0123456789abcdef")

        assertEquals(listOf(one, two), StreamDedup.collapse(listOf(one, two)))
    }
}
