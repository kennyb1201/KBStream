package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top bar TAKES focus when it is summoned - every time, and with one more Up
 * able to recover it.
 *
 * Field report (Onn, 0.9.25): pressing Up from Continue Watching faded the bar
 * in but left focus on the card, and further Up presses did nothing until Back
 * dismissed it. Two defects composed into that: the hand-off treated
 * `requestFocus()`'s return value as success, so a request that landed and was
 * then taken back by the rail ended the drive (and a first chip that missed the
 * whole twelve-frame window ended it silently); and the effect was keyed on the
 * `showTopBar` Boolean, so re-summoning an already-visible bar changed nothing
 * and the hand-off was never re-driven.
 *
 * Read from the source: this is Compose focus wiring that needs a TV and a
 * remote to press.
 */
class HomeTopBarHandOffContractTest {

    private val home = normalized(stripComments(source(HOME)))
    private val mobile = normalized(stripComments(source(MOBILE)))

    /** The hand-off effect's body, without the effect that follows it. */
    private val handOff: String =
        home.substringAfter("LaunchedEffect(showTopBar, topBarSummons) {")
            .substringBefore("LaunchedEffect(")

    @Test
    fun `success is focus observed inside the bar, not the request's answer`() {
        assertTrue(
            "the drive exits on focus the bar reports for its own subtree",
            handOff.contains("var frames = 0") &&
                handOff.contains(
                    "while (!topBarHasFocus && frames < HOME_TOP_BAR_DRIVE_MAX_FRAMES) {"
                ) &&
                handOff.contains("runCatching { topBarFocusRequester.requestFocus() }")
        )
        assertFalse(
            "and not on the request's return value, which answers true for a " +
                "node that is about to lose focus again",
            handOff.contains("landed = runCatching { topBarFocusRequester")
        )
        assertFalse(
            "the fixed twelve-frame terminal budget is gone from this path",
            handOff.contains("HOME_FOCUS_RETRY_FRAMES")
        )
        assertTrue(
            "the bar reports where focus is from its Row, which aggregates " +
                "every chip beneath it",
            home.contains("onBarFocusChanged: (Boolean) -> Unit,") &&
                home.contains(".onFocusChanged { onBarFocusChanged(it.hasFocus) },") &&
                home.contains("onBarFocusChanged = { topBarHasFocus = it },")
        )
    }

    @Test
    fun `the drive lasts as long as the bar and says so at both outcomes`() {
        assertTrue(
            "a bar that leaves drops the stale focus report, so the next summon " +
                "starts from not-landed",
            handOff.contains("if (!showTopBar) { topBarHasFocus = false return@LaunchedEffect }")
        )
        assertTrue(
            "a landed hand-off logs the frames it took (info, because Fire TV " +
                "suppresses debug logs)",
            handOff.contains(
                "Log.i( \"HOME_FOCUS\", \"Top bar hand-off landed after \" + " +
                    "frames + \" frames\" )"
            )
        )
        assertTrue(
            "and the runaway cap is a WARN, never a silent give-up",
            handOff.contains("Log.w( \"HOME_FOCUS\",") &&
                handOff.contains("\"bar still unfocused; Up re-drives it\"")
        )
        assertTrue(
            "the cap is a runaway guard measured against seconds at frame rate, " +
                "not the twelve-frame budget the DOWN hand-back uses",
            home.contains("private const val HOME_TOP_BAR_DRIVE_MAX_FRAMES = 180") &&
                home.contains(
                    "attempts < HOME_FOCUS_RETRY_FRAMES"
                )
        )
    }

    @Test
    fun `every summon re-arms the hand-off`() {
        assertTrue(
            "the effect keys on the summon counter as well as the Boolean, so a " +
                "press on an already-visible bar re-drives it",
            home.contains("LaunchedEffect(showTopBar, topBarSummons) {") &&
                home.contains("var topBarSummons by remember { mutableStateOf(0) }")
        )
        assertEquals(
            "both summon paths - the short press on the top rail and the " +
                "hold-Up shortcut - bump it",
            2,
            home.split("topBarSummons++").size - 1
        )
        assertTrue(
            "and the short press still captures the card it was pressed on, so " +
                "the dismiss can put focus back there",
            home.contains("fun openTopBar( requester: FocusRequester ) " +
                "{ lastPosterFocusRequester = requester showTopBar = true topBarSummons++ }")
        )
        assertTrue(
            "the hold-Up path keeps its own restore target and gains the same " +
                "re-arm",
            home.contains("fun openTopBarFromHold(restoreTarget: FocusRequester?) {") &&
                handOff.isNotEmpty()
        )
    }

    @Test
    fun `the dismiss and the trip back down are untouched`() {
        assertTrue(
            "Back still restores focus to the card the bar was summoned from",
            home.contains(
                "fun dismissTopBar() { showTopBar = false " +
                    "restoreFocus(focusedCardRequester ?: lastPosterFocusRequester) }"
            )
        )
        assertTrue(
            "and the hand-back DOWN keeps its own verified retry, which is a " +
                "different failure shape",
            home.contains("landed = runCatching { requester.requestFocus() }") &&
                home.contains("while (!landed && attempts < HOME_FOCUS_RETRY_FRAMES) {")
        )
    }

    @Test
    fun `mobile Home is untouched`() {
        assertFalse(
            "the portrait top bar is not a summon-and-dismiss overlay",
            mobile.contains("HOME_FOCUS") || mobile.contains("topBarSummons")
        )
    }

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
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
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
    }
}
