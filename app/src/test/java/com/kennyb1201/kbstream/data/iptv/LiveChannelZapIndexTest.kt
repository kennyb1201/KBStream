package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity lookups in the zap lineup are answered from an index, not a scan.
 *
 * Reported by Sentry as an ANR on the onn. 4K Plus (ANDROID-Z, 0.6.109): the
 * main thread was inside [LiveChannelZapRegistry.indexOfChannel] with the
 * player's guide opener above it, because every lookup walked the whole
 * published group and a real IPTV group is tens of thousands of channels.
 *
 * The behaviour must not change - first match wins, unknown is -1 - so these
 * pin both the answers and the fact that answering costs no reads of the
 * lineup after it is published.
 */
class LiveChannelZapIndexTest {

    private fun channel(id: String, url: String = "http://host/$id") =
        LiveChannelZapRegistry.ZapChannel(
            channelId = id,
            name = id,
            streamUrl = url,
            logoUrl = null
        )

    /** Counts element reads, so a lookup that scans is visible as a count. */
    private class CountingList(
        private val items: List<LiveChannelZapRegistry.ZapChannel>
    ) : AbstractList<LiveChannelZapRegistry.ZapChannel>() {
        var reads = 0
            private set

        override val size: Int get() = items.size

        override fun get(index: Int): LiveChannelZapRegistry.ZapChannel {
            reads++
            return items[index]
        }
    }

    private fun publish(list: List<LiveChannelZapRegistry.ZapChannel>) {
        LiveChannelZapRegistry.setGroups(
            groups = listOf(LiveChannelZapRegistry.ZapGroup("All", list))
        )
    }

    @Test
    fun `a long lineup is indexed once, and lookups do not read it again`() {
        val channels = CountingList((0 until 20_000).map { channel("ch$it") })
        publish(channels)
        val readsAfterPublish = channels.reads

        assertEquals(19_999, LiveChannelZapRegistry.indexOfChannel("ch19999"))
        assertEquals(0, LiveChannelZapRegistry.indexOfChannel("ch0"))
        assertEquals("a lookup must not scan the lineup", readsAfterPublish, channels.reads)
    }

    @Test
    fun `an unknown id is minus one and still costs no scan`() {
        val channels = CountingList((0 until 1_000).map { channel("ch$it") })
        publish(channels)
        val readsAfterPublish = channels.reads

        assertEquals(-1, LiveChannelZapRegistry.indexOfChannel("not-here"))
        assertEquals(readsAfterPublish, channels.reads)
    }

    @Test
    fun `a duplicated id resolves to its first entry`() {
        // The scan this replaced was indexOfFirst, so the earlier entry is the
        // one the player zaps from; a later duplicate must not win.
        publish(listOf(channel("dup"), channel("other"), channel("dup")))

        assertEquals(0, LiveChannelZapRegistry.indexOfChannel("dup"))
        assertEquals(1, LiveChannelZapRegistry.indexOfChannel("other"))
    }

    @Test
    fun `the stream-url fallback is indexed too`() {
        val channels = CountingList(
            listOf(
                channel("a", "http://host/a"),
                channel("b", "http://host/b")
            )
        )
        publish(channels)
        val readsAfterPublish = channels.reads

        assertEquals(1, LiveChannelZapRegistry.indexOfStreamUrl("http://host/b"))
        assertEquals(-1, LiveChannelZapRegistry.indexOfStreamUrl("http://host/none"))
        assertEquals(readsAfterPublish, channels.reads)
    }

    @Test
    fun `switching groups republishes the index`() {
        LiveChannelZapRegistry.setGroups(
            groups = listOf(
                LiveChannelZapRegistry.ZapGroup("All", listOf(channel("a1"), channel("a2"))),
                LiveChannelZapRegistry.ZapGroup("News", listOf(channel("n1")))
            ),
            selected = "All"
        )
        assertEquals(1, LiveChannelZapRegistry.indexOfChannel("a2"))

        assertTrue(LiveChannelZapRegistry.offsetGroup(1))

        // The browsed group is what the player zaps through, so the index has
        // to follow it: the old group's ids are gone, the new one resolves.
        assertEquals(-1, LiveChannelZapRegistry.indexOfChannel("a2"))
        assertEquals(0, LiveChannelZapRegistry.indexOfChannel("n1"))
    }

    @Test
    fun `publishing nothing and clearing both empty the index`() {
        publish(listOf(channel("a")))
        assertEquals(0, LiveChannelZapRegistry.indexOfChannel("a"))

        LiveChannelZapRegistry.setGroups(emptyList())
        assertEquals(-1, LiveChannelZapRegistry.indexOfChannel("a"))

        publish(listOf(channel("a")))
        LiveChannelZapRegistry.clear()
        assertEquals(-1, LiveChannelZapRegistry.indexOfChannel("a"))
        assertEquals(-1, LiveChannelZapRegistry.indexOfStreamUrl("http://host/a"))
    }

    @Test
    fun `the snapshot is the published list, not a copy`() {
        // The in-player guide plans its EPG read over this list; rebuilding an
        // equal one per call was the second full allocation on the open path.
        val published = listOf(channel("a"), channel("b"))
        publish(published)

        assertSame(published, LiveChannelZapRegistry.snapshot())
        assertEquals(2, LiveChannelZapRegistry.snapshot().size)
    }
}
