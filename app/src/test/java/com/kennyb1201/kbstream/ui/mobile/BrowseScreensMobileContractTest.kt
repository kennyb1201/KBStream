package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse family's turn at Phase 2's rule: one `FormFactor` branch in each TV
 * file, the phone layout in a tv-free file under `ui/mobile/`, and the same
 * activity-scoped ViewModel behind both.
 *
 * A JVM test cannot compose either layout, so what is pinned here is the
 * contract: the dispatch and what it hands over, the shared touch page's shape
 * (hidden-title filter, watched arithmetic, paging, menu, return record), and
 * the per-screen pieces that must not be lost - the studio's genre chips and
 * brand header, the decade's genre chips, and the collection's release order,
 * override chain and part actions.
 */
class BrowseScreensMobileContractTest {

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

    private fun tagScreen(): String =
        source("com/kennyb1201/kbstream/ui/tag/TagScreen.kt")

    private fun studioScreen(): String =
        source("com/kennyb1201/kbstream/ui/studio/StudioScreen.kt")

    private fun decadeScreen(): String =
        source("com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt")

    private fun collectionScreen(): String =
        source("com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt")

    private fun sharedPage(): String =
        source("com/kennyb1201/kbstream/ui/mobile/BrowseSectionsMobile.kt")

    private fun tagMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/TagScreenMobile.kt")

    private fun studioMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/StudioScreenMobile.kt")

    private fun decadeMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/DecadeScreenMobile.kt")

    private fun collectionMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt")

