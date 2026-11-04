package com.kennyb1201.kbstream.ui.streams

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping around the cached-only rule (spec T2).
 *
 * Two lists are published side by side and a third (the add-on tabs) restates
 * the first. All three have to agree once the filter removes a row: the merged
 * list, the add-on name of each surviving row, and every non-empty tab.
 */
class CachedOnlyVisibleTest {

    private companion object {
        const val CACHED_HASH = "0123456789abcdef0123456789abcdef01234567"
        const val UNCACHED_HASH = "89abcdef0123456789abcdef0123456789abcdef"
    }

    private fun hashRow(name: String, hash: String) = Stream(
        name = "Penguplay",
        title = name,
        infoHash = hash
    )

    private fun directRow(name: String) = Stream(
        name = "AIOStreams",
        title = name,
        url = "https://cdn.example/$name.mkv"
    )

    @Test
    fun `keptIndices reads positions, in order`() {
        val rows = listOf(
            hashRow("cached", CACHED_HASH),
            hashRow("uncached", UNCACHED_HASH),
            directRow("direct")
        )
        assertEquals(
            listOf(0, 2),
            CachedOnlyVisible.keptIndices(rows, setOf(CACHED_HASH))
        )
    }

    @Test
    fun `the add-on names thin with their rows`() {
        val rows = listOf(
            hashRow("cached", CACHED_HASH),
            hashRow("uncached", UNCACHED_HASH),
            directRow("direct")
        )
        val addons = listOf("Penguplay", "Comet", "AIOStreams")
        val kept = CachedOnlyVisible.keptIndices(rows, setOf(CACHED_HASH))
        assertEquals(
            "a name left where its row was removed slides onto the next add-on's " +
                "source, and the player then blames the wrong add-on",
            listOf("Penguplay", "AIOStreams"),
            CachedOnlyVisible.alongside(addons, rows.size, kept)
        )
    }

    @Test
    fun `an unaligned name list is left alone rather than invented`() {
        val rows = listOf(hashRow("cached", CACHED_HASH), directRow("direct"))
        val kept = CachedOnlyVisible.keptIndices(rows, setOf(CACHED_HASH))
        assertEquals(
            "the progressive paint publishes rows before their add-ons are known; " +
                "a shorter list must stay as it is",
            emptyList<String>(),
            CachedOnlyVisible.alongside(emptyList<String>(), rows.size, kept)
        )
    }

    @Test
    fun `a group keeps its own surviving rows in its own order`() {
        val comet = StreamAddonGroup(
            addonName = "Comet",
            streams = listOf(
                hashRow("cached", CACHED_HASH),
                hashRow("uncached", UNCACHED_HASH)
            )
        )
        val filtered = CachedOnlyVisible.groups(listOf(comet), setOf(CACHED_HASH))
        assertEquals(1, filtered.size)
        assertEquals("Comet", filtered.single().addonName)
        assertEquals(listOf(hashRow("cached", CACHED_HASH)), filtered.single().streams)
    }

    @Test
    fun `a group the filter empties is dropped, not left as an empty tab`() {
        val allUncached = StreamAddonGroup(
            addonName = "Comet",
            streams = listOf(hashRow("a", UNCACHED_HASH), hashRow("b", UNCACHED_HASH))
        )
        val directOnly = StreamAddonGroup(
            addonName = "AIOStreams",
            streams = listOf(directRow("link"))
        )
        val filtered = CachedOnlyVisible.groups(listOf(allUncached, directOnly), setOf(CACHED_HASH))
        assertEquals(
            "a tab of nothing but uncached rows is noise, not information",
            listOf("AIOStreams"),
            filtered.map { it.addonName }
        )
    }

    @Test
    fun `the merged list and the groups hold the same rows in the same order`() {
        val rows = listOf(
            hashRow("cached", CACHED_HASH),
            hashRow("uncached", UNCACHED_HASH),
            directRow("direct")
        )
        val addons = listOf("Comet", "Comet", "AIOStreams")
        val kept = CachedOnlyVisible.keptIndices(rows, setOf(CACHED_HASH))
        val merged = kept.map { rows[it] }
        val names = CachedOnlyVisible.alongside(addons, rows.size, kept)

        val groups = CachedOnlyVisible.groups(
            listOf(
                StreamAddonGroup("Comet", listOf(rows[0], rows[1])),
                StreamAddonGroup("AIOStreams", listOf(rows[2]))
            ),
            setOf(CACHED_HASH)
        )
        val fromGroups = groups.flatMap { group ->
            group.streams.map { group.addonName to it }
        }
        assertEquals(
            "the tabs are a view of All, not a second opinion about it",
            merged.zip(names).map { (stream, name) -> name to stream },
            fromGroups
        )
    }

    @Test
    fun `a group whose rows are all direct keeps every one of them`() {
        val group = StreamAddonGroup(
            "AIOStreams",
            listOf(directRow("a"), directRow("b"))
        )
        val filtered = CachedOnlyVisible.groups(listOf(group), emptySet())
        assertEquals(2, filtered.single().streams.size)
    }

    @Test
    fun `no groups in, no groups out`() {
        assertTrue(CachedOnlyVisible.groups(emptyList(), setOf(CACHED_HASH)).isEmpty())
    }
}
