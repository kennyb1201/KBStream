package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Any list can be a Home rail", end to end through the source files.
 *
 * The feature spans four places that have to agree - the Library row's menu,
 * the store, Home's rail column, and the account sync row - and every one of
 * them fails QUIETLY: a missing menu entry is an action that does not exist, a
 * store nobody reads is a rail that never appears, and a sync row nobody gates
 * is an account wiped by every device that never used the feature. None of that
 * is visible from a compile, so it is pinned here.
 */
class LibraryHomeRailsContractTest {

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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val library =
        source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
    private val store =
        source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
    private val home =
        source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    // The rail LOADER moved out of the TV file when the phone grew the same
    // rails: one loader for both Home screens, so what a rail is (and which
    // lists make one) is decided in one place. The rules below are unchanged -
    // only the file they are read from is.
    private val railLoader =
        source("com/kennyb1201/kbstream/ui/home/LibraryHomeRailLoader.kt")
    private val payload =
        source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val sync = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")

    @Test
    fun `every list row can be put on Home, whatever service holds it`() {
        val pane = library.substringAfter("private fun ListsPane(")
            .substringBefore("private fun ItemGrid(")
        assertTrue(
            "the long-press must no longer be gated on canManageList - a " +
                "read-only service list has to reach the menu too",
            pane.contains("val onRowLongPress: (() -> Unit) = { onListLongPress(list) }")
        )
        val dialog = library.substringAfter("private fun LibraryListActionsDialog(")
        assertTrue(
            "the dialog offers the switch for every list",
            squash(dialog).contains("if (onHome) \"HIDE FROM HOME\" else \"SHOW ON HOME\"")
        )
        // The saved community list is the second reason a rename row is absent:
        // its name is its author's, so the flag alone no longer decides.
        assertTrue(
            "and hides rename/delete only where the service forbids them",
            squash(library).contains(
                "onRename = if (list.source.canManageList && !list.liked) {"
            )
        )
    }

    @Test
    fun `the switch writes the pin and the row says so`() {
        assertTrue(
            "the menu writes through the store",
            squash(library).contains("LibraryHomeRails.setPinned(")
        )
        assertTrue(
            "and the row marks itself, so the feature is discoverable from the rail",
            squash(library).contains("\" · On Home\"")
        )
    }

    @Test
    fun `Home draws a rail for every pinned list, and none for an empty one`() {
        assertTrue(
            "the column draws the pinned rails from its merged entries - a pinned " +
                "list is an entry of the one Home order, not a block after it",
            squash(home).contains(
                "is com.kennyb1201.kbstream.ui.kb.HomeEntry.LibraryRail ->"
            ) && !squash(home).contains("libraryHomeRailItems()")
        )
        assertTrue(
            "they are loaded once per pinned set, off the main thread",
            squash(railLoader).contains("produceState(initialValue = emptyList(), pins)") &&
                squash(railLoader).contains("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "an empty list draws nothing rather than an empty heading",
            squash(railLoader).contains(
                "if (items.isEmpty()) null else LibraryHomeRailUi(pin, items)"
            )
        )
        assertTrue(
            "and a kids profile on the account that owns the list must not see its " +
                "adult titles, so the rail goes through the same ceiling the Library does",
            squash(railLoader).contains("tmdb.kidsFilterMetas(")
        )
        assertTrue(
            "the TV column asks the shared loader - not a copy of its own",
            squash(home).contains("val pinnedLibraryRails by rememberLibraryHomeRails()") &&
                !squash(home).contains("private fun rememberLibraryHomeRails()")
        )
        assertTrue(
            "and the rail header is the pin's name plus its type suffix, computed " +
                "at render from the items that loaded",
            squash(home).contains(
                "SectionTitle( rail.pin.name + libraryRailTypeSuffix(rail.items.map " +
                    "{ it.type }), onHeight = onTitleHeight )"
            )
        )
    }

    @Test
    fun `a watchlist is a list you can pin, not just a filter`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val models = source("com/kennyb1201/kbstream/data/library/LibraryModels.kt")
        assertTrue(
            "the four account watchlists are named as watchlist sources",
            squash(models).contains(
                "val isWatchlistSource: Boolean get() = this == TRAKT_WATCHLIST || " +
                    "this == MDBLIST_WATCHLIST || this == SIMKL_WATCHLIST || " +
                    "this == BINGECAT_WATCHLIST"
            )
        )
        assertEquals(
            "one row per connected watchlist, built through the one helper",
            5,
            Regex(Regex.escape("watchlistLibraryList(")).findAll(viewModel).count()
        )
        assertTrue(
            "and the other list builder keeps them, or every local-list edit would " +
                "take the pinnable watchlists away until the next full refresh",
            squash(viewModel).contains(
                "_uiState.value.lists.filter { it.source.isWatchlistSource }"
            )
        )
        assertTrue(
            "each watchlist row carries a service-independent identity, so the pin " +
                "survives a reindex",
            squash(viewModel).contains("remoteId = WATCHLIST_REMOTE_ID")
        )
        assertTrue(
            "and opening one shows the rows the WATCHLIST filter already merged",
            squash(viewModel).contains(
                "return canonicalWatchlist.filter { it.source == list.source }"
            )
        )
    }

