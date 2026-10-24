package com.kennyb1201.kbstream.data.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The removal record's wiring, read from the source.
 *
 * [LibraryTombstonesTest] pins the rule; this pins the three places the field
 * depends on it actually being used, because each is a one-line omission that no
 * single-unit test can see:
 *
 *  - the two removal paths that answer "the sync put this here" (the cleanup and
 *    the untoggle question) write the record as the rows leave;
 *  - the payload CARRIES it and the applier MERGES it before the timestamp gate
 *    that lets a stale list replace the local one - merging after the gate (or
 *    as part of the blob) is the field's war: phone removes, TV pushes its older
 *    list, the removal is gone;
 *  - a sync round consults it, after the cleanup, so a re-processed cloud (a
 *    Resync wipes every mark) cannot re-add what was deliberately removed.
 *
 * Read from source: these are preference writes, a payload apply and a round's
 * loop, which need a device, an account and two clocks to press.
 */
class LibraryTombstoneWiringContractTest {

    private fun source(relativePath: String): String =
        normalized(stripComments(File(findMainSourceRoot(), relativePath).readText()))

    @Test
    fun `both removal paths write the record as the rows leave`() {
        val store = source(LIBRARY_MODELS)

        val toggle = store.substringAfter("fun removeTorboxSynced(context: Context): Int {")
            .substringBefore("fun removeMyListEntries(")
        assertTrue(
            "the untoggle's \"yes\" remembers what it removed, or the next pull " +
                "puts it back",
            toggle.contains("markTombstoned(context, leaving)")
        )
        assertTrue(
            "and it names the rows that are leaving, before the list is written",
            toggle.contains("val leaving = items.filter { it.origin == LibraryOrigin.TORBOX_SYNC }") &&
                toggle.indexOf("markTombstoned(context, leaving)") <
                toggle.indexOf("writeList(context, KEY_MY_LIST, kept)")
        )

        val cleanup = store.substringAfter("fun removeMyListEntries(")
            .substringBefore("fun tombstones(")
        assertTrue(
            "the ownership cleanup does the same, so its removal survives the other " +
                "device's next round",
            cleanup.contains("val leaving = items.filter { dedupeKey(it) in doomed }") &&
                cleanup.contains("markTombstoned(context, leaving)")
        )
    }

    @Test
    fun `a manual re-add clears the record, and the sync's own add does not`() {
        val store = source(LIBRARY_MODELS)
        val add = store.substringAfter("private fun addToListInternal(")
            .substringBefore("private fun removeFromListInternal(")

        assertTrue(
            "the clear is asked only for a MANUAL add: a sync add that matches a " +
                "removed title must leave the removal standing",
            add.contains("if (origin == LibraryOrigin.MANUAL) {") &&
                add.contains("clearTombstonesFor(context, mediaType, imdbId, tmdbId)")
        )
        assertTrue(
            "and it is a clearance entry rather than a deletion, so the other " +
                "device's older removal cannot undo it on the next merge",
            store.contains("fun clearTombstonesFor(") &&
                store.contains("updated[key] = LibraryTombstone(key, at, removed = false)")
        )
    }

    @Test
    fun `the payload carries the record and the pull merges it before the gate`() {
        val payload = source(SYNC_PREFS)
        val build = payload.substringAfter("fun buildLibrary(context: Context): JsonObject =")
            .substringBefore("/**")
        assertTrue(
            "the record travels beside the lists",
            build.contains("LibraryTombstones.PAYLOAD_KEY")
        )

        val apply = payload.substringAfter("private fun applyLibrary(")
            .substringBefore("private fun applyDismissals(")
        assertTrue(
            "the merge happens before the timestamp gate can return, or a stale " +
                "device's push wins the list AND the removal",
            apply.contains("LocalLibraryStore.mergePulledTombstones(") &&
                apply.indexOf("mergePulledTombstones(") < apply.indexOf("if (!remoteIsStale) {")
        )
        assertTrue(
            "and the list that is stored is filtered against the merged record " +
                "either way: the pulled blob when the pull wins, the local one when " +
                "it does not",
            apply.contains("LocalLibraryStore.withoutTombstonedRows(source, tombstones)") &&
                apply.contains("?: LocalLibraryStore.myListBlob(context, profileId)")
        )
        assertFalse(
            "and it is made unconditionally - nothing gates it, because a removal " +
                "is not part of the blob race the clock decides",
            apply.substringBefore("mergePulledTombstones(").contains("if (")
        )
    }

    @Test
    fun `a round consults the record after the cleanup and says how many it skipped`() {
        val sync = source(TORBOX_SYNC)

        assertTrue(
            "the round reads the record AFTER the cleanup's own removals, so it " +
                "cannot re-add what that pass just removed",
            sync.contains("val tombstones = LocalLibraryStore.tombstones(context)") &&
                sync.indexOf("remediateOwnership(context, store, liveProfileId, torrents)") <
                sync.indexOf("val tombstones = LocalLibraryStore.tombstones(context)")
        )
        assertTrue(
            "every matched torrent is checked by ids - TMDB's alone is enough, since " +
                "the record's own key can name both",
            sync.contains("LibraryTombstones.isRemoved(tombstones, match.mediaType, imdbId, match.tmdbId)") &&
                sync.indexOf("LibraryTombstones.isRemoved(") <
                sync.indexOf("LibraryMirror.addToLibrary(")
        )
        assertTrue(
            "and the skip is counted, so a cloud full of torrents adding nothing " +
                "says why",
            sync.contains("tombstoneSkips = tombstoneSkips") &&
                sync.contains("${'$'}{report.tombstoneSkips} removed on purpose elsewhere")
        )
        assertFalse(
            "a tombstoned torrent is not marked handled: the clearance path has to " +
                "be able to put it back without a Resync",
            sync.substringAfter("LibraryTombstones.isRemoved(")
                .substringBefore("continue")
                .contains("markProcessed")
        )
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

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
        const val LIBRARY_MODELS = "com/kennyb1201/kbstream/data/library/LibraryModels.kt"
        const val SYNC_PREFS = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val TORBOX_SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
    }
}
