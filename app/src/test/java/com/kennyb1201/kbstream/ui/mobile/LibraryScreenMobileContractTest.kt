package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's turn at Phase 2's rule: one `FormFactor` branch in the TV file,
 * the phone layout in a new tv-free file, and the same activity-scoped
 * `LibraryViewModel` behind both.
 *
 * Pinned here because a JVM test cannot compose either layout: the dispatch,
 * the filter/sort strip, the hidden-title filter, the return-to-poster record,
 * the personal-lists pane and the per-source row menu at their contract level.
 */
class LibraryScreenMobileContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun libraryScreen(): String =
        source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")

    private fun mobileLibrary(): String =
        source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")

    @Test
    fun `library dispatches on the form factor at its entry point`() {
        val library = libraryScreen()
        val dispatch = library.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = library.indexOf("LibraryScreenMobile(")
        assertTrue("LibraryScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call LibraryScreenMobile", mobileCall > dispatch)
        val callBlock = library.substring(mobileCall)
            .substringBefore("val context = LocalContext.current")
        assertTrue(
            "the branch must hand over the same callback and ViewModel",
            callBlock.contains("onItemClick = onItemClick") &&
                callBlock.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV page must still be the focus grid it was",
            library.substringAfter("val context = LocalContext.current")
                .contains("ItemGrid(")
        )
    }

    @Test
    fun `the mobile library is built on the touch twins and the mobile theme`() {
        val mobile = mobileLibrary()
        assertTrue(
            "the screen must apply MobileTheme",
            mobile.contains("MobileTheme {")
        )
        assertTrue(
            "posters are touch tiles that follow the global landscape switch",
            mobile.contains("ui.components.touch.TouchGlobalPosterTile")
        )
        assertTrue(
            "the row menu is the touch sheet",
            mobile.contains("ui.components.touch.PosterContextMenu")
        )
        assertTrue(
            "the filter and sort chips are the shared touch pill",
            mobile.contains("ui.components.touch.TouchChoiceChip")
        )
        assertTrue(
            "the create-list field and its confirm are the touch controls",
            mobile.contains("ui.components.touch.KBTextField") &&
                mobile.contains("ui.components.touch.KBButton")
        )
        assertFalse(
            "the mobile library must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile library reads the tv library view model`() {
        val mobile = mobileLibrary()
        listOf(
            "viewModel.uiState",
            "viewModel.refresh()",
            "viewModel.setFilter(",
            "viewModel.setSort(",
            "viewModel.toggleHideWatched()",
            "viewModel.selectList(",
            "viewModel.createLocalList(",
            "viewModel.deleteLocalList(",
            "viewModel.removeItem("
        ).forEach { read ->
            assertTrue("mobile Library must use $read", mobile.contains(read))
        }
        listOf(
            "state.allItems",
            "state.localItems",
            "state.watchlistItems",
            "state.selectedListItems",
            "state.lists",
            "state.selectedList",
            "state.ratings",
            "state.watchedKeys",
            "state.partiallyWatchedKeys",
            "state.simklConnected",
            "state.mdbListConfigured"
        ).forEach { read ->
            assertTrue("mobile Library must read $read", mobile.contains(read))
        }
    }

    @Test
    fun `hidden titles drop out of every list, as on tv`() {
        val mobile = mobileLibrary()
        assertTrue(
            "the shared hidden-title filter, read once for the screen",
            mobile.contains("HiddenTitles.hides(") &&
                mobile.contains("rememberHiddenTitleKeys()")
        )
        listOf(
            "localItems = stateRaw.localItems.filterNot(::hiddenItem)",
            "allItems = stateRaw.allItems.filterNot(::hiddenItem)",
            "watchlistItems = stateRaw.watchlistItems.filterNot(::hiddenItem)",
            "selectedListItems = stateRaw.selectedListItems.filterNot(::hiddenItem)"
        ).forEach { filter ->
            assertTrue(
                "a hidden title must leave every library view: $filter",
                mobile.contains(filter)
            )
        }
    }

    @Test
    fun `the filter and sort strip is the tv one`() {
        val mobile = mobileLibrary()
        assertTrue(
            "all four filters are offered",
            mobile.contains("LibraryFilter.entries")
        )
        assertTrue(
            "all four sort fields are offered",
            mobile.contains("LibrarySort.entries")
        )
        assertTrue(
            "the selected sort carries its direction, so a second tap is not a no-op",
            mobile.contains("▲") && mobile.contains("▼")
        )
        assertTrue(
            "UNWATCHED toggles watched rows out of the grid",
            mobile.contains("WATCHED HIDDEN") &&
                mobile.contains("viewModel.toggleHideWatched()")
        )
        assertTrue(
            "each filter states its own empty case (the watchlist names why)",
            mobile.contains("\"Watchlist is empty — \"") &&
                mobile.contains("Simkl not connected") &&
                mobile.contains("MDBList key not set")
        )
    }

    @Test
    fun `a tap records the poster a back from detail returns to`() {
        val mobile = mobileLibrary()
        assertTrue(
            "the same record key the TV grid uses, so one page has one place",
            mobile.contains("BrowseReturnFocus.record(") &&
                mobile.contains("LIBRARY_BROWSE_SCREEN_KEY")
        )
        assertTrue(
            "and it is consumed on entry and scrolled to",
            mobile.contains("BrowseReturnFocus.consume(LIBRARY_BROWSE_SCREEN_KEY)") &&
                mobile.contains("gridState.scrollToItem(index)")
        )
        assertTrue(
            "the record is the dedupe key, not a position that re-sorting moves",
            mobile.contains("itemId = LocalLibraryStore.dedupeKey(")
        )
    }

    @Test
    fun `the personal-lists pane is a list, with a confirmed delete`() {
        val mobile = mobileLibrary()
        assertTrue(
            "rows show their count and source",
            mobile.contains("titles · ") &&
                mobile.contains("val sourceLabel = list.locationLabel")
        )
        assertTrue(
            "only a list this device owns can be deleted",
            mobile.contains("if (list.source.isLocalList) {") &&
                mobile.contains("listPendingDelete = list")
        )
        assertTrue(
            "the press opens a confirmation, never the delete itself",
            mobile.contains("Dialog(onDismissRequest = { listPendingDelete = null })") &&
                mobile.contains("Delete list?") &&
                mobile.contains("viewModel.deleteLocalList(list.id)")
        )
        assertTrue(
            "a new list can be created from the phone",
            mobile.contains("\"+ NEW LIST\"") &&
                mobile.contains("viewModel.createLocalList(newListName)")
        )
    }

    @Test
    fun `the row menu covers every source family`() {
        val mobile = mobileLibrary()
        listOf(
            "LibrarySource.LOCAL",
            "LibrarySource.MDBLIST_WATCHLIST",
            "LibrarySource.LOCAL_LIST",
            "LibrarySource.MDBLIST_LIST",
            "LibrarySource.TRAKT_WATCHLIST",
            "LibrarySource.TRAKT_LIST",
            "LibrarySource.SIMKL_WATCHLIST",
            "LibrarySource.SIMKL_LIST"
        ).forEach { source ->
            assertTrue("the menu must handle $source", mobile.contains(source))
        }
        assertTrue(
            "removal is the view model's, and it is destructive",
            mobile.contains("viewModel.removeItem(selected)") &&
                mobile.contains("Remove from this list")
        )
        assertTrue(
            "Simkl rows link back to their own page, as its attribution rules ask",
            mobile.contains("simklItemUrl(") &&
                mobile.contains("\"View on Simkl\"") &&
                mobile.contains("\"Managed on Simkl\"")
        )
        assertTrue(
            "and every row can go to its details, be added to a list and be hidden",
            mobile.contains("item.navigationId?.let") &&
                mobile.contains("LibraryAddTarget(") &&
                mobile.contains("hideTarget(")
        )
    }

    @Test
    fun `a row with no ids says so instead of swallowing the tap`() {
        val mobile = mobileLibrary()
        assertTrue(
            "a tracker row can carry only a title and a poster - it has no " +
                "detail page, and the tap reports that",
            mobile.contains("val navigationId = item.navigationId") &&
                mobile.contains("notice = \"No details page for") &&
                mobile.contains("if (navigationId == null)")
        )
    }
}
