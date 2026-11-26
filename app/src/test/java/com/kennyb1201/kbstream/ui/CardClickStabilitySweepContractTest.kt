package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf spec 2026-11-22, item 4 remainder, the sweep beyond Home: the search
 * rails, the catalog grid, the KB folder grids, the collection page, the episode
 * card and the phone's Home rails all handed every card a fresh lambda, so a
 * card could compare equal everywhere else and still be recomposed whenever its
 * rail did - which on a D-pad walk is every focus step.
 *
 * [com.kennyb1201.kbstream.ui.home.CardClickStabilityContractTest] and
 * [com.kennyb1201.kbstream.ui.library.LibraryCardStabilityContractTest] pin the
 * Home and Library halves; this pins the sweep, in the same shape: the closure
 * is remembered per item and reads the caller's handler through
 * `rememberUpdatedState`, because every one of these rails is called with fresh
 * closures itself and keying on the handler would rebuild the card on every
 * parent pass.
 *
 * Read from the source, like the rest of the dashboard's contract tests: the
 * instance-reuse half needs a `ui-test-junit4` recomposition rule this harness
 * does not have, and the on-device check is the `recomp railItems` paste.
 */
class CardClickStabilitySweepContractTest {

    @Test
    fun `every swept screen hands its cards remembered instances`() {
        SITES.forEach { site ->
            val src = stripComments(source(site.path))
            assertEquals(
                "${site.path}: every converted card site has to receive the " +
                    "remembered instance, and nothing else in this sweep may " +
                    "quietly go back to a literal click",
                site.clickCount,
                count(src, "onClick = ${site.name}")
            )
            assertTrue(
                "${site.path}: ${site.name} has to be a remembered instance, or " +
                    "the card is handed a new click on every parent pass",
                rememberedInstance(site.name).containsMatchIn(src)
            )
            assertTrue(
                "${site.path}: the remembered closure reads the caller's handler at " +
                    "press time, so a new handler does not rebuild the card",
                readsAtPressTime(src)
            )
        }
    }

    @Test
    fun `no swept card still gets the literal closure it was fixed for`() {
        val kbFolder = stripComments(source(KB_FOLDER))
        assertFalse(
            "the folder grid's click is remembered now; a literal one came back",
            kbFolder.contains("onClick = { onOpenItem(item, target) }")
        )
        val detail = stripComments(source(DETAIL))
        assertFalse(
            "the episode card's click is remembered now; a literal one came back",
            detail.contains("onClick = { if (!isUnavailable) onClick() }")
        )
        val phone = stripComments(source(PHONE_HOME))
        assertEquals(
            "each of the phone's three arranged rails remembers ONE click and " +
                "shares it with both of its branches, the hero arm lives in it, " +
                "and Continue Watching and Upcoming remember their own",
            8,
            count(phone, "onClick = cardClick")
        )
        assertEquals(
            "the hero arm must not also stay in the card call, or the press " +
                "arms the flight twice",
            3,
            count(phone, "armHeroSource(")
        )
        assertEquals(
            "the Library rail has no long-press menu of its own, so only the " +
                "two arranged rails and Continue Watching remember one",
            5,
            count(phone, "onLongClick = cardLongClick")
        )
    }

    @Test
    fun `the touch surfaces on the same lists are remembered the same way`() {
        listOf(
            // The phone's titles rail, its people rail and its studios rail.
            Site(PHONE_SEARCH, "tileClick", 3),
            // The phone's Library grid.
            Site(PHONE_LIBRARY, "tileClick", 1),
            // The phone's KB folder grid.
            Site(PHONE_KB_FOLDER, "tileClick", 1),
            // The phone's catalog grid and its browse rails.
            Site(PHONE_CATALOG, "tileClick", 1),
            Site(PHONE_BROWSE, "tileClick", 1),
            // A person's films, series and crew sections.
            Site(PHONE_ACTOR, "creditClick", 3),
            // A collection's parts.
            Site(PHONE_COLLECTION, "partClick", 1)
        ).forEach { site ->
            val src = stripComments(source(site.path))
            assertEquals(
                "${site.path}: every converted card site receives the remembered " +
                    "instance",
                site.clickCount,
                count(src, "onClick = ${site.name}")
            )
            assertTrue(
                "${site.path}: ${site.name} is a remembered instance and reads its " +
                    "handler at press time",
                rememberedInstance(site.name).containsMatchIn(src) &&
                readsAtPressTime(src)
            )
        }

        // The phone's Detail episodes: the rail card and the list row.
        val phoneDetail = stripComments(source(PHONE_DETAIL))
        listOf("episodeClick", "listEpisodeClick").forEach { name ->
            assertTrue(
                "the phone's episode rows hand a remembered $name",
                phoneDetail.contains("onClick = $name") &&
                    Regex("""val ${Regex.escape(name)} = remember\(""")
                        .containsMatchIn(phoneDetail)
            )
        }
        assertTrue(
            "and the season tiles' click is remembered too, with the season menu's",
            phoneDetail.contains("val seasonClick = remember(seasonNumber) {") &&
                phoneDetail.contains("val seasonLongClick = remember(")
        )
    }

