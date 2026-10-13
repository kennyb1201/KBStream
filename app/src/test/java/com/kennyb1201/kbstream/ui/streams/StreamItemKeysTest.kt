package com.kennyb1201.kbstream.ui.streams

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sources picker's lazy-list keys: unique always, stable per list, and
 * content-derived so focus follows a card rather than a position.
 *
 * Reported problem: two fully blank placeholder streams share the key "||||"
 * and a LazyColumn throws IllegalArgumentException on a duplicate key, taking
 * the screen down. The picker is documented to show those placeholders, and
 * StreamDedup.collapse leaves hash-less streams alone, so the state is
 * reachable. Repeated keys now get a counter suffix - and only repeats do.
 */
class StreamItemKeysTest {

    private fun blankPlaceholder() = Stream()

    private fun link(url: String, title: String = "Some Show S2E5", name: String = "Addon") =
        Stream(name = name, title = title, url = url)

    @Test
    fun `two fully blank placeholders are two distinct keys`() {
        val keys = StreamItemKeys.of(listOf(blankPlaceholder(), blankPlaceholder()))

        assertEquals(2, keys.size)
        assertEquals(
            "a duplicate key is the IllegalArgumentException the screen died on",
            2,
            keys.toSet().size
        )
        assertEquals(listOf("||||", "||||#2"), keys)
    }

    @Test
    fun `a third copy counts up again`() {
        val keys = StreamItemKeys.of(List(3) { blankPlaceholder() })

        assertEquals(listOf("||||", "||||#2", "||||#3"), keys)
    }

    @Test
    fun `the first source with a key keeps the plain key`() {
        val keys = StreamItemKeys.of(
            listOf(link("https://a.example/e5.mp4"), link("https://a.example/e5.mp4"))
        )

        assertEquals(
            listOf("https://a.example/e5.mp4|Some Show S2E5|Addon||", "https://a.example/e5.mp4|Some Show S2E5|Addon||#2"),
            keys
        )
    }

    @Test
    fun `a typo'd duplicate of an otherwise shared key is suffixed, not merged`() {
        // The report's exact shape: same addon, same title, two blank URLs.
        val keys = StreamItemKeys.of(
            listOf(
                link("", title = "Some Show S2E5", name = "Addon"),
                link("", title = "Some Show S2E5", name = "Addon")
            )
        )

        assertEquals(listOf("|Some Show S2E5|Addon||", "|Some Show S2E5|Addon||#2"), keys)
    }

    @Test
    fun `distinct sources never get a suffix`() {
        val keys = StreamItemKeys.of(
            listOf(
                link("https://a.example/e5.mp4"),
                link("https://b.example/e5.mp4"),
                link("https://c.example/e5.mp4")
            )
        )

        assertTrue("no repeats, no counters", keys.none { it.contains('#') })
        assertEquals("a key is the source's own metadata", StreamItemKeys.base(
            link("https://b.example/e5.mp4")
        ), keys[1])
    }

    @Test
    fun `the same list in the same order gives the same keys`() {
        val streams = listOf(
            blankPlaceholder(),
            link("https://a.example/e5.mp4"),
            blankPlaceholder()
        )

        assertEquals(StreamItemKeys.of(streams), StreamItemKeys.of(streams))
    }

    @Test
    fun `a reorder keeps every distinct source's key`() {
        // Rank order can change between resolves; a content-derived key travels
        // with the card, so focus is not thrown onto a different source.
        val a = link("https://a.example/e5.mp4")
        val b = link("https://b.example/e5.mp4")
        val c = link("https://c.example/e5.mp4")

        val before = StreamItemKeys.of(listOf(a, b, c))
        val after = StreamItemKeys.of(listOf(c, a, b))

        assertEquals(before[0], after[1])
        assertEquals(before[1], after[2])
        assertEquals(before[2], after[0])
    }

    @Test
    fun `an empty picker has no keys`() {
        assertEquals(emptyList<String>(), StreamItemKeys.of(emptyList()))
    }

    @Test
    fun `the file index is part of the key`() {
        // Two files inside one multi-file torrent are two rows; the same hash
        // at another index must not fold onto one key.
        val episode = Stream(infoHash = "ABCDEF", fileIdx = 0)
        val sample = Stream(infoHash = "ABCDEF", fileIdx = 1)

        val keys = StreamItemKeys.of(listOf(episode, sample))

        assertEquals(2, keys.toSet().size)
        assertTrue(keys[0].endsWith("|0"))
        assertTrue(keys[1].endsWith("|1"))
    }
}
