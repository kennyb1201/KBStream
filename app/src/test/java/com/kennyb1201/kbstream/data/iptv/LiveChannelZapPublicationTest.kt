package com.kennyb1201.kbstream.data.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lineup's publication counter, which is how the in-player guide notices
 * that the Guide screen's matching has landed.
 *
 * The overlay's watcher used to key its re-read only to `GuideRevision`, and
 * that is bumped by a successful IMPORT alone. The Guide screen matches playlist
 * channels to guide channels in batches as the viewer scrolls - that is not an
 * import - so an overlay opened before the matching finished had no signal to
 * re-read and stayed "No guide data" for the session (field report 2026-11-12,
 * build 262). This counter is that signal: any publication, matching included,
 * moves it.
 */
class LiveChannelZapPublicationTest {

    private fun channel(id: String, epgId: String? = null) =
        LiveChannelZapRegistry.ZapChannel(
            channelId = id,
            name = id,
            streamUrl = "http://example.test/$id.ts",
            logoUrl = null,
            epgChannelId = epgId
        )

    @After
    fun tearDown() {
        LiveChannelZapRegistry.clear()
    }

    @Test
    fun `publishing a lineup moves the counter`() {
        val before = LiveChannelZapRegistry.publication()
        LiveChannelZapRegistry.set(listOf(channel("a"), channel("b")))
        val after = LiveChannelZapRegistry.publication()

        assertTrue("a publication must be observable", after > before)
    }

    @Test
    fun `republishing after the matches fill in moves it again`() {
        // The exact sequence the overlay depends on: the lineup goes out with no
        // guide ids (matching had not run), then the same lineup goes out again
        // with them filled in. The second publication is the only signal that
        // the data the overlay was waiting for has arrived.
        LiveChannelZapRegistry.set(listOf(channel("a"), channel("b")))
        val unmatched = LiveChannelZapRegistry.publication()

        assertTrue(
            "nothing to resolve yet",
            LiveChannelZapRegistry.snapshot().all { it.epgChannelId == null }
        )

        LiveChannelZapRegistry.set(listOf(channel("a", "guide.a"), channel("b", "guide.b")))
        val matched = LiveChannelZapRegistry.publication()

        assertTrue("the matched republication is a new publication", matched > unmatched)
        assertEquals(
            listOf("guide.a", "guide.b"),
            LiveChannelZapRegistry.snapshot().map { it.epgChannelId }
        )
        assertTrue(
            "and the counter is the only thing a reader needs to compare",
            matched - unmatched == 1L
        )
    }

    @Test
    fun `publishing groups moves it too`() {
        val before = LiveChannelZapRegistry.publication()
        LiveChannelZapRegistry.setGroups(
            groups = listOf(
                LiveChannelZapRegistry.ZapGroup("All", listOf(channel("a"))),
                LiveChannelZapRegistry.ZapGroup("News", listOf(channel("b")))
            ),
            selected = "News"
        )
        assertTrue(LiveChannelZapRegistry.publication() > before)
    }

    @Test
    fun `an empty publication still counts, and clearing counts`() {
        val before = LiveChannelZapRegistry.publication()
        // The guide screen deliberately skips publishing an empty lineup, but if
        // one arrives it is still a publication: the overlay must re-read rather
        // than sit on the previous group's matches.
        LiveChannelZapRegistry.setGroups(emptyList())
        val afterEmpty = LiveChannelZapRegistry.publication()
        assertTrue(afterEmpty > before)

        LiveChannelZapRegistry.set(listOf(channel("a")))
        LiveChannelZapRegistry.clear()
        assertTrue(
            "leaving the player must invalidate what a reader saw",
            LiveChannelZapRegistry.publication() > afterEmpty
        )
    }

    @Test
    fun `reading the counter does not move it`() {
        LiveChannelZapRegistry.set(listOf(channel("a")))
        val first = LiveChannelZapRegistry.publication()
        assertEquals(first, LiveChannelZapRegistry.publication())
        LiveChannelZapRegistry.snapshot()
        LiveChannelZapRegistry.size()
        assertEquals(first, LiveChannelZapRegistry.publication())
    }
}
