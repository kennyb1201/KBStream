package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's league picker: two reported faults, pinned where they can be seen.
 *
 *  - "the leagues screen loses focus to the screen behind it" - the panel was a
 *    scrim painted inside the hub's own layout, so the screen behind it stayed
 *    in the focus tree and a D-pad press could walk out of the list and keep
 *    moving through the rails behind the dim.
 *  - "the last button is truncated" - the plate was a fixed run of dp (a 420dp
 *    list plus its chrome), so on a box reporting a short logical height the
 *    panel ran off the bottom of the screen and took the DONE button with it.
 *
 * Neither is reachable from a unit test: there is no TV in CI, and this module
 * has no Compose UI harness for a screen that wants a ViewModel, a live tick
 * and ESPN data. So the structure that fixes them is read out of the source,
 * the way the other UI contracts in this tree are - which also means the fix
 * cannot be quietly undone by the next edit to this panel.
 */
class SportsLeaguesPanelContractTest {

    private val panel: String by lazy {
        val src = source()
        val start = src.indexOf("private fun LeagueTogglesPanel(")
        assertTrue("LeagueTogglesPanel is missing", start >= 0)
        val end = src.indexOf("private fun LeagueToggleRow(", start)
        assertTrue("the panel's body is not bounded by LeagueToggleRow", end > start)
        src.substring(start, end)
    }

    @Test
    fun `the picker is raised as a dialog window, so focus cannot leave it`() {
        assertTrue(
            "a scrim drawn inside the hub's layout leaves the screen BEHIND it in the focus tree, " +
                "which is how the D-pad walked out of the panel and into the rails",
            panel.contains("androidx.compose.ui.window.Dialog(")
        )
        assertTrue(
            "and it is the app's shared dialog plate, like every other dialog",
            panel.contains("KBDialogPanel(")
        )
    }

    @Test
    fun `the plate is sized against the window and the list gives up the slack`() {
        assertTrue(
            "the plate's height must be relative to the window, not a fixed run of dp",
            panel.contains("fillMaxHeight(")
        )
        assertFalse(
            "a fixed-height list is what pushed DONE off the bottom of a short screen",
            panel.contains(".height(420.dp)")
        )
        assertTrue(
            "the rows take whatever height is left after the heading and the button",
            panel.contains(".weight(1f)")
        )
        val list = panel.indexOf(".weight(1f)")
        val done = panel.indexOf("KBButton(label = \"DONE\"")
        assertTrue("DONE must still be drawn", done >= 0)
        assertTrue(
            "DONE must follow the list, inside the plate, so it is laid out after the list takes the slack",
            done > list
        )
    }

    @Test
    fun `opening the picker puts focus on its first row`() {
        assertTrue(
            "the panel owns the requester it hands to the first row",
            panel.contains("remember { FocusRequester() }")
        )
        assertTrue(
            "and asks for it when the panel opens, rather than leaving focus on the LEAGUES button " +
                "that raised it",
            panel.contains("firstRow.requestFocus()")
        )
        assertTrue(
            "handed to the first row and no other, so every open starts at the top",
            panel.contains("focusRequester = firstRow.takeIf { index == 0 }")
        )
        assertTrue(
            "and the row has to spend it on the card the viewer can actually focus",
            source().contains("Modifier.focusRequester(focusRequester)")
        )
    }

    @Test
    fun `each league can be moved a place, and the ends cannot`() {
        assertTrue(
            "the panel renders the catalog in the viewer's own order, not the raw list",
            panel.contains("SportsLeagues.ordered(order)")
        )
        assertTrue(
            "with a stop up and a stop down for every row",
            panel.contains("onMoveUp = { onMove(league.path, -1) }") &&
                panel.contains("onMoveDown = { onMove(league.path, 1) }")
        )
        assertTrue(
            "the first row cannot move up and the last cannot move down",
            panel.contains("canMoveUp = index > 0") &&
                panel.contains("canMoveDown = index < orderedLeagues.lastIndex")
        )
        assertTrue(
            "and the hub persists the move rather than only redrawing the panel",
            source().contains("onMove = viewModel::moveLeague")
        )
        assertTrue(
            "so the tabs themselves follow the new order",
            source().contains("SportsLeagues.enabled(enabled, leagueOrder)")
        )
    }

    private fun source(): String {
        val file = File(findSourceRoot(), SCREEN)
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

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
    }
}
