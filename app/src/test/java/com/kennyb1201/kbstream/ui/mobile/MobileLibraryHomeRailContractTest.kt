package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned Library rails, on the phone.
 *
 * The field symptom this pins: a list pinned from mobile Library said "Remove
 * from Home" and mobile Home never drew it, because every pinned rail lived
 * below the `FormFactor.isTv` return in [com.kennyb1201.kbstream.ui.home.HomeScreen]
 * - a screen the phone never reaches. The phone's Home is the arranged entries
 * and nothing else.
 *
 * So there are three things to hold:
 *
 *  1. ONE loader. The phone calls the television's loader rather than growing a
 *     twin, or the empty-rail rule and the kids ceiling would be enforced twice
 *     and could disagree.
 *  2. The rails at the END of the phone's column, in pin order, drawn with the
 *     touch cards and opened by the phone's own Detail callback.
 *  3. A fresh read of the pins per composition, so a pin (or unpin) shows on the
 *     next Home frame without a restart.
 *
 * Source text is what a JVM test can see here (the module has no
 * compose-ui-test harness); the layout itself is exercised on a device.
 */
class MobileLibraryHomeRailContractTest {

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

    private val loader =
        source("com/kennyb1201/kbstream/ui/home/LibraryHomeRailLoader.kt")
    private val tvHome =
        source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val mobileHome =
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")

    @Test
    fun `one loader serves both Home screens`() {
        assertTrue(
            "the loader must be shared, not copied: the TV calls it and holds no " +
                "private copy of its own",
            tvHome.contains("rememberLibraryHomeRails()") &&
                !tvHome.contains("private fun rememberLibraryHomeRails(")
        )
        assertTrue(
            "and the phone calls the same function",
            mobileHome.contains("val pinnedLibraryRails by rememberLibraryHomeRails()")
        )
        // The rules travel with it, unchanged.
        assertTrue(
            "one load per pin, off the main thread",
            loader.contains("value = withContext(Dispatchers.IO)") &&
                loader.contains("LibraryHomeRailItems.load(context, pin)")
        )
        assertTrue(
            "an empty answer is not a rail - never an empty heading",
            loader.contains("if (items.isEmpty()) null else LibraryHomeRailUi(pin, items)")
        )
        assertTrue(
            "the kids ceiling applies to a pinned list on both screens",
            loader.contains("tmdb.kidsFilterMetas(")
        )
        assertTrue(
            "a row with neither id cannot be opened, so it is dropped rather than " +
                "drawn as a dead card",
            loader.contains("val id = imdb ?: tmdb ?: return null")
        )
    }

    @Test
    fun `the phone draws the pinned rails at the end of Home, in pin order`() {
        val entries = mobileHome.indexOf("homeEntries.forEach")
        val rails = mobileHome.indexOf("items = pinnedLibraryRails")
        assertTrue("the phone must still draw the arranged entries", entries > 0)
        assertTrue(
            "the pinned rails sit AFTER the arranged entries - the end of Home, " +
                "as on the TV",
            rails > entries
        )
        assertTrue(
            "one rail per pin, keyed by the pin so a removed pin cannot stand in " +
                "for the next one",
            mobileHome.contains("key = { _, rail -> \"librail|\" + rail.pin.key }")
        )
        assertTrue(
            "the rail is the pin's name, as the TV headings are",
            mobileHome.contains("MobileSection(title = rail.pin.name)")
        )
        assertTrue(
            "the pin's own order is the order they were put there - no sort, no " +
                "alphabet",
            mobileHome.contains("items = pinnedLibraryRails") &&
                !mobileHome.contains("pinnedLibraryRails.sorted")
        )
    }

    @Test
    fun `a pinned rail is a touch rail, opened the phone's way`() {
        // Bounded at the next composable, so the assertions below are about this
        // rail and not about the cards that follow it.
        val rail = mobileHome.substringAfter("private fun MobileLibraryListRail(")
            .substringBefore("A Continue Watching card, in the television rail's own design.")
        assertTrue(
            "a tap must open the title's Detail through the phone's own callback " +
                "and arm the hero flight, exactly as a catalog card does",
            rail.contains("armHeroSource(meta.type, meta.id)") &&
                rail.contains("onOpen(meta)")
        )
        assertTrue(
            "the cards are the touch cards, in the viewer's shape",
            rail.contains("LandscapeCard(") && rail.contains("PosterCard(")
        )
        assertFalse(
            "no D-pad machinery comes with it: the TV's focus requesters and " +
                "bring-into-view specs stay TV-only",
            rail.contains("FocusRequester") || rail.contains("BringIntoViewSpec")
        )
        assertFalse(
            "and no long-press menu of its own - the shared poster menu is keyed " +
                "on a title, not on the list it was seen in",
            rail.contains("onLongClick")
        )
        assertTrue(
            "watched / partially-watched use the shared card badges, not a second " +
                "badge rule",
            rail.contains("isWatched = watched") &&
                rail.contains("isPartiallyWatched = partiallyWatched")
        )
        assertTrue(
            "a row whose art does not resolve still draws its title",
            rail.contains("fallbackTitle = meta.name") &&
                rail.contains("contentDescription = meta.name")
        )
        assertTrue(
            "the same title in two pinned lists must not share one card slot",
            rail.contains(
                "key = { _, meta -> \"librail|\" + rail.pin.key + \"|\" + " +
                    "meta.type + \":\" + meta.id }"
            )
        )
    }

    @Test
    fun `a new pin shows without a restart, and without the Library screen's tick`() {
        assertTrue(
            "the pins are read on each composition of Home, keyed on the pins " +
                "themselves - not remembered against the Library screen",
            loader.contains("val pins = LibraryHomeRails.pins(context)") &&
                loader.contains("produceState(initialValue = emptyList(), pins)")
        )
        assertTrue(
            "Home reads the pins itself: the Library screen's homeRailRevision is " +
                "remember-local there and never reaches this screen",
            mobileHome.contains("rememberLibraryHomeRails()") &&
                !mobileHome.contains("homeRailRevision += 1")
        )
        assertFalse(
            "and the phone must not keep a stale copy of the pins of its own",
            mobileHome.contains("LibraryHomeRails.pins(")
        )
    }

    @Test
    fun `the TV block and the catalog rails are untouched`() {
        assertTrue(
            "the TV still places its pinned rails at the end of its column",
            tvHome.contains("libraryHomeRailItems()")
        )
        assertTrue(
            "the TV rail is still the focus-aware one",
            tvHome.contains("private fun LibraryListHomeRail(") &&
                tvHome.contains(".focusRequester(requester)")
        )
        assertTrue(
            "the phone's arranged rails are still the ones the home manager places",
            mobileHome.contains("KBHomeSlots.buildMergedEntries(") &&
                mobileHome.contains("is HomeEntry.AddonRail ->")
        )
    }
}
