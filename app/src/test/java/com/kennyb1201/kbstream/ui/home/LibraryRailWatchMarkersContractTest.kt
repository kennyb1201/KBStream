package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned Library rails are loaded by the SCREENS, not by HomeViewModel: the
 * single shared loader (`rememberLibraryHomeRails`, used by both Home
 * surfaces) produces them, and the ViewModel never held them.
 *
 * That is why a watched title that existed only on a pinned Trakt / MDBList list
 * wore no eye or checkmark: the marker refresh only ever preloaded the catalog
 * rails (plus Top Picks), and the preload input is the boundary - a title not in
 * it can never enter `watchedKeys`. The same boundary left the sibling-ownership
 * set short, because it was built from the same catalog list.
 *
 * These tests pin the fix's wiring: the ViewModel stores what the screens
 * report, both screens report it, one combined set feeds the preload AND the
 * ownership filter, and a re-delivery of the same rows is not a refresh.
 */
class LibraryRailWatchMarkersContractTest {

    @Test
    fun `the viewmodel stores the rows the screens report`() {
        val source = source(HOME_VM)

        assertTrue(
            "the marker pass needs a stored value it reads on EVERY refresh " +
                "(the periodic tick, a profile switch, a mark) - passing it per " +
                "call would let the markers vanish on the next trigger",
            source.contains(
                "private val _libraryRailItems =\n" +
                    "        MutableStateFlow<List<MetaPreview>>(emptyList())"
            )
        )
        assertTrue(
            "and there must be one entry point for the screens to report them",
            source.contains("fun setLibraryRailItems(items: List<MetaPreview>) {")
        )
    }

    @Test
    fun `a re-delivery of the same rows is not a refresh`() {
        val body = functionBody(HOME_VM, "fun setLibraryRailItems(")
        val flat = body.flatten()

        assertTrue(
            "the comparison must be by content (the type/id pairs), never by " +
                "list identity: a recompose hands over a fresh list of the " +
                "same rows and every one would start a refresh",
            flat.contains(
                "if ( libraryRailItemKeys(items) == libraryRailItemKeys(_libraryRailItems.value) ) { return }"
            )
        )

        val guard = body.indexOf("libraryRailItemKeys(items)")
        val write = body.indexOf("_libraryRailItems.value = items")
        val refresh = body.indexOf("refreshWatchedStatus(_rails.value)")

        assertTrue("the setter must store what it was given", write >= 0)
        assertTrue(
            "the unchanged-rows guard must come BEFORE the store and its refresh",
            guard in 0 until write
        )
        assertTrue(
            "a changed set (including an EMPTY one - a profile with no pins must " +
                "drop the previous profile's rows) asks for the markers again",
            refresh > write
        )
    }

    @Test
    fun `one combined set feeds the preload and the ownership filter`() {
        val body = functionBody(HOME_VM, "private fun refreshWatchedStatus(")
        val flat = body.flatten()

        // T1: the pinned rails' rows join the catalog rails and the picks.
        assertTrue(
            "the marker pass must build one set from all three sources",
            flat.contains("watchedMarkerMetas( railItems = rails.flatMap { rail -> rail.items },") &&
                flat.contains("topPickItems =") &&
                flat.contains("libraryItems = _libraryRailItems.value")
        )
        assertTrue(
            "and its preload input must be that set, not the rails alone",
            flat.contains("val preloadItems = watchedMarkerPreload(markerMetas)")
        )
        assertTrue(
            "both badge sets resolve from the one input (eye and checkmark share it)",
            flat.contains("preloadAndGetWatchedKeys( preloadItems )") &&
                flat.contains("preloadAndGetPartiallyWatchedKeys( preloadItems )")
        )

        // T3: the ownership set must cover the same titles as the preload, or a
        // title that exists only on a pinned list has no id to be stripped by -
        // the PAW Patrol eye on the wrong profile.
        assertTrue(
            "the ownership pass must be given the SAME combined set, not the " +
                "catalog rails: a sibling-owned title that exists only on a " +
                "pinned list was outside it and kept its marker",
            flat.contains("siblingOwnedRailItemIds(markerMetas)")
        )

        val helperSource = source(HOME_VM)
        val helper = functionBody(HOME_VM, "private fun siblingOwnedRailItemIds(")
        assertTrue(
            "and the helper must take those items rather than walking Rails itself",
            helperSource.contains(
                "private fun siblingOwnedRailItemIds(items: List<MetaPreview>): Set<String> {"
            ) && helper.contains("return items")
        )
    }

    @Test
    fun `both home screens report their loaded rails, and neither loads them itself`() {
        val tv = source(HOME_SCREEN)
        val phone = source(HOME_SCREEN_MOBILE)
        val report =
            "LaunchedEffect(pinnedLibraryRails) { viewModel.setLibraryRailItems( " +
                "pinnedLibraryRails.flatMap { rail -> rail.items } ) }"

        assertTrue(
            "the television's loader result must reach the marker pass, keyed " +
                "on the loaded rails so a pin or unpin re-reports",
            tv.flatten().contains(report)
        )
        assertTrue(
            "and so must the phone's - one shared HomeViewModel, so one fix " +
                "covers both surfaces",
            phone.flatten().contains(report)
        )
        assertTrue(
            "the loading stays in the one shared loader; a second copy is how " +
                "the TV's rails and the phone's would drift",
            tv.contains("rememberLibraryHomeRails()") &&
                phone.contains("rememberLibraryHomeRails()") &&
                !tv.contains("fun rememberLibraryHomeRails(") &&
                !phone.contains("fun rememberLibraryHomeRails(")
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** Whitespace-insensitive form, so formatting is not what a test pins. */
    private fun String.flatten(): String = replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val HOME_SCREEN_MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
    }
}
