package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Search screen's turn at Phase 2's rule: one `FormFactor` branch in the TV
 * file, the phone layout in a new tv-free file, and the same activity-scoped
 * `SearchViewModel` (and hoisted list state) behind both.
 *
 * Pinned here because a JVM test cannot compose either layout: the dispatch,
 * the query path, the hidden-title filter, the browse browser's actions and the
 * result rails at their contract level. The pure browse arithmetic is already
 * covered by the TV screen's own tests (`BrowseRules`), which the phone reuses.
 */
class SearchScreenMobileContractTest {

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

    private fun searchScreen(): String =
        source("com/kennyb1201/kbstream/ui/search/SearchScreen.kt")

    private fun mobileSearch(): String =
        source("com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt")

    @Test
    fun `search dispatches on the form factor at its entry point`() {
        val search = searchScreen()
        val dispatch = search.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = search.indexOf("SearchScreenMobile(")
        assertTrue("SearchScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call SearchScreenMobile", mobileCall > dispatch)
        val callBlock = search.substring(mobileCall)
            .substringBefore("val query by viewModel.searchQuery")
        listOf(
            "onItemClick = onItemClick",
            "onPersonClick = onPersonClick",
            "onStudioClick = onStudioClick",
            "onCollectionClick = onCollectionClick",
            "onOpenTagScreen = onOpenTagScreen",
            "onOpenStudioScreen = onOpenStudioScreen",
            "onOpenCollectionScreen = onOpenCollectionScreen",
            "onOpenDecadeScreen = onOpenDecadeScreen",
            "listState = listState",
            "viewModel = viewModel"
        ).forEach { handoff ->
            assertTrue(
                "the mobile branch must hand over $handoff - every browse entry " +
                    "and result opens through one of them",
                callBlock.contains(handoff)
            )
        }
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV page must still be the D-pad one it was",
            search.substringAfter("val query by viewModel.searchQuery")
                .contains("SearchBrowseBrowser(")
        )
    }

    @Test
    fun `the mobile search is built on the touch twins and the mobile theme`() {
        val mobile = mobileSearch()
        assertTrue(
            "the screen must apply MobileTheme",
            mobile.contains("MobileTheme {")
        )
        assertTrue(
            "the query is entered in the touch field",
            mobile.contains("ui.components.touch.KBTextField")
        )
        assertTrue(
            "results are touch poster tiles",
            mobile.contains("ui.components.touch.PosterCard")
        )
        assertTrue(
            "the menus are the touch sheet",
            mobile.contains("ui.components.touch.PosterContextMenu")
        )
        assertFalse(
            "the mobile search must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile search reads the tv view model`() {
        val mobile = mobileSearch()
        listOf(
            "viewModel.searchQuery",
            "viewModel.results",
            "viewModel.addonResultGroups",
            "viewModel.actorResults",
            "viewModel.studioResults",
            "viewModel.collectionResults",
            "viewModel.watchedKeys",
            "viewModel.partialWatchedKeys",
            "viewModel.recentSearches",
            "viewModel.resolvedIds",
            "viewModel.suggestions",
            "viewModel.browseCategories",
            "viewModel.browseSubmenuLoading",
            "viewModel.hiddenBrowseChips",
            "viewModel.browseHomeShortcuts",
            "viewModel.selectedBrowseCategoryKey"
        ).forEach { read ->
            assertTrue("mobile Search must read $read", mobile.contains(read))
        }
    }

    @Test
    fun `the query path is the screen's own, including the voice seed`() {
        val mobile = mobileSearch()
        assertTrue(
            "every keystroke searches, as on TV",
            mobile.contains("onValueChange = viewModel::onQueryChanged")
        )
        assertTrue(
            "Done commits the query (it is what records the recent search)",
            mobile.contains("onDone = { viewModel.commitSearch() }")
        )
        assertTrue(
            "a spoken query or deep link is consumed once and submitted",
            mobile.contains("SearchSeed.consume()")
        )
        assertTrue(
            "clearing the field clears the search",
            mobile.contains("viewModel.onQueryChanged(\"\")")
        )
        assertTrue(
            "a fresh visit opens the keyboard; a return to existing results does not",
            mobile.contains("if (viewModel.searchQuery.value.isBlank()) " +
                "fieldFocus.requestFocus()")
        )
    }

    @Test
    fun `hidden titles drop out of both result families`() {
        val mobile = mobileSearch()
        assertTrue(
            "the filter is the shared one, read once for the screen",
            mobile.contains("HiddenTitles.hides(") &&
                mobile.contains("rememberHiddenTitleKeys()")
        )
        assertTrue(
            "the TMDB rows and the add-on rails are both filtered",
            mobile.contains("resultsRaw.filterNot(::resultIsHidden)") &&
                mobile.contains("group.results.filterNot(::resultIsHidden)")
        )
        assertTrue(
            "the empty state counts the RAW matches, so hiding hits does not " +
                "read as 'no matches'",
            mobile.contains("resultsRaw.size + actorResults.size + studioResults.size")
        )
    }

    @Test
    fun `the browse browser is the full browser, with its touch actions`() {
        val mobile = mobileSearch()
        assertTrue(
            "tapping a tab selects the category, as on TV",
            mobile.contains("viewModel.selectBrowseCategory(category.key)")
        )
        assertTrue(
            "a chip opens its discover screen through the view model",
            mobile.contains("viewModel.onBrowseEntryClicked(")
        )
        assertTrue(
            "a long press offers Home mirroring and Hide",
            mobile.contains("viewModel.addBrowseChipToHome(") &&
                mobile.contains("viewModel.removeBrowseChipFromHome(") &&
                mobile.contains("viewModel.hideBrowseChip(")
        )
        assertTrue(
            "a category tab's long press brings back what was hidden under it",
            mobile.contains("viewModel.unhideAllBrowseChips(") &&
                mobile.contains("BrowseChipVisibility.countHidden(")
        )
        assertTrue(
            "the pure browse rules are the shared ones, not a second copy",
            mobile.contains("filterBrowseEntries(") &&
                mobile.contains("browseCountLabel(") &&
                mobile.contains("browseCategoryBlurb(") &&
                mobile.contains("browseCategoryNounPlural(")
        )
        assertTrue(
            "a big category gets its filter field, and every category can be shuffled",
            mobile.contains("BROWSE_FILTER_MIN_ENTRIES") &&
                mobile.contains("category.entries.randomOrNull()")
        )
        assertTrue(
            "the view model's discover-screen hooks are armed on entry",
            mobile.contains("viewModel.onOpenTagScreen = onOpenTagScreen") &&
                mobile.contains("viewModel.onOpenStudioScreen = onOpenStudioScreen") &&
                mobile.contains("viewModel.onOpenCollectionScreen = onOpenCollectionScreen") &&
                mobile.contains("viewModel.onOpenDecadeScreen = onOpenDecadeScreen")
        )
    }

    @Test
    fun `the result rails are the tv rails, with the same badges and titles`() {
        val mobile = mobileSearch()
        listOf("Titles", "Actors", "Studios", "Collections").forEach { rail ->
            assertTrue("the phone must keep the $rail rail", mobile.contains("\"$rail\""))
        }
        assertTrue(
            "the watched badges come from the shared helpers, not a second rule",
            mobile.contains("watchedTile(") &&
                mobile.contains("partialWatchedTile(")
        )
        assertTrue(
            "add-on rail identities and titles are the shared ones",
            mobile.contains("addonRailIdentity(") && mobile.contains("searchRailTitle(")
        )
        assertTrue(
            "both synced rail-label toggles are honoured and re-read on revision",
            mobile.contains("getSearchRailShowCatalogType") &&
                mobile.contains("getSearchRailShowAddonName") &&
                mobile.contains("PrefsPayloadApplier")
        )
    }

    @Test
    fun `the title menu is the tv menu`() {
        val mobile = mobileSearch()
        assertTrue(
            "the same id pair the tile's badge uses feeds the library add",
            mobile.contains("LibraryIds.split(result.id)") &&
                mobile.contains("LibraryAddTarget(")
        )
        assertTrue(
            "the menu can go to the title, toggle watched and hide it",
            mobile.contains("viewModel.onResultOpened(selected)") &&
                mobile.contains("viewModel.markAsWatched(selected)") &&
                mobile.contains("viewModel.markUnwatched(selected)") &&
                mobile.contains("hideTarget(")
        )
        assertTrue(
            "the watched row's label is the shared one",
            mobile.contains("watchedMenuLabel(")
        )
    }

    @Test
    fun `the selectable pill is shared, not re-drawn per screen`() {
        val chips = source("com/kennyb1201/kbstream/ui/components/touch/TouchChips.kt")
        assertTrue(
            "the touch chip must live in the touch twin layer",
            chips.contains("fun TouchChoiceChip(")
        )
        assertTrue(
            "a chip cannot carry a long press through FilterChip, so it is " +
                "built on combinedClickable",
            chips.contains("combinedClickable(onClick = onClick, onLongClick = onLongClick)")
        )
        assertTrue(
            "both phone screens that draw it use the shared one",
            mobileSearch().contains("TouchChoiceChip(") &&
                source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
                    .contains("TouchChoiceChip(")
        )
    }
}
