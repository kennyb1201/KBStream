package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zap registry carries every browsable group, so the in-player guide can
 * step between them with LEFT/RIGHT and then zap within the one it landed on.
 *
 * Reported: "make it where the in player iptv guide we can change groups with
 * left and right dpad press". The registry used to hold only the group the
 * player was launched from, so there was nothing to step to.
 *
 * These are the pure rules behind that; the wiring that presses them lives in
 * the player and is pinned by PlayerGuideGroupSwitchContractTest.
 */
class LiveChannelZapGroupsTest {

    private fun channel(id: String) = LiveChannelZapRegistry.ZapChannel(
        channelId = id,
        name = id,
        streamUrl = "http://host/$id",
        logoUrl = null
    )

    private fun group(label: String, vararg ids: String) =
        LiveChannelZapRegistry.ZapGroup(label, ids.map(::channel))

    private fun setUpGroups() {
        LiveChannelZapRegistry.setGroups(
            groups = listOf(
                group("All", "a1", "a2", "a3"),
                group("News", "n1", "n2"),
                group("Sports", "s1")
            ),
            selected = "News"
        )
    }

    @Test
    fun `the selected group is the lineup that is browsed`() {
        setUpGroups()

        assertEquals("News", LiveChannelZapRegistry.browsingGroup())
        assertEquals(2, LiveChannelZapRegistry.size())
        assertEquals("n1", LiveChannelZapRegistry.channelAt(0)?.channelId)
        assertTrue(LiveChannelZapRegistry.zapEnabled())
    }

    @Test
    fun `left and right step through the groups and their lineups`() {
        setUpGroups()

        assertTrue(LiveChannelZapRegistry.offsetGroup(1))
        assertEquals("Sports", LiveChannelZapRegistry.browsingGroup())
        assertEquals(listOf("s1"), (0 until LiveChannelZapRegistry.size()).mapNotNull {
            LiveChannelZapRegistry.channelAt(it)?.channelId
        })

        assertTrue(LiveChannelZapRegistry.offsetGroup(-1))
        assertEquals("News", LiveChannelZapRegistry.browsingGroup())

        assertTrue(LiveChannelZapRegistry.offsetGroup(-1))
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())
        assertEquals(3, LiveChannelZapRegistry.size())
    }

    @Test
    fun `a press at either end does not wrap`() {
        setUpGroups()

        // Already at "All".
        assertTrue(LiveChannelZapRegistry.offsetGroup(-1))
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())

        assertFalse(
            "a press with nowhere to go must report no change, so the overlay " +
                "is not repainted and the guide is not re-read",
            LiveChannelZapRegistry.offsetGroup(-1)
        )

        assertTrue(LiveChannelZapRegistry.offsetGroup(1))
        assertTrue(LiveChannelZapRegistry.offsetGroup(1))
        assertEquals("Sports", LiveChannelZapRegistry.browsingGroup())
        assertFalse(LiveChannelZapRegistry.offsetGroup(1))
    }

    @Test
    fun `a single group has nowhere to step`() {
        LiveChannelZapRegistry.setGroups(listOf(group("All", "a1", "a2")), selected = "All")

        assertFalse(LiveChannelZapRegistry.offsetGroup(1))
        assertFalse(LiveChannelZapRegistry.offsetGroup(-1))
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())
    }

    @Test
    fun `the header can say where in the group list the viewer is`() {
        setUpGroups()

        assertEquals(2 to 3, LiveChannelZapRegistry.groupPosition())
        LiveChannelZapRegistry.offsetGroup(-1)
        assertEquals(1 to 3, LiveChannelZapRegistry.groupPosition())
    }

    @Test
    fun `an unknown or blank selection falls back to the first group`() {
        setUpGroups()

        LiveChannelZapRegistry.setGroups(
            groups = listOf(group("All", "a1"), group("News", "n1")),
            selected = "Movies"
        )
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())
        assertEquals(1 to 2, LiveChannelZapRegistry.groupPosition())

        LiveChannelZapRegistry.setGroups(
            groups = listOf(group("All", "a1"), group("News", "n1")),
            selected = "   "
        )
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())

        LiveChannelZapRegistry.setGroups(
            groups = listOf(group("All", "a1"), group("News", "n1")),
            selected = null
        )
        assertEquals("All", LiveChannelZapRegistry.browsingGroup())
    }

    @Test
    fun `a group with no channels is not a stop`() {
        LiveChannelZapRegistry.setGroups(
            groups = listOf(
                group("All", "a1"),
                LiveChannelZapRegistry.ZapGroup("Empty", emptyList()),
                group("News", "n1")
            ),
            selected = "All"
        )

        assertEquals(listOf("All", "News"), LiveChannelZapRegistry.groupLabels())
        assertTrue(LiveChannelZapRegistry.offsetGroup(1))
        assertEquals("News", LiveChannelZapRegistry.browsingGroup())
    }

    @Test
    fun `the single-group publish still works`() {
        // MainActivity and older callers publish one group.
        LiveChannelZapRegistry.set(
            channels = listOf(channel("a1"), channel("a2")),
            browsingGroup = "Favorites"
        )

        assertEquals("Favorites", LiveChannelZapRegistry.browsingGroup())
        assertEquals(2, LiveChannelZapRegistry.size())
        assertFalse(LiveChannelZapRegistry.offsetGroup(1))
        assertEquals(1 to 1, LiveChannelZapRegistry.groupPosition())
    }

    @Test
    fun `an empty publish empties the lineup`() {
        setUpGroups()
        LiveChannelZapRegistry.set(emptyList())

        assertEquals(0, LiveChannelZapRegistry.size())
        assertNull(LiveChannelZapRegistry.browsingGroup())
        assertNull(LiveChannelZapRegistry.groupPosition())
        assertFalse(LiveChannelZapRegistry.zapEnabled())
        assertNull(LiveChannelZapRegistry.channelAt(0))
    }

    @Test
    fun `clearing forgets the groups too`() {
        setUpGroups()
        LiveChannelZapRegistry.clear()

        assertEquals(emptyList<String>(), LiveChannelZapRegistry.groupLabels())
        assertNull(LiveChannelZapRegistry.groupPosition())
        assertFalse(LiveChannelZapRegistry.zapEnabled())
    }

    @Test
    fun `a typed channel number resolves inside the browsed group only`() {
        LiveChannelZapRegistry.setGroups(
            groups = listOf(
                LiveChannelZapRegistry.ZapGroup(
                    "All",
                    listOf(
                        channel("a1").copy(chno = "1"),
                        channel("a2").copy(chno = "2")
                    )
                ),
                LiveChannelZapRegistry.ZapGroup(
                    "News",
                    listOf(channel("n1").copy(chno = "7"))
                )
            ),
            selected = "News"
        )

        // Inside the browsed group.
        assertEquals(0, LiveChannelZapRegistry.indexOfChannelNumber("7"))
        // A number from another group's lineup is not reachable from here.
        assertEquals(-1, LiveChannelZapRegistry.indexOfChannelNumber("1"))
    }
}
