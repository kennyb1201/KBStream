package com.kennyb1201.kbstream.ui.kb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the collection rail actually applies the rules [CollectionRailTilesTest]
 * pins: the filter runs before the list, the key is positional, and every item
 * emits a tile.
 *
 * Read from the source: these are Compose `itemsIndexed` arguments, a
 * `remember`, and a modifier chain, which need a device, a remote and a
 * handful of taps (or a Compose UI harness this module does not carry) to
 * exercise directly.
 */
class CollectionRailWiringContractTest {

    private val source: String by lazy {
        File(findMainSourceRoot(), SLOTS).readText()
    }

    /** The rail's own body, from its declaration to the tile composable after it. */
    private val rail: String by lazy {
        source.substringAfter("fun KBHomeCollectionRail(")
            .substringBefore("private fun CollectionFolderTile(")
    }

    @Test
    fun `the rail drops null-id folders before the list, not inside the item`() {
        assertTrue(
            "the tiles have to be computed before the LazyRow, from the rule",
            rail.contains("collectionRailTiles(collection.folders)")
        )
        assertTrue(
            "and memoised against the collection so the list is not rebuilt per frame",
            rail.contains("remember(collection) { collectionRailTiles(collection.folders) }")
        )
        assertTrue(
            "the null-id filter must not still live in the item block - that is " +
                "what left an invisible, focusable slot in the rail",
            !rail.contains("if (folderId != null)") &&
                !rail.contains("if (folder.id != null)")
        )
    }

    @Test
    fun `the key is positional and unique`() {
        assertTrue(
            "itemsIndexed, so the key can include the position",
            rail.contains("itemsIndexed(")
        )
        assertTrue(
            rail.contains("key = { index, tile -> collectionRailKey(index, tile) }")
        )
        assertFalse(
            "the old `id ?: title` key collides and must not come back",
            source.contains("key = { it.id ?: it.title }")
        )
    }

    @Test
    fun `every item emits exactly one tile with one focus callback`() {
        val block = rail.substringAfter("key = { index, tile -> collectionRailKey(index, tile) }")
        assertEquals(
            "one tile per item - a second call site would be a second code path",
            1,
            Regex("CollectionFolderTile\\(").findAll(block).count()
        )
        assertEquals(
            "the hero update fires once per tile, from the tile's own focus",
            1,
            Regex("onFocus =").findAll(block).count()
        )
        assertFalse(
            "no branch may sit inside the item block: every position is a tile",
            block.substringBefore("CollectionFolderTile(").contains("if (")
        )
    }

    @Test
    fun `the tile keeps its focus requester and the rail's up hook`() {
        val block = rail.substringAfter("key = { index, tile -> collectionRailKey(index, tile) }")
        assertTrue(
            "a fresh requester per item, correctly scoped because the key is unique",
            block.contains("val requester = remember { FocusRequester() }")
        )
        assertTrue(
            "homeTopRailUpHook wiring is part of this rail",
            block.contains("homeTopRailUpHook(requester, onUpPressed)")
        )
        assertTrue(
            "the tile's click target is the non-null id the rule carried through",
            block.contains("onClick = { onOpenFolder(folderId) }")
        )
    }

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
