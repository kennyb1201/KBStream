package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sometimes it doesn't let me scroll down into the games."
 *
 * The cause was the hub's own good intention. A game the playlist cannot carry
 * is not focusable - a run of dead cards should not cost the D-pad a stop - so
 * focus cannot walk past one, and a section of them has no focus target below
 * for the LazyColumn to scroll to. The viewer sits on the tab row, presses
 * Down, and nothing moves: not the focus, not the list.
 *
 * The fix is that the press has a second answer. Down/Up first asks the
 * platform's own focus move, exactly as before, and only when there is nothing
 * to focus does the same press scroll the list. Pinned here because there is no
 * TV in CI: the wiring is read out of the source.
 */
class SportsHubScrollContractTest {

    /** The source with runs of whitespace collapsed to one space. */
    private val flat: String by lazy { source().replace(Regex("\\s+"), " ") }

    @Test
    fun `a Down press moves focus, and scrolls when it cannot`() {
        assertTrue(
            "the fallback has to try the focus move FIRST, or a press that had a target would scroll instead",
            flat.contains("focusManager.moveFocus(direction) || scrollByPage(direction)")
        )
        assertTrue(
            "and it is the D-pad's two directions that are answered",
            flat.contains("Key.DirectionDown -> FocusDirection.Down") &&
                flat.contains("Key.DirectionUp -> FocusDirection.Up")
        )
        assertTrue(
            "the handler sits on the column that owns both the tab row and the list, so a press from the tabs is answered too",
            flat.contains(".padding(horizontal = 24.dp, vertical = 18.dp) .onKeyEvent { event ->")
        )
    }

    @Test
    fun `the list state belongs to the screen, not to the body`() {
        // The tab row is a sibling of the list, so a fallback that only knew
        // about the list would never see a press made from the tabs.
        assertTrue(
            "the screen owns the state",
            flat.contains("val listState = rememberLazyListState()")
        )
        assertTrue(
            "and the body draws the list with it",
            flat.contains("state = listState,")
        )
        assertTrue(
            "it is handed down, not re-created per section",
            flat.contains("listState: LazyListState,")
        )
    }

    @Test
    fun `the scroll is bounded, and only happens when the list can move`() {
        assertTrue(
            "a press at the end of the list must not consume itself",
            flat.contains("listState.canScrollForward") && flat.contains("listState.canScrollBackward")
        )
        assertTrue(
            "the distance is the pure rule's",
            flat.contains("sportsScrollStep(rowHeight = row, viewportHeight = viewport)")
        )
        assertTrue(
            "and it is a real scroll, in the direction the press asked for",
            flat.contains("listState.animateScrollBy(") &&
                flat.contains("if (direction == FocusDirection.Down) step.toFloat() else -step.toFloat()")
        )
    }

    @Test
    fun `unplayable cards are still not focus stops`() {
        // The fallback exists to make the grid reachable DESPITE this choice,
        // not to replace it: making every dead card focusable would put the
        // D-pad back to a press per card that cannot do anything.
        val card = slice("private fun GameCard(", "private fun TeamColumn(")
        assertTrue(
            "only a playable card goes through KBCard",
            card.contains("if (playable) {") && card.contains("KBCard(")
        )
        assertTrue(
            "the other branch is a plain Surface",
            card.contains("Surface(")
        )
    }

    private fun slice(startMarker: String, endMarker: String): String {
        val src = source()
        val start = src.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = src.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return src.substring(start, end)
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
