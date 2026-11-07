package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.ui.kb.isHomeHeadLeftSummon
import com.kennyb1201.kbstream.ui.kb.isTopRailEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Left from a Home rail's HEAD card opens the top bar.
 *
 * The ask (Kenny, TV): forty catalogs down, one Left on the row's first card
 * should reach the top instead of one Up press per rail. Short press, not long -
 * Left on a head card was a dead press, so there is nothing to preserve and the
 * hold exists only where the short press is already taken.
 *
 * The Up hook and this one are deliberately asymmetric, and that is the rule
 * worth pinning: Up is gated to the rail drawn FIRST (a lower rail's Up has a
 * legitimate target, the rail above), while Left is ungated on every rail (no
 * head card has a legitimate Left target on any of them). The edge-stop stays
 * everywhere as the backstop for a rail kind the hook does not reach.
 *
 * The key rule is tested behaviourally ([isHomeHeadLeftSummon]); the rest pins
 * the seams a JVM test cannot drive - that every rail kind that draws a head
 * card carries the hook, that it is the plain opener and not the gated one, and
 * that the dismiss still comes back to the card that asked.
 */
class HomeRailHeadLeftHookContractTest {

    // ── spec T1: Left on the head summons and is consumed ─────────────

    @Test
    fun `a Left key-down on a head card summons the top bar`() {
        assertTrue(isHomeHeadLeftSummon(isHeadCard = true, isKeyDown = true, isLeft = true))
    }

    @Test
    fun `the summon hands over the card's own requester and consumes the press`() {
        val hook = bodyOf(SLOTS, "internal fun Modifier.homeRailHeadLeftTopBarHook(", "The Left rule itself")
        assertTrue(
            "the opener must be handed the requester of the card it was pressed on, " +
                "because that is what the dismiss restores",
            hook.contains("openTopBar(requester)")
        )
        assertTrue(
            "a consumed press is what stops the focus system running a Left search",
            hook.contains("true\n        } else {\n            false")
        )
        assertTrue(
            "KeyDown only: nothing here times a hold, and KeyUp must fall through",
            hook.contains("event.type == KeyEventType.KeyDown") &&
                hook.contains("event.key == Key.DirectionLeft")
        )
    }

    // ── spec T2: Left elsewhere is the focus system's ─────────────────

    @Test
    fun `Left on a card that is not the head is left alone`() {
        assertFalse(isHomeHeadLeftSummon(isHeadCard = false, isKeyDown = true, isLeft = true))
    }

    @Test
    fun `KeyUp and every other key fall through`() {
        assertFalse(
            "a KeyUp must not summon: the press already did, or did not",
            isHomeHeadLeftSummon(isHeadCard = true, isKeyDown = false, isLeft = true)
        )
        assertFalse(
            "another key is the focus system's",
            isHomeHeadLeftSummon(isHeadCard = true, isKeyDown = true, isLeft = false)
        )
    }

    @Test
    fun `the hook is identity off the head`() {
        val hook = bodyOf(SLOTS, "internal fun Modifier.homeRailHeadLeftTopBarHook(", "The Left rule itself")
        assertTrue(
            "applying it to a whole row must only ever arm the head card",
            hook.contains("if (openTopBar == null || !isHeadCard) {") &&
                hook.contains("    this\n")
        )
    }

    // ── spec T3: the Up hook keeps its own, gated rule ────────────────

    @Test
    fun `Up stays gated to the rail drawn first`() {
        assertTrue(isTopRailEntry(0))
        assertFalse(isTopRailEntry(1))
        val home = source(HOME)
        assertTrue(
            "the per-position rule is still what feeds Up",
            home.contains("fun topRailUpHook(entryIndex: Int)") &&
                home.contains("onUpPressed = topRailUpHook(entryIndex)")
        )
        assertTrue(
            "and the short-press Up hook is still applied by the rails",
            home.contains("Modifier.homeTopRailUpHook(")
        )
    }

    @Test
    fun `the Left hook is never gated by rail position`() {
        val home = source(HOME)
        assertFalse(
            "gating Left by position would leave the other 39 rails' head cards dead",
            home.contains("onHeadLeftPressed = topRailUpHook")
        )
        assertTrue(
            "one plain opener, handed to every rail",
            home.contains(
                "val headLeftHook: (FocusRequester) -> Unit = { requester -> openTopBar(requester) }"
            )
        )
        assertEquals(
            "the three built-in rows and the two slots-file rails take it from the same lambda",
            5,
            home.split("onHeadLeftPressed = headLeftHook").size - 1
        )
        assertTrue(
            "and a pinned Library list, whichever slot the arrangement gives it",
            home.split("topRailUpHook(").size - 1 >= 4
        )
    }

