package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring a rename needs to keep a Home rail honest, pinned from the sources
 * because none of it fails loudly on a device.
 *
 * The rules are behaviour-tested (`LibraryListRenameRulesTest`). What can go
 * missing silently is the plumbing: a rename that never reaches the pin, a pin
 * rewrite that runs after the failure path (so a refused rename moves a rail), a
 * refresh that does not prune, a service whose new id is thrown away, or a toggle
 * that reads one pin spelling and offers a second Add.
 */
class LibraryHomeRailRenameContractTest {

    private val root: File by lazy { findSourceRoot() }

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
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val viewModel =
        source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
    private val store =
        source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
    private val trakt = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val bingecat =
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val tvLibrary = source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
    private val phoneLibrary =
        source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")

    private fun renameBlock(): String {
        val start = viewModel.indexOf("fun renameList(list: LibraryList, name: String) {")
        assertTrue("renameList is missing", start >= 0)
        val end = viewModel.indexOf("private fun repinRenamedList(", start)
        assertTrue("repinRenamedList is missing", end > start)
        return squash(viewModel.substring(start, end))
    }

    private fun repinBlock(): String {
        val start = viewModel.indexOf("private fun repinRenamedList(")
        assertTrue("repinRenamedList is missing", start >= 0)
        val end = viewModel.indexOf("fun deleteRemoteList(", start)
        assertTrue("deleteRemoteList is missing", end > start)
        return squash(viewModel.substring(start, end))
    }

    private fun refreshBlock(): String {
        val start = viewModel.indexOf("private fun refreshListsOnly(")
        assertTrue("refreshListsOnly is missing", start >= 0)
        val end = viewModel.indexOf("/** One account's watchlist as a row", start)
        assertTrue("the watchlist row helper is missing", end > start)
        return squash(viewModel.substring(start, end))
    }

    @Test
    fun `a rename re-pins, and only once it has landed`() {
        val block = renameBlock()
        val failure = block.indexOf("if (renamed == null) {")
        val repin = block.indexOf("repinRenamedList(appContext, list, trimmed, renamed.remoteId)")
        assertTrue("the failure path is missing: $block", failure >= 0)
        assertTrue(
            "a refused rename must not move the rail: the failure returns before the re-pin",
            failure < repin
        )
        assertTrue(
            "and the pin goes by the identity the rename landed on, not one re-read later",
            block.contains("repinRenamedList(appContext, list, trimmed, renamed.remoteId)")
        )
        val pinBlock = repinBlock()
        assertTrue(
            "with the row's current id as the fallback when the source keeps it",
            pinBlock.contains("?: libraryHomeRailId(list)")
        )
        assertTrue(
            "the write is the pure rule on the old identity, one publish",
            pinBlock.contains("LibraryHomeRails.repinAfterRename(context, renamedPin, previousIds)")
        )
        assertTrue(
            "the old spellings include the service id the rename addressed...",
            pinBlock.contains("list.remoteId?.takeIf { it.isNotBlank() }?.let { add(it) }")
        )
        assertTrue(
            "...and the rail's numeric id, which an older pin may carry",
            pinBlock.contains("add(\"id:\" + list.id)")
        )
        assertTrue(
            "a name-only source still returns a landed outcome (the id simply did not change)",
            block.contains(").let { if (it) LibraryListRename() else null }")
        )
    }

    @Test
    fun `the rename's own refresh prunes the pins it just read`() {
        val block = refreshBlock()
        assertTrue(
            "the refresh a rename triggers has to reconcile, or the ghost outlives it: $block",
            block.contains("LibraryHomeRails.reconcilePins(appContext, liveForPrune)")
        )
        val live = block.substringAfter("val liveListIds = buildMap {")
            .substringBefore("val liveForPrune")
        assertTrue(
            "BingeCat prunes only on a snapshot this pass actually fetched",
            live.contains("bingecatSnapshot.refreshedAtMs >= bingecatReadAt") &&
                live.contains("LibrarySource.BINGECAT_LIST.name")
        )
        assertFalse(
            "MDBList's and Trakt's reads here omit their liked lists, so they cannot prune",
            live.contains("LibrarySource.TRAKT_LIST.name") ||
                live.contains("LibrarySource.MDBLIST_LIST.name")
        )
        assertFalse(
            "and local pins follow their tombstones, not a lists read",
            live.contains("LibrarySource.LOCAL_LIST.name")
        )
        assertTrue(
            "and the pin the rename just wrote is not pruned by a read that lagged it",
            block.contains(
                "liveListIds + (pin.source to (live + LibraryHomeRailLiveList(pin.id, setOf(pin.id))))"
            )
        )
        assertTrue(
            "the rename hands its new pin to that prune",
            renameBlock().contains("refreshListsOnly(keepPin = renamedPin)")
        )
    }

    @Test
    fun `the store rewrites one pin in one write, and never creates one`() {
        val repin = squash(store).substringAfter("fun repinAfterRename(")
            .substringBefore("fun removePins(")
        assertTrue(
            "the rewrite goes through the pure rule: $repin",
            repin.contains("val updated = repinnedAfterRename(current, renamed, previousIds)")
        )
        assertTrue(
            "an unchanged pin (or none at all) writes nothing",
            repin.contains("if (updated == current) return false")
        )
        assertTrue(
            "and the write is the one publish, so no sibling sees two rails mid-rename",
            repin.contains("write(context, updated)")
        )
        assertTrue(
            "Remove drops every spelling in one write too",
            squash(store).contains(
                "fun removePins(context: Context, spellings: Collection<LibraryHomeRailPin>)"
            )
        )
    }

    @Test
    fun `a service whose id follows the name tells us what it became`() {
        assertTrue(
            "Trakt derives the slug from the name, so the answer carries the new one",
            squash(trakt).contains("val slug = response.body()?.ids?.slug")
        )
        assertTrue(
            "BingeCat's patch answer carries the list it changed",
            squash(bingecat).contains("(answer as? BingeCatListResponse)?.list?.ref")
        )
        assertTrue(
            "and a rename that did not land answers null on both",
            squash(trakt).contains("if (!response.isSuccessful) return null") &&
                squash(bingecat).contains("if (!listWriteLanded(answer)) return null")
        )
    }

    @Test
    fun `both Libraries read every spelling a pin can have`() {
        listOf("tv" to tvLibrary, "phone" to phoneLibrary).forEach { (name, library) ->
            assertTrue(
                "$name: the toggle's on-Home must look at every pin that names the row",
                squash(library).contains("pinsNamingList(")
            )
            assertTrue(
                "$name: and Remove must drop all of them, plus the spelling it would write",
                squash(library).contains("LibraryHomeRails.removePins(context, pinsForRow + pin)")
            )
        }
        assertTrue(
            "the TV row marker reads the same rule, so the list and its menu cannot disagree",
            squash(tvLibrary).contains(
                "val onHome = pinsNamingList( homeRailPins, list.source.name, " +
                    "libraryHomeRailIdSpellings(list), list.name ).isNotEmpty()"
            )
        )
    }
}
