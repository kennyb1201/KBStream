package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.ui.kb.isTopRailEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's two arrangement invariants, asserted at the seams a unit test cannot
 * reach.
 *
 * 1. The rail drawn FIRST owns the D-pad Up -> top bar hook, and it is the only
 *    one that does. The hero spacer above the rails is inert, so that hook is
 *    the only route to the top bar; handing it to a rail that is not first, or
 *    to none of them, is a dead Up key or an unreachable top bar on a remote.
 *    Ownership used to follow rail identity (Continue Watching took it whenever
 *    it had cards), which was the same as "the topmost rail" only while CW was
 *    hardcoded above the catalogs.
 * 2. The built-in rows are drawn even when no catalog rail exists. They read
 *    local history and the local schedule, so a profile with history and no
 *    add-ons must still see them - and the loader must not claim the screen
 *    while they do.
 *
 * The rule itself ([isTopRailEntry]) is tested behaviourally; the rest pins that
 * every rail kind applies it.
 */
class HomeRailUpHookContractTest {

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

    @Test
    fun `only the first merged entry owns the hook`() {
        assertTrue(isTopRailEntry(0))
        assertFalse(isTopRailEntry(1))
        assertFalse(isTopRailEntry(-1))
    }

    @Test
    fun `ownership follows the merged order, not rail identity`() {
        val home = source(HOME)
        assertFalse(
            "rail identity must not decide the hook again",
            home.contains("firstRailNeedsUpHook")
        )
        assertFalse(home.contains("firstDisplayedRailSourceIndex"))
        assertTrue(
            "the catalog cards ask the order, through the shared rule",
            home.contains("Modifier.homeTopRailUpHook(") &&
                home.contains("isTopRailEntry(entryIndex)")
        )
    }

    @Test
    fun `every rail kind that can be drawn first can take the hook`() {
        val home = source(HOME)
        val slots = source(SLOTS)
        // Built-ins, browse rows, collections and catalog rails all receive it.
        assertTrue(
            "the built-ins take the hook from their position",
            home.contains("drawBuiltinRail(")
        )
        assertEquals(
            "the browse and collection rows both take it",
            2,
            home.split("onUpPressed = topRailUpHook(entryIndex)").size - 1
        )
        assertTrue(
            "the Continue Watching rail no longer opens the top bar unconditionally",
            !home.contains("onUpPressed = { requester ->")
        )
        // The one modifier implementation, applied by the two rails in the
        // slots file and available to the rest.
        assertTrue(slots.contains("internal fun Modifier.homeTopRailUpHook("))
        assertEquals(
            "both slots-file rails apply it to their own tiles",
            2,
            slots.split("homeTopRailUpHook(requester, onUpPressed)").size - 1
        )
        assertTrue(
            "and every rail kind can be told it does not own it",
            slots.contains("onUpPressed: ((FocusRequester) -> Unit)? = null")
        )
    }

    @Test
    fun `the built-in rows are drawn when no catalog rail exists`() {
        val home = source(HOME)
        assertTrue(
            "one place emits the built-ins for the branches that have no rail column",
            home.contains("builtinRailItems()")
        )
        assertEquals(
            "declared once and called from both no-catalog branches",
            3,
            home.split("builtinRailItems()").size - 1
        )
        assertTrue(
            "the empty-state branch draws them",
            home.contains("rails.isEmpty() && !isLoading -> {")
        )
        assertTrue(
            "and the failed-build branch, which is status about the catalog list too",
            home.contains("error != null && rails.isNotEmpty() -> {")
        )
        assertTrue(
            "the full-screen loader must count a built-in row as content",
            home.contains("isLoading && mergedEntries.isEmpty() -> {")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