    @Test
    fun `each browse page dispatches on the form factor at its entry`() {
        listOf(
            Triple("TagScreen", tagScreen(), "TagScreenMobile("),
            Triple("StudioScreen", studioScreen(), "StudioScreenMobile("),
            Triple("DecadeScreen", decadeScreen(), "DecadeScreenMobile("),
            Triple("CollectionScreen", collectionScreen(), "CollectionScreenMobile(")
        ).forEach { (name, tv, mobileCall) ->
            val dispatch = tv.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
            val call = tv.indexOf(mobileCall)
            assertTrue("$name must branch on FormFactor.isTv", dispatch >= 0)
            assertTrue("the mobile branch must call $mobileCall", call > dispatch)
            val block = tv.substring(call).substringBefore("return")
            assertTrue(
                "the branch must hand the same ViewModel over",
                block.contains("viewModel = viewModel")
            )
            assertTrue(
                "the branch must hand the same navigation callback over",
                block.contains("onNavigateDetail = onNavigateDetail")
            )
            assertTrue(
                "the branch must return, so the TV page never composes on a phone",
                tv.substring(call).substringBefore("val ").contains("return")
            )
            assertTrue(
                "the TV page must still be below the branch",
                tv.substringAfter("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
                    .substringAfter("return")
                    .contains("LazyColumn(") ||
                    name == "CollectionScreen"
            )
        }
    }

    @Test
    fun `the mobile browse pages are built on the touch twins and the mobile theme`() {
        listOf(
            "TagScreenMobile" to tagMobile(),
            "StudioScreenMobile" to studioMobile(),
            "DecadeScreenMobile" to decadeMobile(),
            "CollectionScreenMobile" to collectionMobile()
        ).forEach { (name, mobile) ->
            assertTrue("$name must apply MobileTheme", mobile.contains("MobileTheme {"))
            assertTrue(
                "$name must draw posters with the touch card that follows the " +
                    "global landscape switch - directly, or through the shared " +
                    "sections page",
                mobile.contains("ui.components.touch.PosterCard") ||
                    mobile.contains("ui.components.touch.TouchGlobalPosterTile") ||
                    mobile.contains("SectionsBrowseMobile(")
            )
            assertFalse(
                "$name must not import a tv-material3 widget",
                mobile.contains("import androidx.tv.material3.")
            )
        }
        assertTrue(
            "the shared page draws its posters with the touch card that follows " +
                "the global landscape switch",
            sharedPage().contains("ui.components.touch.TouchGlobalPosterCard")
        )
        assertTrue(
            "and captions through the shared block, so the poster-title toggles " +
                "reach it",
            sharedPage().contains("TouchPosterCaptions(")
        )
        assertTrue(
            "the row menu is the touch sheet",
            sharedPage().contains("ui.components.touch.PosterContextMenu") &&
                collectionMobile().contains("ui.components.touch.PosterContextMenu")
        )
    }

    @Test
    fun `the shared sections page keeps the tv rails' rules`() {
        val shared = sharedPage()
        assertTrue(
            "hidden titles drop out of every rail, through the shared filter",
            shared.contains("HiddenTitles.hides(") &&
                shared.contains("rememberHiddenTitleKeys()")
        )
        assertTrue(
            "watched state is resolved through the IMDB key with the TMDB fallback",
            shared.contains("val tmdbKeyId = \"tmdb:\${railItem.item.id}\"") &&
                shared.contains("watchedKey(tmdbKeyId, mediaType) in watchedKeys")
        )
        assertTrue(
            "the checkmark wins over the eye badge",
            shared.contains("val partial = !watched") ||
                shared.contains("!watched && (")
        )
        assertTrue(
            "a rail with another page offers LOAD MORE at the end of its own row, " +
                "and shows its in-flight state",
            shared.contains("\"LOAD MORE\"") &&
                shared.contains("\"LOADING…\"") &&
                shared.contains("onLoadMore(section.title)")
        )
        assertTrue(
            "the menu carries the shared library, hide and watched rows",
            shared.contains("LibraryAddTarget(") &&
                shared.contains("hideTarget(") &&
                shared.contains("watchedMenuLabel(") &&
                shared.contains("watchedMenuDescription(")
        )
        assertTrue(
            "a tap records the card the Detail page returns to",
            shared.contains("BrowseReturnFocus.record(") &&
                shared.contains("BrowseReturnTarget(") &&
                shared.contains("BrowseReturnFocus.consume(screenKey)")
        )
        // Back from Detail reopens the page on the card that was pressed, in
        // the two steps the rail shape needs: the outer list onto the rail,
        // then that rail onto the card.
        assertTrue(
            "the return puts the outer list back on the recorded rail",
            shared.contains("revealBrowseReturnRail(railKey, target.railIndex)") &&
                shared.contains("rowStates[railKey]?.scrollToItem(target.position)")
        )
        assertTrue(
            "a failed load says so instead of reading as an empty page",
            shared.contains("\"Error: \$error\"") && shared.contains("\"TRY AGAIN\"")
        )
    }

    @Test
    fun `each rail is a horizontal band, so the next rail is one swipe away`() {
        val shared = sharedPage()
        assertTrue(
            "the page must scroll vertically, one rail at a time",
            shared.contains("LazyColumn(") && shared.contains("rememberLazyListState()")
        )
        assertTrue(
            "and each rail must be its own horizontal row",
            shared.contains("LazyRow(") && shared.contains("itemsIndexed(")
        )
        assertTrue(
            "each rail's row must be keyed by the rail title, the key the return " +
                "record carries",
            shared.contains("key = { _, section -> section.title }")
        )
        assertTrue(
            "the rail's heading must name it above the row",
            shared.contains("BrowseRailTitle(")
        )
        assertFalse(
            "a grid laid ONE rail's posters out as rows, which buried every rail " +
                "under it - the 'scroll forever to reach the next row' report",
            shared.contains("LazyVerticalGrid")
        )
    }

    @Test
    fun `tag, studio and decade use the same screen with their own view models`() {
        listOf(
            "TagScreenMobile" to tagMobile(),
            "StudioScreenMobile" to studioMobile(),
            "DecadeScreenMobile" to decadeMobile()
        ).forEach { (name, mobile) ->
            assertTrue(
                "$name must use the shared sections page",
                mobile.contains("SectionsBrowseMobile(")
            )
            assertTrue(
                "$name must hand its own paging states over",
                mobile.contains("RailPaging(") &&
                    mobile.contains("viewModel::loadMoreSection")
            )
            assertTrue(
                "$name must open a card through the view model's resolver",
                mobile.contains("viewModel.resolveAndNavigate(")
            )
            assertTrue(
                "$name must offer watched-state toggles through the view model",
                mobile.contains("viewModel.markAsWatched(") &&
                    mobile.contains("viewModel.markUnwatched(")
            )
        }

        assertTrue(
            "the tag page keeps its keyword/genre wording and its route key",
            tagMobile().contains("\"Keyword\"") &&
                tagMobile().contains("\"Genre\"") &&
                tagMobile().contains("tagBrowseScreenKey(")
        )
        assertTrue(
            "the studio page keeps the brand header and the genre chips",
            studioMobile().contains("logoUrls = logoUrls") &&
                studioMobile().contains("companyInfo?.description") &&
                studioMobile().contains("genres = genres") &&
                studioMobile().contains("viewModel::onGenreSelected") &&
                studioMobile().contains("studioBrowseScreenKey(")
        )
        val studioLoad = studioMobile().substringAfter("fun reload()")
        assertTrue(
            "the studio retry passes the same full route as the TV page",
            studioLoad.contains("originalsCompanyId") &&
                studioLoad.contains("networkIsCompany") &&
                studioMobile().contains("viewModel.selectedGenreId.value")
        )
        assertTrue(
            "the decade page keeps its genre chips, its decade key and its reload arguments",
            decadeMobile().contains("genres = genres") &&
                decadeMobile().contains("decadeBrowseScreenKey(") &&
                decadeMobile().contains("viewModel.load(decadeStart, viewModel.selectedGenreId.value)")
        )
    }

    @Test
    fun `the collection keeps release order, the override chain and its part actions`() {
        val mobile = collectionMobile()
        assertTrue(
            "the parts are painted in release order, as on TV",
            mobile.contains(".sortedBy { it.releaseDate ?: \"9999-99-99\" }")
        )
        assertTrue(
            "hidden films drop out of the collection",
            mobile.contains("HiddenTitles.hides(") &&
                mobile.contains("rememberHiddenTitleKeys()")
        )
        assertTrue(
            "the header walks the same still chain: screen, then all-screens, then TMDB",
            mobile.contains("imageOverrideBackdropUrl(") &&
                mobile.contains("ImageOverrides.SCREEN_ALL") &&
                mobile.contains("imageOverridePosterUrl(")
        )
        assertTrue(
            "the count line reads as movies, singular and plural",
            mobile.contains("\"1 movie\"") && mobile.contains("\"\$count movies\"")
        )
        assertTrue(
            "the menu opens the part's Detail page and toggles its watched state",
            mobile.contains("\"Go to Details\"") &&
                mobile.contains("viewModel.markAsWatched(part.id)") &&
                mobile.contains("viewModel.markUnwatched(part.id)")
        )
        assertTrue(
            "a tap records the film the Detail page returns to",
            mobile.contains("collectionBrowseScreenKey(collectionId)") &&
                mobile.contains("itemId = \"collection_part:\${part.id}\"") &&
                // scrollToItem counts ITEMS; the header sits in front of the
                // parts, so the offset is part of restoring the right cell.
                mobile.contains("gridState.scrollToItem(posterOffset + index)")
        )
        assertTrue(
            "a collection that fails to load can be retried",
            mobile.contains("viewModel.load(collectionId)") &&
                mobile.contains("\"TRY AGAIN\"")
        )
    }
}