    // ── spec T4: the dismiss comes back to the card that asked ────────

    @Test
    fun `dismissing the bar after a Left summon restores the head card`() {
        val home = source(HOME)
        val open = home.substringAfter("fun openTopBar(")
            .substringBefore("fun openTopBarFromHold(")
        assertTrue(
            "the summon stores the card's own requester as the restore target",
            open.contains("lastPosterFocusRequester = requester")
        )
        val dismiss = home.substringAfter("fun dismissTopBar() {")
            .substringBefore("// Long-press menu on Continue Watching cards.")
        assertTrue(
            "the dismiss hands focus back to it",
            dismiss.contains("restoreFocus(focusedCardRequester ?: lastPosterFocusRequester)")
        )
    }

    // ── spec T5: the edge-stop stays, on every head card ──────────────

    @Test
    fun `the edge-stop still cancels the Left search`() {
        val stop = bodyOf(SLOTS, "internal fun Modifier.homeRailHeadLeftEdgeStop(", "How long D-pad Up has to be HELD")
        assertTrue(
            stop.contains("this.focusProperties { left = FocusRequester.Cancel }")
        )
        assertTrue(
            "a cancel, not a consumed key: explicit requestFocus() calls are unaffected",
            stop.contains("if (!isHeadCard) {")
        )
    }

    @Test
    fun `every head-card site that has the edge-stop also has the hook`() {
        // The audit, as an invariant: the two are applied at exactly the same
        // places, so a rail kind cannot gain the guard and lose the shortcut (or
        // the reverse) without this failing.
        assertEquals(
            "HomeScreen's head-card sites",
            calls(source(HOME), "homeRailHeadLeftEdgeStop"),
            calls(source(HOME), "homeRailHeadLeftTopBarHook")
        )
        assertEquals(
            "the slots file's two rails",
            calls(source(SLOTS), "homeRailHeadLeftEdgeStop"),
            calls(source(SLOTS), "homeRailHeadLeftTopBarHook")
        )
        assertEquals(5, calls(source(HOME), "homeRailHeadLeftTopBarHook"))
        assertEquals(2, calls(source(SLOTS), "homeRailHeadLeftTopBarHook"))
    }

    @Test
    fun `every Home rail kind that draws a head card carries it`() {
        val home = source(HOME)
        val slots = source(SLOTS)

        // The five rail composables in HomeScreen, plus its catalog rail's
        // inline card (which is not a composable of its own).
        assertTrue(
            "the Upcoming Schedule cards",
            body(home, "private fun UpcomingEpisodeCard(", "private fun LibraryListHomeRail(")
                .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "a pinned Library list",
            body(home, "private fun LibraryListHomeRail(", "private fun BuiltinTopPicksRail(")
                .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "Top Picks for You",
            body(home, "private fun BuiltinTopPicksRail(", "private fun BuiltinContinueWatchingRail(")
                .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "Continue Watching",
            body(home, "private fun BuiltinContinueWatchingRail(", "private fun BuiltinUpcomingRail(")
                .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "every catalog rail, including the app-built Top Today / kids / guest rows",
            body(home, "val posterModifier = Modifier", "Box(")
                .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "and the Browse and Collection rows housed in the slots file",
            body(slots, "fun KBHomeCollectionRail(", "The collection rail's focus landing")
                .contains(".homeRailHeadLeftTopBarHook(") &&
                body(slots, "fun KBHomeBrowseRail(", "One Browse tile:")
                    .contains(".homeRailHeadLeftTopBarHook(")
        )
        assertTrue(
            "the hook is applied where the card's index is known, so `index == 0` " +
                "means the head of THAT rail",
            home.contains("isHeadCard = index == 0,") &&
                home.contains("isHeadCard = index == CONTINUE_WATCHING_HEAD_INDEX,")
        )
    }

    // ── source helpers ────────────────────────────────────────────────

    /**
     * How many times [name] is APPLIED by a source file.
     *
     * A call site is a line that starts with whitespace and a dot; the modifier
     * function's own declaration (`internal fun Modifier.name(`) is not one, and
     * counting it would make every file look like it applies one more than it
     * does.
     */
    private fun calls(source: String, name: String): Int =
        Regex("\\s\\." + Regex.escape(name) + "\\(").findAll(source).count()

    private fun body(source: String, after: String, before: String): String {
        assertTrue("missing marker: $after", source.contains(after))
        assertTrue("missing marker: $before", source.contains(before))
        return source.substringAfter(after).substringBefore(before)
    }

    /** The same slice, taken from a named source file. */
    private fun bodyOf(path: String, after: String, before: String): String =
        body(source(path), after, before)

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

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SLOTS = "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
    }
}
