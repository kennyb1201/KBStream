package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chip mirrored to Home can be taken off Home from the tile itself.
 *
 * The gap this pins: adding a chip to Home is a long-press on the chip in the
 * search browser's Browse strip, and for a while that was the ONLY place the
 * relationship could be undone - so a viewer who wanted the row off Home had to
 * remember which chip added it and go find it. The tile now answers a long
 * press with "Remove from Home", and every part of that is wiring that a
 * refactor can drop silently: a rail that stops passing the tile's long press
 * through, a menu action that forgets to write, a removal that writes a second
 * copy of the pref blob instead of the shared one.
 *
 * It reads sources instead of calling them for the same reason
 * BrowseCatalogPublishContractTest and PlayerGuideWriteGateContractTest do:
 * none of these omissions fails to compile, and none of them is visible to a
 * behavioural test that cannot build a TV UI.
 */
class HomeBrowseShortcutRemovalContractTest {

    private companion object {
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val KB_VIEW_MODEL = "com/kennyb1201/kbstream/ui/kb/KBHomeViewModel.kt"
        const val SEARCH_VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val SEARCH_SCREEN = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"

        const val TILE = "private fun BrowseShortcutTile("
        const val MENU = "browseShortcutMenu?.let { shortcut ->"
        const val REMOVE = "fun removeBrowseShortcut("
        const val REFRESH = "fun refreshBrowseHomeShortcuts()"
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

    /**
     * Resolves `…/src/main/java` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
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

    private fun sliceFrom(src: String, marker: String, length: Int = 1_200): String {
        val at = src.indexOf(marker)
        assertTrue("$marker not found in the source", at >= 0)
        return src.substring(at, minOf(src.length, at + length))
    }

    /** One declaration's body, up to the given marker inside it. */
    private fun bodyBetween(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("$from not found in the source", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("$to not found after $from, so the body is not bounded", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the tile is what takes the long press, and the rail is what passes it up`() {
        val slots = source(SLOTS)
        // Bounded by the card's own content alignment, which sits below the
        // click wiring: a body that reached the endpoint saw the whole call.
        val tile = bodyBetween(slots, TILE, "contentAlignment = Alignment.Center")
        assertTrue(
            "BrowseShortcutTile must hand its long press to KBCard, or holding " +
                "Select on a Home browse tile does nothing but open the door",
            tile.contains("onLongClick = onLongClick")
        )
        assertTrue(
            "the rail must hand the tile's own FocusRequester up with the " +
                "shortcut, so the menu can give focus back to the tile",
            slots.contains("{ callback(shortcut, requester) }")
        )
    }

    @Test
    fun `Home raises a menu whose action takes the chip off the row`() {
        val home = source(HOME)
        assertTrue(
            "the Browse rail must pass a long-press handler, or the tile's menu " +
                "is unreachable from Home",
            home.contains("onShortcutLongPress = { shortcut, requester ->")
        )
        val menu = sliceFrom(home, MENU, length = 2_500)
        assertTrue(
            "the long press must raise the shared context menu",
            menu.contains("PosterContextMenu(")
        )
        assertTrue(
            "the menu's action must remove the chip from Home, which is the " +
                "whole point of the affordance",
            menu.contains("kbViewModel.removeBrowseShortcut(shortcut)")
        )
    }

    @Test
    fun `the removal writes through the one blob both screens read`() {
        val vm = sliceFrom(source(KB_VIEW_MODEL), REMOVE, length = 900)
        assertTrue(
            "the removal must go through BrowseHomeShortcuts.remove, the same " +
                "call the browse chip's own menu makes, or Home and Search can " +
                "disagree about which chips are mirrored",
            vm.contains("BrowseHomeShortcuts.remove(")
        )
        assertTrue(
            "the row must be republished in the same turn, or the tile the " +
                "viewer just removed stays on screen until the next Home resume",
            vm.contains("browseShortcuts = remaining")
        )
        assertTrue(
            "KBHomeViewModel must not grow its own read/write of the shortcut " +
                "pref blob: the data layer owns the key and its encoding",
            !vm.contains("putStringSet") && !vm.contains("getSharedPreferences")
        )
    }

    @Test
    fun `removing on Home cannot leave Search offering to remove it again`() {
        assertTrue(
            "SearchViewModel must expose a refresh of the mirrored set; its copy " +
                "is loaded once at construction and this ViewModel is " +
                "activity-scoped, so a chip removed on Home would leave the chip " +
                "menu on Search still offering \"Remove from Home\"",
            source(SEARCH_VIEW_MODEL).contains(REFRESH)
        )
        assertTrue(
            "Search must take that refresh when the screen appears, the way Home " +
                "reloads its shortcuts on resume",
            source(SEARCH_SCREEN).contains("viewModel.refreshBrowseHomeShortcuts()")
        )
    }
}
