package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wider-consumers audit, pinned.
 *
 * Taste signals were originally pinned to the recommendation blend and nothing
 * else. Two more consumers were then ADOPTED - the merged search waves' tiebreak
 * and the Library's LIKED sort chip - and every other surface was audited and
 * REJECTED. Both halves are contracts a later change could silently break, and
 * both live inside Android-bound view models, so they are pinned as source:
 *
 *  - adopted: the comparator POSITION (taste after the year, never before it),
 *    the empty default, and the LIKED chip's three groups;
 *  - rejected: no taste reference at all in the code that orders the surfaces
 *    that were turned down (the stream ranker, the hero, the Home catalog rails
 *    and their arrangement, actor filmography, Continue Watching, KBFolder,
 *    sports fixture order, and the add-on catalog reads search keeps in catalog
 *    order).
 */
class TasteWiderConsumersContractTest {

    @Test
    fun `the merged search order reads taste after the year, never before it`() {
        val order = section(SEARCH_VM, "internal fun searchTitleOrder(", "class SearchViewModel(")
        val match = order.indexOf("searchMatchScore")
        val year = order.indexOf("it.year")
        val taste = order.indexOf("searchTastePlacement")
        val name = order.indexOf("it.name.lowercase()")
        assertTrue("the comparator must still lead on match quality", match >= 0)
        assertTrue("...then the year", year > match)
        assertTrue("...then the viewer's taste", taste > year)
        assertTrue("...then the name", name > taste)
    }

    @Test
    fun `the merged waves take an empty-by-default snapshot, and the caller hands theirs over`() {
        val vm = source(SEARCH_VM)
        assertTrue(
            "the taste-free order has to be the pre-taste order, so the default is empty",
            vm.contains("taste: TasteSignals.Snapshot = TasteSignals.Snapshot()")
        )
        assertTrue(
            "the comparator is what the merge sorts with, not a second re-sort",
            vm.contains("sortedWith(searchTitleOrder(lowerQuery, taste))")
        )
        assertTrue(
            "and the call site reads the active profile's own signals",
            vm.contains("taste = TasteSignals.snapshot(app)")
        )
    }

    @Test
    fun `the library LIKED chip is last and its direction is deliberately ignored`() {
        val enumBody = section(LIBRARY_VM, "enum class LibrarySort(", "data class LibraryUiState(")
        val liked = enumBody.indexOf("LIKED(\"LIKED\")")
        assertTrue("the new chip must exist", liked >= 0)
        assertTrue(
            "and must be listed LAST, so the existing strip order is undisturbed",
            liked > enumBody.indexOf("RATING(\"RATING\")")
        )
        assertTrue(
            "the direction-ignored rule has to be stated, or a later agent 'fixes' it",
            enumBody.contains("IGNORED")
        )

        val branch = section(LIBRARY_VM, "LibrarySort.LIKED -> {", "liked + unSignalled + disliked")
        assertTrue("liked rows lead", branch.contains("TastePlacement.LIKED"))
        assertTrue("disliked rows trail", branch.contains("TastePlacement.DISLIKED"))
        assertFalse(
            "the LIKED sort must not read the direction flag",
            branch.contains("ascending")
        )
    }

    @Test
    fun `every library view and the sort entry point are handed the snapshot`() {
        val push = memberBody(LIBRARY_VM, "private fun pushDisplay(")
        assertTrue(
            "read once per rebuild, not once per view",
            push.contains("val taste = TasteSignals.snapshot(getApplication())")
        )
        assertTrue(
            "and every one of the four views sorts with it",
            Regex("sortItems\\(applyYears\\(canonical\\w+, years\\), sort, ratings, ascending, taste\\)")
                .findAll(push)
                .count() == 4
        )
        assertTrue(
            "the sort entry point passes it through with an empty default",
            source(LIBRARY_VM).contains("taste: TasteSignals.Snapshot = TasteSignals.Snapshot()")
        )
    }

    @Test
    fun `no rejected consumer orders by taste`() {
        listOf(
            "the stream ranker orders one title's streams off the stream text",
            STREAM_RANKER,
            "the Home hero follows focus",
            HOME_SCREEN,
            "the rail arrangement is the viewer's",
            HOME_ORDER_PREFS,
            "actor filmography is a historical record",
            ACTOR_VM,
            "Continue Watching is a resume list",
            WATCH_HISTORY_REPO,
            "a hosted collection's order is its author's",
            KB_FOLDER_VM,
            "fixtures are chronological",
            SPORTS_GAME_ORDER,
            "sports reads are chronological",
            ESPN,
            "an add-on catalog keeps its own order",
            ADDON_REPO
        ).chunked(2).forEach { (why, path) ->
            assertFalse(
                "$path must not read taste - $why",
                source(path).contains("TasteSignals")
            )
        }

        assertFalse(
            "the Home catalog rail pages from the add-on's own order; taste must not enter it",
            memberBody(HOME_VM, "fun loadMoreForRail(").contains("TasteSignals")
        )
        assertFalse(
            "the Upcoming schedule orders by air date, which taste cannot change",
            memberBody(HOME_VM, "fun buildUpcomingSchedule(").contains("TasteSignals")
        )
        assertFalse(
            "and Up Next is episode order within a show, air order across shows",
            memberBody(HOME_VM, "fun publishInstantUpNextSnapshot(").contains("TasteSignals")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    /** The text between two markers in one source file. */
    private fun section(path: String, startMarker: String, endMarker: String): String {
        val src = source(path)
        val start = src.indexOf(startMarker)
        assertTrue("source missing: $startMarker", start >= 0)
        val end = src.indexOf(endMarker, start + startMarker.length)
        assertTrue("source missing: $endMarker", end > start)
        return src.substring(start, end)
    }

    /**
     * The body of the member function starting at [signature], up to the next
     * member (the first line indented exactly one level whose first token is a
     * declaration) - so a nested lambda or block cannot cut the slice short.
     */
    private fun memberBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}(fun |private |internal |val |var |@)")
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val SEARCH_VM = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val LIBRARY_VM = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val ADDON_REPO = "com/kennyb1201/kbstream/data/addon/AddonRepository.kt"
        const val STREAM_RANKER = "com/kennyb1201/kbstream/domain/streamengine/StreamRanker.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_ORDER_PREFS = "com/kennyb1201/kbstream/data/kb/KBHomeOrderPrefs.kt"
        const val ACTOR_VM = "com/kennyb1201/kbstream/ui/actor/ActorViewModel.kt"
        const val WATCH_HISTORY_REPO =
            "com/kennyb1201/kbstream/data/history/WatchHistoryRepository.kt"
        const val KB_FOLDER_VM = "com/kennyb1201/kbstream/ui/kb/KBFolderViewModel.kt"
        const val SPORTS_GAME_ORDER = "com/kennyb1201/kbstream/ui/sports/SportsGameOrder.kt"
        const val ESPN = "com/kennyb1201/kbstream/data/sports/EspnSportsRepository.kt"
    }
}
