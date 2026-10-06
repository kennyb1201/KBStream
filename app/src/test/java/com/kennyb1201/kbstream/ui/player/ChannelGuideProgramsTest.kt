package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        number: String? = null,
        tvgId: String? = null,
        tvgName: String? = null
    ) = LiveChannelZapRegistry.ZapChannel(
        channelId = id,
        name = id,
        streamUrl = "http://host/$id",
        logoUrl = null,
        chno = number,
        epgChannelId = epgChannelId,
        epgUrl = epgUrl,
        tvgId = tvgId,
        tvgName = tvgName
    )

    @Test
    fun `an unmatched entry with a source and an id needs a match resolved`() {
        // The case the in-player guide could not recover from: the guide screen
        // published the channel before matching it to an imported guide.
        assertTrue(
            needsGuideMatch(
                channel("a", epgUrl = "http://epg/one", tvgId = "a.us")
            )
        )
        // A name alone is enough to resolve by.
        assertTrue(
            needsGuideMatch(channel("a", epgUrl = "http://epg/one", tvgName = "Channel A"))
        )
    }

    @Test
    fun `an already matched or sourceless entry needs no match`() {
        // Already matched by the guide screen.
        assertFalse(
            needsGuideMatch(
                channel("a", epgChannelId = "a.us", epgUrl = "http://epg/one")
            )
        )
        // No guide source to match against.
        assertFalse(needsGuideMatch(channel("a", tvgId = "a.us")))
    }

    @Test
    fun `the match query carries the same candidates the guide screen feeds the matcher`() {
        val query = guideMatchQueryFor(
            LiveChannelZapRegistry.ZapChannel(
                channelId = "key.a",
                name = "Channel A HD",
                streamUrl = "http://host/a",
                logoUrl = null,
                epgUrl = "  http://epg/one  ",
                tvgId = "a.us",
                tvgName = "Channel A"
            )
        )
        assertEquals("key.a", query?.key)
        assertEquals("http://epg/one", query?.epgUrl)
        assertEquals(listOf("a.us"), query?.idCandidates)
        assertEquals(listOf("Channel A", "Channel A HD"), query?.nameCandidates)
    }

    @Test
    fun `an entry with no source or no identity yields no match query`() {
        // No guide source to match against.
        assertNull(guideMatchQueryFor(channel("a", tvgId = "a.us")))
        // A source but nothing to match WITH: no id, no tvg-name, blank name.
        assertNull(
            guideMatchQueryFor(
                LiveChannelZapRegistry.ZapChannel(
                    channelId = "a",
                    name = "",
                    streamUrl = "http://host/a",
                    logoUrl = null,
                    epgUrl = "http://epg/one"
                )
            )
        )
        // A name alone is identity enough, so this one DOES produce a query.
        assertTrue(guideMatchQueryFor(channel("a", epgUrl = "http://epg/one")) != null)
    }

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

    // ── Multiple configured guide sources ──────────────────────────────
    //
    // Reported: "the in player guide still says alot of no guide data even
    // though the regular guide in guide screen is fully populated". The screen
    // reads EVERY configured source and merges; the player asked only the
    // primary one, so a channel whose programs live under a secondary guide
    // read as unmatched - and a device whose only guide is a secondary URL read
    // as having no guide at all.

    private fun multiSourceChannel(
        id: String = "a",
        epgChannelId: String? = "id.a",
        epgUrls: List<String> = listOf("http://epg/primary", "http://epg/secondary"),
        epgUrl: String? = null,
        tvgId: String? = null
    ) = LiveChannelZapRegistry.ZapChannel(
        channelId = id,
        name = id,
        streamUrl = "http://host/$id",
        logoUrl = null,
        epgChannelId = epgChannelId,
        epgUrl = epgUrl,
        epgUrls = epgUrls,
        tvgId = tvgId
    )

    @Test
    fun `every published source is queried for a matched channel`() {
        assertEquals(
            listOf(
                GuideQuery("http://epg/primary", listOf("id.a")),
                GuideQuery("http://epg/secondary", listOf("id.a"))
            ),
            planGuideQueries(listOf(multiSourceChannel()))
        )
    }

    @Test
    fun `a channel with no single source but a published list still plans a read`() {
        // The reported setup: the guide comes from a secondary source, so the
        // legacy single-value field is empty.
        assertEquals(
            listOf(
                GuideQuery("http://epg/primary", listOf("id.a")),
                GuideQuery("http://epg/secondary", listOf("id.a"))
            ),
            planGuideQueries(listOf(multiSourceChannel(epgUrl = null)))
        )
    }

    @Test
    fun `the source list is trimmed, deduped and keeps the legacy value`() {
        assertEquals(
            listOf("http://epg/one", "http://epg/two"),
            guideSourcesOf(
                multiSourceChannel(
                    epgUrls = listOf(" http://epg/one ", "", "http://epg/one", "http://epg/two"),
                    epgUrl = "http://epg/two"
                )
            )
        )
        assertEquals(
            listOf("http://epg/one"),
            guideSourcesOf(multiSourceChannel(epgUrls = emptyList(), epgUrl = " http://epg/one "))
        )
        assertEquals(
            emptyList<String>(),
            guideSourcesOf(multiSourceChannel(epgUrls = emptyList(), epgUrl = null))
        )
    }

    @Test
    fun `an entry with only a published source list can still be matched`() {
        val entry = multiSourceChannel(
            epgChannelId = null,
            epgUrls = listOf("http://epg/primary"),
            tvgId = "a.us"
        )

        assertTrue(needsGuideMatch(entry))
        assertEquals(
            listOf("http://epg/primary"),
            guideMatchQueriesFor(entry).map { it.epgUrl }
        )
    }

    @Test
    fun `one match query is built per published source, primary first`() {
        val entry = multiSourceChannel(epgChannelId = null, tvgId = "a.us")

        assertEquals(
            listOf("http://epg/primary", "http://epg/secondary"),
            guideMatchQueriesFor(entry).map { it.epgUrl }
        )
        assertEquals("http://epg/primary", guideMatchQueryFor(entry)?.epgUrl)
    }

    @Test
    fun `a weaker name candidate still yields a query per source`() {
        val entry = multiSourceChannel(
            epgChannelId = null
        ).copy(tvgId = null, tvgName = "Channel A")

        assertEquals(2, guideMatchQueriesFor(entry).size)
        assertEquals(
            listOf("Channel A", "a"),
            guideMatchQueriesFor(entry).first().nameCandidates
        )
    }
}