    @Test
    fun `the loader serves the sources the rail can hold`() {
        listOf(
            "LibrarySource.LOCAL_LIST" to "LocalLibraryStore.listItems(context, id)",
            "LibrarySource.BINGECAT_LIST" to "BingeCatRepository.getInstance(context).listRows(pin.id)",
            "LibrarySource.TRAKT_LIST" to "traktListReadsForPinnedId(pin.id)",
            "LibrarySource.MDBLIST_LIST" to "MdbListClient.getListItems(context, id)",
            "LibrarySource.TRAKT_WATCHLIST" to "TraktRepository.getInstance(context).watchlist()",
            "LibrarySource.MDBLIST_WATCHLIST" to "MdbListClient.getWatchlist(context)",
            "LibrarySource.BINGECAT_WATCHLIST" to
                "BingeCatRepository.getInstance(context).snapshot()?.watchlist",
            "LibrarySource.SIMKL_WATCHLIST" to
                "SimklRepository.getInstance(context).getWatchlistItems()"
        ).forEach { (branch, call) ->
            assertTrue("$branch must load its own rows, found no $call", store.contains(call))
        }
        assertTrue(
            "a Trakt pin is read by the path its id names, not always as the " +
                "account's own list - a liked PUBLIC list is pinned by its numeric " +
                "id, which Trakt answers ownerless, and a `users/me/lists/{numeric}` " +
                "read of it 404s to an empty rail",
            squash(store)
                .substringAfter("LibrarySource.TRAKT_LIST ->")
                .substringBefore("LibrarySource.MDBLIST_LIST ->")
                .let { branch ->
                    branch.contains("traktListReadsForPinnedId(pin.id)") &&
                        !branch.contains("TraktListRef(user = \"me\"")
                }
        )
        assertTrue(
            "and a title the viewer told the Library to forget stays off the rail too",
            squash(store).contains(
                "LibraryTombstones.removedFrom(items, LocalLibraryStore.tombstones(context))"
            )
        )
        assertTrue(
            "a service that fails costs the rail, never Home",
            squash(store).contains("runCatching { loadFor(context, source, pin) }.getOrDefault(emptyList())")
        )
    }

    @Test
    fun `the pins travel with the account, without erasing an untouched device`() {
        assertTrue(
            "the row exists",
            payload.contains("const val KEY_LIBRARY_HOME_RAILS = \"library_home_rails\"")
        )
        assertTrue(
            "it is built into the payload",
            squash(payload).contains(
                "KEY_LIBRARY_HOME_RAILS to " +
                    "com.kennyb1201.kbstream.data.library.LibraryHomeRails.buildBlob(context)"
            )
        )
        assertTrue(
            "and applied on the receiving side",
            squash(payload).contains("PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS -> applyLibraryHomeRails(")
        )
        assertTrue(
            "a device that never pinned publishes nothing",
            squash(sync).contains(
                "if (key == PrefsPayloadBuilder.KEY_LIBRARY_HOME_RAILS && " +
                    "!PrefsPayloadBuilder.hasLibraryHomeRailsToPublish(context)"
            )
        )
        assertTrue(
            "but a device that removed its last pin still does",
            squash(store).contains("prefs(context).getLong(SYNCED_AT_KEY, 0L) > 0L")
        )
        assertFalse(
            "the applier must not treat an absent rails array as \"clear them\"",
            squash(payload)
                .substringAfter("private fun applyLibraryHomeRails(")
                .substringBefore("prefs.edit()")
                .let { it.contains("rails = emptyList()") }
        )
    }
}
