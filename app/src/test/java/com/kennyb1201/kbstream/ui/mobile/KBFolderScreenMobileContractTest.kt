package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.kb.KBContentItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KB folder's turn at Phase 2's rule: one `FormFactor` branch in the TV
 * file, the phone layout in a new tv-free file, and the same
 * `KBFolderViewModel` behind both.
 *
 * The page is the grid the phone Home opens a collection into, so the contract
 * pins the load/retry path, the source tabs, the hidden-title filter (through
 * the TV page's own shared helper), the return-to-poster record and the
 * watched-key candidates by behaviour.
 */
class KBFolderScreenMobileContractTest {

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

    private fun folderScreen(): String =
        source("com/kennyb1201/kbstream/ui/kb/KBFolderScreen.kt")

    private fun mobileFolder(): String =
        source("com/kennyb1201/kbstream/ui/mobile/KBFolderScreenMobile.kt")

    private fun item(
        id: String,
        type: String = "movie",
        tmdbId: Int? = null
    ) = KBContentItem(
        id = id,
        type = type,
        title = "Title $id",
        posterUrl = null,
        backdropUrl = null,
        year = "2020",
        rating = null,
        tmdbId = tmdbId
    )

    @Test
    fun `the kb folder dispatches on the form factor at its entry point`() {
        val screen = folderScreen()
        val dispatch = screen.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = screen.indexOf("KBFolderScreenMobile(")
        assertTrue("KBFolderScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call KBFolderScreenMobile", mobileCall > dispatch)
        val branch = screen.substring(dispatch, mobileCall + 220)
        assertTrue(
            "the branch must hand over the route's folder and the same ViewModel",
            branch.contains("folderId = folderId") && branch.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must hand over the item callback",
            branch.contains("onItemClick = onItemClick")
        )
        assertTrue(
            "the branch must return before the TV focus/return plumbing runs",
            branch.contains("return")
        )
        assertTrue(
            "the TV page must still own its layouts",
            screen.substringAfter("KBFolderScreenMobile(").contains("KBFolderBody(")
        )
    }

    @Test
    fun `the mobile folder is built on the touch twins and the mobile theme`() {
        val mobile = mobileFolder()
        assertTrue("the page must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "the cards are touch tiles that follow the global landscape switch",
            mobile.contains(
                "com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile"
            )
        )
        assertTrue(
            "the long-press menu is the touch sheet",
            mobile.contains("com.kennyb1201.kbstream.ui.components.touch.PosterContextMenu")
        )
        assertTrue(
            "the retry control is the touch button",
            mobile.contains("com.kennyb1201.kbstream.ui.components.touch.KBButton")
        )
        assertFalse(
            "the mobile folder must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile folder drives the tv view model and shares the tv rules`() {
        val mobile = mobileFolder()
        listOf(
            "viewModel.loadById(folderId)",
            "viewModel.retry(folderId)",
            "viewModel.selectSource(",
            "viewModel.resolveAndNavigate(",
            "viewModel.markAsWatched(item)",
            "viewModel.markUnwatched(item)",
            "viewModel.watchedKeys",
            "viewModel.partialWatchedKeys",
            "viewModel.resolvedIds"
        ).forEach { call ->
            assertTrue("mobile folder must use $call", mobile.contains(call))
        }
        assertTrue(
            "hidden titles go through the TV page's own filter",
            mobile.contains("visibleFolderRails(")
        )
        assertTrue(
            "cards are keyed the way the TV page keys them",
            mobile.contains("kbFolderCardKey(item)")
        )
        assertTrue(
            "source tabs keep the TV gate (showAllTab and at least two rails)",
            mobile.contains("state.showAllTab && state.rails.size >= 2")
        )
        assertTrue(
            "a detail visit records the poster it came from",
            mobile.contains("BrowseReturnFocus.record(") &&
                mobile.contains("BrowseReturnFocus.consume(returnKey)")
        )
        assertFalse(
            "the phone page is already a grid, so the TV's Open in Grid row is not offered",
            mobile.contains("label = \"Open in Grid\"")
        )
        assertTrue(
            "the menu still carries the TV details and watched rows",
            mobile.contains("\"Go to Details\"") && mobile.contains("watchedMenuLabel(")
        )
    }

    @Test
    fun `watched candidates follow the tv page's id order`() {
        assertEquals(
            "an add-on card's own IMDb id is the only candidate",
            listOf("movie::tt0111161"),
            mobileKbWatchedKeys(item(id = "tt0111161"), emptyMap())
        )
        assertEquals(
            "a series is normalized to the series key",
            listOf("series::tt0903747"),
            mobileKbWatchedKeys(item(id = "tt0903747", type = "TV"), emptyMap())
        )
        assertEquals(
            "a TMDB card checks the resolved IMDb flavour first, then tmdb:<n>",
            listOf("movie::tt0133093", "movie::tmdb:603"),
            mobileKbWatchedKeys(
                item(id = "tmdb:603", tmdbId = 603),
                mapOf("movie::603" to "tt0133093")
            )
        )
        assertEquals(
            "an unresolved TMDB card still keys on its raw TMDB id",
            listOf("movie::tmdb:603"),
            mobileKbWatchedKeys(item(id = "tmdb:603", tmdbId = 603), emptyMap())
        )
    }
}
