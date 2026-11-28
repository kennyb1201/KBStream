package com.kennyb1201.kbstream.data.kb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of the per-rail Home sync, pinned at the source (spec 2026-11-27).
 *
 * The rule itself is unit tested ([HomeOrderMergeTest]); what a source pin adds
 * is that the app actually USES it - every edit names the rail it placed, a
 * press that changed nothing claims no edit, and a pull merges instead of
 * dropping an older blob whole. This module has no Compose or Hilt harness, so
 * the call sites are pinned the way the rest of the tree pins them.
 */
class HomeOrderPerRailSyncContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val STORE = PACKAGE + "data/kb/KBHomeOrderPrefs.kt"
        const val MERGE = PACKAGE + "data/kb/KBHomeOrderMerge.kt"
        const val APPLIER = PACKAGE + "data/sync/SyncPrefsPayload.kt"
        const val TRACE = PACKAGE + "data/sync/HomeOrderSyncTrace.kt"
        const val MANAGER = PACKAGE + "ui/addons/AddonsViewModel.kt"
        const val BUILDER = PACKAGE + "ui/addons/CatalogBuilderViewModel.kt"
    }

    @Test
    fun `the blob carries a per-rail placement time and the viewer's edit stamps it`() {
        val store = source(STORE)
        assertTrue(
            "the arrangement itself carries the map, so parse, encode, reset and push " +
                "all carry it without a second store",
            store.contains("val editedAt: Map<String, Long> = emptyMap()")
        )
        assertTrue(
            "save names the rails the edit acted on",
            store.contains("touched: Set<String> = emptySet()")
        )
        assertTrue(
            "and stamps exactly those, through the pure rule",
            store.contains("stampRailEdits(value, touched, System.currentTimeMillis())")
        )
        assertTrue(
            "and only a viewer edit moves the blob's own stamp",
            store.contains("if (stampAsEdit) editor.putLong(SYNCED_AT_KEY, System.currentTimeMillis())")
        )
    }

    @Test
    fun `every placement edit names its rail and a rename names none`() {
        val manager = source(MANAGER)
        // Pin, both hide toggles (the manager's and the built-in's), and the
        // shared reorder core: each acts on exactly one rail key.
        assertTrue(manager.contains("persistHomeOrder(setOf(key)) { prefs -> toggleCollectionPin(prefs, key) }"))
        assertTrue(manager.contains("persistHomeOrder(setOf(key)) { prefs ->\n"))
        assertTrue(
            manager.contains(
                "persistHomeOrder(setOf(key)) { prefs -> KBHomeOrderPrefs.toggleBuiltinHidden(prefs, key) }"
            )
        )
        assertTrue(manager.contains("persistHomeOrder(setOf(key)) { prefs ->\n            moveRailInHomeColumn"))
        assertTrue(
            "a name is not a placement, so a rename stamps nothing",
            manager.contains("persistHomeOrder { prefs -> KBHomeOrderPrefs.withRename(prefs, key, name) }")
        )
        assertTrue(
            "the builder's hide switch is the same edit and names its rail too",
            source(BUILDER).contains("KBHomeOrderPrefs.save(context, updated, setOf(key))")
        )
    }

    @Test
    fun `a press that changed nothing writes nothing`() {
        val manager = source(MANAGER)
        assertTrue(
            "the no-op press must not stamp this device as the newest arrangement",
            manager.contains("val current = KBHomeOrderPrefs.get(context)") &&
                manager.contains("val updated = transform(current)") &&
                manager.contains("if (updated == current) return")
        )
    }

    @Test
    fun `a remap carries the stamps and still claims no edit`() {
        val store = source(STORE)
        assertTrue(
            "the map is rewritten with the key",
            store.contains("editedAt = stamps")
        )
        assertTrue(
            "and the write remains bookkeeping, not a viewer edit",
            store.contains("writeBlob(context, updated, stampAsEdit = false)")
        )
    }

    @Test
    fun `a pull merges per rail instead of dropping an older blob`() {
        val applier = source(APPLIER)
        assertTrue(
            "the pull is decided by the shared rule",
            applier.contains("pullHomeOrder(") &&
                applier.contains("remoteUpdated = remoteUpdated")
        )
        assertFalse(
            "an older blob is no longer dropped whole",
            applier.contains("The ORDER belongs to the newer copy and is dropped whole")
        )
        assertTrue(
            "the decision is the rule's outcome",
            applier.contains("decision = when (pull.outcome)") &&
                applier.contains("HomeOrderSyncTrace.REJECTED_STALE")
        )
        assertTrue(
            "and nothing is written when the merge changed nothing",
            applier.contains("if (!pull.changed) return")
        )
        assertTrue(
            "the merged blob carries the winning per-key times",
            applier.contains("encodeBlob(pull.order)") &&
                applier.contains("putLong(\"home_order_synced_at\", pull.stamp)")
        )
    }

    @Test
    fun `the trace vocabulary is unchanged`() {
        val trace = source(TRACE)
        listOf("PUBLISHED", "ADOPTED", "MERGED", "REJECTED_STALE", "BLANK_IGNORED")
            .forEach { decision ->
                assertTrue("$decision still exists", trace.contains("const val $decision = "))
            }
        assertFalse(
            "no new decision string was added for the merge",
            trace.contains("const val PARTIAL")
        )
        assertTrue("still bounded", trace.contains("MAX_EVENTS = 24"))
        // The line format is the acceptance instrument and must not move.
        assertTrue(
            trace.contains(
                "\"sync home-order: local=${'$'}{stamp(localStampMs)} \" +\n" +
                    "        \"pushed=${'$'}{stamp(pushedUpdatedAtMs)} pulled=${'$'}pulled\""
            )
        )
    }

    @Test
    fun `the per-key rule is the merge file's, not a second copy`() {
        val merge = source(MERGE)
        assertTrue(merge.contains("internal fun pullHomeOrder("))
        assertTrue(merge.contains("internal fun mergeHomeOrderAdopted("))
        assertTrue(merge.contains("internal fun stampRailEdits("))
        assertTrue(
            "the older-blob comparison has one home",
            merge.contains("remoteUpdated != null && remoteUpdated < localStamp")
        )
        assertFalse(
            "and it is not re-derived in the applier",
            source(APPLIER).contains("remoteUpdated < localStamp")
        )
    }
}