    @Test
    fun `the sweep kept what each site did, not just how it was handed over`() {
        val search = stripComments(source(SEARCH))
        assertTrue(
            "a search tile still records its place before it opens",
            search.contains("SearchReturnFocus.record(")
        )
        assertTrue(
            "the add-on rail remembers one click for every result",
            search.contains("val tileClick = remember(result, tileKey, railKey, index) {")
        )
        assertTrue(
            "the collections fixture remembers its own, and the on-screen one " +
                "is not the same key",
            search.contains("val tileClick = remember(result, tileKey, railKey, index) {") &&
                Regex("""val tileClick(\s*:[^=]+)? = remember\(collection, tileKey, index\) \{""")
                    .containsMatchIn(search)
        )

        val grid = stripComments(source(CATALOG_GRID))
        assertTrue(
            "a catalog tile is still a detour, and the flag is still set before " +
                "the navigation",
            grid.contains("leftForDetail = true") &&
                grid.contains("currentOnItemClick.value(meta)")
        )
        assertTrue(
            "the Hide successor is still resolved from the pager as it stands at " +
                "press time, through the updated indexer",
            grid.contains("currentItems.value.itemCount") &&
                grid.contains("currentItems.value.peek(at)")
        )

        val kbFolder = stripComments(source(KB_FOLDER))
        assertTrue(
            "the folder grid's long press still names the successor and the target",
            kbFolder.contains("currentOnLongPressItem.value(") &&
                kbFolder.contains("successorIndexAfter(") &&
                kbFolder.contains("Modifier.focusRequester(returnFocusRequester)")
        )
        assertEquals(
            "and both focus chains (the return card and the first card) " +
                "remembered whole, since neither carries a hero hook",
            1,
            count(kbFolder, "modifier = itemModifier")
        )

        val collection = stripComments(source(COLLECTION))
        assertTrue(
            "the collection part still records the rail before it navigates",
            collection.contains("BrowseReturnFocus.record(") &&
                collection.contains("currentOnNavigateDetail.value(")
        )

        val detail = stripComments(source(DETAIL))
        assertTrue(
            "an unaired episode is still not playable",
            detail.contains("if (!isUnavailable) currentOnClick.value()")
        )
    }

    /**
     * `rememberUpdatedState(...)`, or the same call with its lambda typed
     * (`rememberUpdatedState<T> { ... }`) — the type argument may itself carry
     * a `->`, so only the opening bracket is matched.
     */
    private fun readsAtPressTime(src: String): Boolean =
        Regex("""rememberUpdatedState\s*[<(]""").containsMatchIn(src)

    /**
     * `val name = remember(`, with or without a declared type: a handler whose
     * lambda would otherwise infer `() -> Unit?` is annotated, and the pin is
     * about the remembered instance, not about how the val is spelled.
     */
    private fun rememberedInstance(name: String): Regex =
        Regex("""val ${Regex.escape(name)}(\s*:[^=]+)? = remember\(""")

    private data class Site(val path: String, val name: String, val clickCount: Int)

    private fun count(text: String, needle: String): Int {
        var found = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            found++
            at = text.indexOf(needle, at + needle.length)
        }
        return found
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val CATALOG_GRID = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val KB_FOLDER = "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt"
        const val COLLECTION = "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val PHONE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val PHONE_SEARCH = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
        const val PHONE_LIBRARY = "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt"
        const val PHONE_KB_FOLDER = "com/kennyb1201/kbstream/ui/mobile/KBFolderScreenMobile.kt"
        const val PHONE_DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val PHONE_CATALOG = "com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt"
        const val PHONE_BROWSE = "com/kennyb1201/kbstream/ui/mobile/BrowseSectionsMobile.kt"
        const val PHONE_ACTOR = "com/kennyb1201/kbstream/ui/mobile/ActorScreenMobile.kt"
        const val PHONE_COLLECTION =
            "com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt"

        val SITES = listOf(
            // The three search rails: titles, add-ons and collections.
            Site(SEARCH, "tileClick", 5),
            Site(CATALOG_GRID, "tileClick", 1),
            Site(KB_FOLDER, "itemClick", 1),
            Site(COLLECTION, "tileClick", 1),
            // Only the episode card is remembered here; the page's other cards
            // (cast, crew, the season rail) are not part of this item.
            Site(DETAIL, "posterClick", 1),
            Site(PHONE_HOME, "cardClick", 8)
        )
    }
}
