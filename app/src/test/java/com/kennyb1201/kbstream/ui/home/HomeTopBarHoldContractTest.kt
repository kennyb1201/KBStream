package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.ui.kb.HomeTopBarHoldMs
import com.kennyb1201.kbstream.ui.kb.isTopBarHold
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-Up shortcut to Home's top bar, asserted at the two seams a unit test
 * cannot reach.
 *
 * The bar is reachable only from the rail drawn FIRST (see
 * HomeRailUpHookContractTest), so on a Home with a full catalog list it cost one
 * Up press per rail: forty-nine catalogs down, forty-nine presses to reach
 * SEARCH. The shortcut closes that without touching vertical navigation, and it
 * only does so while two things hold:
 *
 * 1. A SHORT press must still get through to the focus system. If the hook
 *    consumed KeyDown - or opened the bar on it - Up would stop moving focus to
 *    the rail above, which is how the whole screen is traversed.
 * 2. The hook must sit on the RAILS LIST, not on individual cards. Applied per
 *    card it would need every rail kind (catalog, browse, collection, two
 *    built-ins) to pass it on, which is exactly the hole the short-press hook
 *    has, and the shortcut would silently vanish for whichever rail kind was
 *    added next.
 *
 * The rule itself ([isTopBarHold]) is tested behaviourally; the rest pins the
 * source those invariants live in.
 */
class HomeTopBarHoldContractTest {

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
    fun `only a real hold counts, not the press that moves focus`() {
        assertFalse("a zero-length press", isTopBarHold(0L))
        assertFalse("a tap", isTopBarHold(120L))
        assertFalse("just short of the hold", isTopBarHold(HomeTopBarHoldMs - 1L))
        assertTrue("exactly the hold", isTopBarHold(HomeTopBarHoldMs))
        assertTrue("held well past it", isTopBarHold(HomeTopBarHoldMs + 5_000L))
    }

    @Test
    fun `the shortcut is applied once, to the rails list`() {
        val home = source(HOME)
        assertTrue(
            "the hold hook must be applied exactly once, on the list that draws " +
                "every rail - a second application would mean a per-card hook " +
                "that a rail kind can forget",
            home.split("rememberHomeTopBarHoldUpHook(").size - 1 == 1
        )

        // ...and that one application is the rails LazyColumn's modifier, not
        // some unrelated composable: slice the list call's argument block and
        // look for it before the content.
        val listCall = home.substringAfter("LazyColumn(\n", "MISSING")
        assertTrue("the rails LazyColumn is missing", listCall != "MISSING")
        val header = listCall.substringBefore("contentPadding")
        assertTrue(
            "the rails list must carry the hold hook in its own modifier chain",
            header.contains("rememberHomeTopBarHoldUpHook(")
        )

        // The bar has to hand focus back to the card the viewer left, so the
        // restore target is the one Home already keeps for its poster menus.
        assertTrue(
            "the hold must remember where the viewer was",
            home.contains("restoreTargetAtPress = { lastPosterFocusRequester }")
        )
        assertTrue(
            "the restore target is only overwritten when there is one, so a " +
                "rail kind that reports no card cannot leave dismiss with " +
                "nowhere to send focus",
            home.contains("if (restoreTarget != null) {")
        )
    }

    @Test
    fun `the press is passed through and only the release of a hold is consumed`() {
        val slots = source(SLOTS)
        // Whitespace stripped so the assertions are about the branches, not
        // about how the hook happens to be indented.
        val body = slots
            .substringAfter("internal fun rememberHomeTopBarHoldUpHook(")
            .substringBefore("fun KBHomeCollectionRail(")
            .replace(Regex("\\s+"), " ")
        assertTrue("the hold hook is missing", body.length > 100)

        val press = body.substringBefore("KeyEventType.KeyUp")
        val release = body.substringAfter("KeyEventType.KeyUp")

        assertFalse(
            "the press must reach the focus system, or Up stops moving focus to " +
                "the rail above",
            press.contains("onOpenTopBar(")
        )
        assertTrue(
            "the press is only recorded, and it is not consumed",
            press.contains("KeyEventType.KeyDown -> { if ")
        )
        assertTrue(
            "the hold is timed from the initial press, not from an OS repeat",
            press.contains("repeatCount == 0")
        )
        assertTrue(
            "where the viewer was is captured when the press begins",
            press.contains("restoreTargetAtPress()")
        )
        assertTrue(
            "a release with no press of ours open must read as a zero-length " +
                "press, not as a hold against a zeroed start time",
            release.contains("if (pressStartTime == 0L) { 0L }")
        )
        assertTrue(
            "a hold that is released opens the bar and is swallowed; a short " +
                "press is not, so it cannot also fight the focus move its own " +
                "press made",
            release.contains(
                "if (isTopBarHold(heldMs)) { onOpenTopBar(restoreTarget) true } " +
                    "else { false }"
            )
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
