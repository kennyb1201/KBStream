package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the in-player guide overlay shows per channel, and what it reads. */
class ChannelGuideProgramsTest {

    private val hour = 60L * 60L * 1000L

    private fun program(channelId: String, title: String, start: Long, end: Long) =
        EpgProgramRow(
            channelId = channelId,
            title = title,
            description = null,
            category = null,
            startUtcMillis = start,
            endUtcMillis = end
        )

    private fun channel(
        id: String,
        epgChannelId: String? = null,
        epgUrl: String? = null,
        number: String? = null
    ) = LiveChannelZapRegistry.ZapChannel(
        channelId = id,
        name = id,
        streamUrl = "http://host/$id",
        logoUrl = null,
        chno = number,
        epgChannelId = epgChannelId,
        epgUrl = epgUrl
    )

    @Test
    fun `the running program is now and the following one is next`() {
        val now = 100L
        val rows = listOf(
            program("a", "Earlier", now - 2 * hour, now - hour),
            program("a", "On Now", now - hour, now + hour),
            program("a", "Up Next", now + hour, now + 2 * hour)
        )

        val entry = nowNextByChannel(rows, now).getValue("a")

        assertEquals("On Now", entry.now?.title)
        assertEquals("Up Next", entry.next?.title)
    }

    @Test
    fun `a gap between programs leaves now empty and keeps the next`() {
        // The guide has a hole where the current programme should be, so the
        // upcoming row must not be reported as "on now".
        val now = 100L
        val rows = listOf(
            program("a", "Finished", now - 2 * hour, now - hour),
            program("a", "Later", now + hour, now + 2 * hour)
        )

        val entry = nowNextByChannel(rows, now).getValue("a")

        assertNull(entry.now)
        assertEquals("Later", entry.next?.title)
    }

    @Test
    fun `a program starting exactly now counts as on now`() {
        // The window query returns the program whose end is past the instant,
        // so a boundary start is the current one - not the upcoming one, which
        // is what a strict "starts after now" rule applied to both fields would
        // report and would leave the NOW line empty every time a program turned
        // over.
        val boundary = 100L
        val rows = listOf(
            program("a", "On Now", boundary, boundary + hour),
            program("a", "Up Next", boundary + hour, boundary + 2 * hour)
        )

        val entry = nowNextByChannel(rows, boundary).getValue("a")

        assertEquals("On Now", entry.now?.title)
        assertEquals("Up Next", entry.next?.title)
    }

    @Test
    fun `rows arriving out of order are still paired correctly`() {
        val now = 100L
        val rows = listOf(
            program("a", "Up Next", now + hour, now + 2 * hour),
            program("a", "On Now", now - hour, now + hour)
        )

        val entry = nowNextByChannel(rows, now).getValue("a")

        assertEquals("On Now", entry.now?.title)
        assertEquals("Up Next", entry.next?.title)
    }

    @Test
    fun `channels are bucketed independently`() {
        val now = 100L
        val rows = listOf(
            program("a", "A Now", now - hour, now + hour),
            program("b", "B Now", now - hour, now + hour)
        )

        val map = nowNextByChannel(rows, now)

        assertEquals("A Now", map.getValue("a").now?.title)
        assertEquals("B Now", map.getValue("b").now?.title)
    }

    @Test
    fun `a channel with no rows gets no entry`() {
        assertEquals(emptyMap<String, ChannelNowNext>(), nowNextByChannel(emptyList(), 100L))
    }

    @Test
    fun `only channels matched to a guide are planned`() {
        val queries = planGuideQueries(
            listOf(
                channel("a", epgChannelId = "id.a", epgUrl = "http://epg/one"),
                channel("b"),
                channel("c", epgChannelId = "id.c")
            )
        )

        assertEquals(listOf(GuideQuery("http://epg/one", listOf("id.a"))), queries)
    }

    @Test
    fun `guide ids are lowercased and duplicates collapse within a source`() {
        val queries = planGuideQueries(
            listOf(
                channel("a", epgChannelId = "ESPN.us", epgUrl = "http://epg/one"),
                channel("b", epgChannelId = "espn.us", epgUrl = "http://epg/one")
            )
        )

        assertEquals(listOf(GuideQuery("http://epg/one", listOf("espn.us"))), queries)
    }

    @Test
    fun `each source is queried on its own`() {
        val queries = planGuideQueries(
            listOf(
                channel("a", epgChannelId = "id.a", epgUrl = "http://epg/one"),
                channel("b", epgChannelId = "id.b", epgUrl = "http://epg/two")
            )
        )

        assertEquals(
            listOf(
                GuideQuery("http://epg/one", listOf("id.a")),
                GuideQuery("http://epg/two", listOf("id.b"))
            ),
            queries
        )
    }

    @Test
    fun `a source longer than the batch size is split`() {
        val queries = planGuideQueries(
            listOf(
                channel("a", epgChannelId = "a", epgUrl = "http://epg/one"),
                channel("b", epgChannelId = "b", epgUrl = "http://epg/one"),
                channel("c", epgChannelId = "c", epgUrl = "http://epg/one")
            ),
            batchSize = 2
        )

        assertEquals(
            listOf(
                GuideQuery("http://epg/one", listOf("a", "b")),
                GuideQuery("http://epg/one", listOf("c"))
            ),
            queries
        )
    }

    @Test
    fun `a non-positive batch size plans nothing`() {
        assertEquals(
            emptyList<GuideQuery>(),
            planGuideQueries(
                listOf(channel("a", epgChannelId = "a", epgUrl = "http://epg/one")),
                batchSize = 0
            )
        )
    }
}
