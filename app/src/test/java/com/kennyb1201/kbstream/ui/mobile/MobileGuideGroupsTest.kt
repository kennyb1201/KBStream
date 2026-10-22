package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone guide's group rules, tested by behaviour.
 *
 * These decide what the chips say and which channels each one shows, so they
 * are the same kind of rule the TV guide's `GuideRules` hold: pure, and worth a
 * test, because every edge case here is visible to a viewer - a missing Recent
 * chip, a Favorites chip that opens empty, a group that cannot be reached.
 */
class MobileGuideGroupsTest {

    private fun channel(
        id: String,
        name: String,
        group: String? = null,
        streamUrl: String = "https://example.com/$id.m3u8"
    ): IptvChannelWithEpg = IptvChannelWithEpg(
        channel = IptvChannel(
            id = id,
            name = name,
            displayName = name,
            streamUrl = streamUrl,
            groupTitle = group,
            logoUrl = null,
            tvgId = null,
            tvgName = null,
            tvgChno = null,
            catchup = null,
            catchupDays = null,
            catchupSource = null,
            providerChannelId = null
        ),
        epgChannel = null,
        now = null,
        next = null
    )

    private val lineup = listOf(
        channel("1", "BBC One", "News"),
        channel("2", "BBC Two", "News"),
        channel("3", "Sky Sports", "Sports"),
        channel("4", "Cartoon Network", "Kids")
    )

    @Test
    fun `chips are All then Recent then Favorites then the playlist groups`() {
        assertEquals(
            listOf("All", "News", "Sports", "Kids"),
            guideGroupLabels(lineup, favorites = emptySet(), recentKeys = emptyList())
        )
        assertEquals(
            listOf("All", "Recent", "Favorites", "News", "Sports", "Kids"),
            guideGroupLabels(
                lineup,
                favorites = setOf("1"),
                recentKeys = listOf("3")
            )
        )
    }

    @Test
    fun `a group title is never listed twice and blank titles are skipped`() {
        val channels = listOf(
            channel("1", "A", "News"),
            channel("2", "B", "  News  "),
            channel("3", "C", "   "),
            channel("4", "D", null)
        )
        assertEquals(
            listOf("All", "News"),
            guideGroupLabels(channels, emptySet(), emptyList())
        )
    }

    @Test
    fun `a playlist group named like a reserved chip is not emitted twice`() {
        // A provider is free to group its channels "All" (or "Recent" /
        // "Favorites"). The reserved chips are already emitted, so the group
        // must add nothing - a duplicate label is a duplicate key in the
        // guide's LazyRow, which throws.
        val channels = listOf(
            channel("1", "A", "All"),
            channel("2", "B", "Favorites"),
            channel("3", "C", "Recent")
        )
        assertEquals(
            listOf("All"),
            guideGroupLabels(channels, favorites = emptySet(), recentKeys = emptyList())
        )
        assertEquals(
            listOf("All", "Recent", "Favorites"),
            guideGroupLabels(channels, favorites = setOf("1"), recentKeys = listOf("2"))
        )
    }

    @Test
    fun `each chip resolves its own members`() {
        val favorites = setOf("2")
        val recent = listOf("3", "1")

        assertEquals(lineup, guideGroupChannels(lineup, "All", favorites, recent))
        assertEquals(
            listOf(lineup[1]),
            guideGroupChannels(lineup, "Favorites", favorites, recent)
        )
        assertEquals(
            listOf(lineup[2], lineup[0]),
            guideGroupChannels(lineup, "Recent", favorites, recent)
        )
        assertEquals(
            listOf(lineup[2]),
            guideGroupChannels(lineup, "Sports", favorites, recent)
        )
    }

    @Test
    fun `a group the lineup no longer has resolves empty rather than throwing`() {
        assertTrue(guideGroupChannels(lineup, "Gone", emptySet(), emptyList()).isEmpty())
        // A recent key whose channel was removed drops out instead of rendering
        // a hole.
        assertEquals(
            listOf(lineup[0]),
            guideGroupChannels(lineup, "Recent", emptySet(), listOf("missing", "1"))
        )
    }

    @Test
    fun `playing a channel moves it to the front of the recent queue`() {
        assertEquals(
            listOf("3", "1", "2"),
            nextRecentKeys(listOf("1", "2", "3"), "3")
        )
        assertEquals(
            "playing the same channel twice must not duplicate it",
            listOf("1", "2"),
            nextRecentKeys(listOf("1", "2"), "1")
        )
        assertEquals(
            "the queue is capped",
            listOf("1", "2"),
            nextRecentKeys(listOf("2", "3"), "1", cap = 2)
        )
        assertEquals(
            "a blank key is not a play",
            listOf("1"),
            nextRecentKeys(listOf("1"), "")
        )
    }

    @Test
    fun `a channel with no id is identified by its stream url`() {
        val noId = channel("", "Odd channel", "News")
        assertEquals(noId.channel.streamUrl, guideChannelKey(noId))
        assertEquals(
            listOf(noId),
            guideGroupChannels(listOf(noId), "Favorites", setOf(noId.channel.streamUrl), emptyList())
        )
    }
}
