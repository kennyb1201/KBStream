package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar's summon from the shell: a Left or Up press that has nowhere to go.
 *
 * The bar is over every TV screen that does not own one (`hostsAppTopBar`), and
 * the gesture has to be the same everywhere: Left at the start of a row, Up on
 * the first row of the screen. Both are dead presses where the viewer is
 * standing - there is no step to preserve - so the key runs the focus search,
 * takes the ordinary step when there is one, and opens the bar when the search
 * answers that there is nothing that way.
 *
 * Asymmetry that must survive: a screen with its own meaning for the key (TV
 * Guide's Left group switch, Library's calendar Left, Spotlight's Up to the
 * hero pills) consumes it first. The shell reads the bubble phase, so those
 * presses never reach this handler - pinned here as the reason the handler is
 * not a preview handler.
 */
class ShellTopBarSummonContractTest {

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

    private fun activity(): String =
        File(sourceRoot, "com/kennyb1201/kbstream/MainActivity.kt").readText()

    /** The shell's key handler, from the guard to its end. */
    // The shell's screen Box fills the height of the row the navigation rail
    // shares with it (adaptive-layout spec, F2), so the slice starts at the
    // Box's own modifier rather than the width one it carried before the rail.
    private fun handler(): String =
        activity().substringAfter("Box(modifier = Modifier.weight(1f).fillMaxHeight()")
            .substringBefore("AnimatedContent(")

    @Test
    fun `both dead presses summon, and only those two`() {
        val handler = handler()
        assertTrue(
            "Left must still summon from the head of a row",
            handler.contains("Key.DirectionLeft -> FocusDirection.Left")
        )
        assertTrue(
            "Up must summon from the first row of the screen, which is the ask",
            handler.contains("Key.DirectionUp -> FocusDirection.Up")
        )
        assertTrue(
            "every other key is the focus system's",
            handler.contains("else -> return@onKeyEvent false")
        )
    }

    @Test
    fun `the press only opens the bar when the search has nowhere to go`() {
        val handler = handler()
        assertTrue(
            "the search runs first - an ordinary step must not open the bar",
            handler.contains("if (!appTopBarFocus.moveFocus(direction)) {") &&
                handler.contains("appTopBarVisible = true")
        )
        assertTrue(
            "and the press is consumed after the step, so the focus system does " +
                "not run the same search twice",
            handler.contains("appTopBarVisible = true\n                }\n                true")
        )
    }

    @Test
    fun `the bar is only summonable where it is armed`() {
        val handler = handler()
        assertTrue(
            "no bar, no summon: the same `appTopBarArmed` gate every other path uses",
            handler.contains("if (!appTopBarArmed || event.type != KeyEventType.KeyDown)")
        )
        val activity = activity()
        assertTrue(
            "armed is television, unlocked, and a screen that hosts the bar",
            activity.contains(
                "FormFactor.isTv && !timeLocked.locked && screen.hostsAppTopBar()"
            )
        )
    }

    @Test
    fun `the shell reads the press after the screen, not before it`() {
        val handler = handler()
        assertTrue(
            "an onKeyEvent on the shell is the bubble phase; a preview handler " +
                "would take Left from TV Guide and Up from Spotlight",
            handler.contains(".onKeyEvent {") && !handler.contains("onPreviewKeyEvent")
        )
        assertFalse(
            "nothing here may gate on a hold or a release: the summon is the short press",
            handler.contains("KeyEventType.KeyUp") || handler.contains("KeyEventType.LongPress")
        )
    }
}
